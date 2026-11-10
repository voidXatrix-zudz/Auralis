package com.auralis.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.auralis.app.BuildConfig
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Everything the update dialog, the Settings row and the dock badge need. */
data class UpdateUiState(
    val available: ReleaseInfo? = null,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val showDialog: Boolean = false,
    /** True when the user skipped this exact release; automatic checks stay silent then. */
    val skipped: Boolean = false,
    val checking: Boolean = false,
    /** Last check outcome, shown in the Settings row ("You're up to date" or the error). */
    val status: String? = null,
    /** 0f..1f while an APK download runs, null when idle. */
    val downloadProgress: Float? = null,
    val readyToInstall: Boolean = false,
    /** True after a failed APK download; the dialog then offers "Open on GitHub". */
    val downloadFailed: Boolean = false,
)

/**
 * Owns the whole update flow: the 12-hour automatic check, manual checks, the skipped version,
 * APK download into the cache and handing the file to the package installer.
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checker = UpdateChecker(BuildConfig.VERSION_NAME)
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val downloadsDir = File(context.cacheDir, "updates")
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingInstall: File? = null
    private var started = false

    /** Called once per app process: cleans old APKs, then runs the throttled launch check. */
    fun start() {
        if (started) return
        started = true
        scope.launch { cleanupOldDownloads() }
        checkJob = scope.launch { autoCheck() }
    }

    fun checkNow() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runCheck(manual = true) }
    }

    fun later() {
        _state.update { it.copy(showDialog = false) }
    }

    fun skipVersion() {
        val tag = _state.value.available?.tag ?: return
        _state.update { it.copy(showDialog = false, skipped = true) }
        scope.launch { runCatching { settings.update { it.copy(skippedUpdateTag = tag) } } }
    }

    fun openReleasePage() {
        val url = _state.value.available?.pageUrl ?: UpdateConfig.RELEASES_PAGE
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { AppLog.w("Updater", "Could not open the release page", it) }
    }

    fun downloadUpdate() {
        val release = _state.value.available ?: return
        val url = release.apkUrl ?: run {
            _messages.tryEmit("This release has no APK attached")
            return
        }
        if (downloadJob?.isActive == true) return
        downloadsDir.mkdirs()
        val target = File(downloadsDir, "auralis-${release.version}.apk")
        val part = File(downloadsDir, "${target.name}.part")

        downloadJob = scope.launch {
            _state.update { it.copy(downloadProgress = 0f, readyToInstall = false, downloadFailed = false) }
            try {
                runInterruptible(Dispatchers.IO) {
                    download(url, part) { progress -> _state.update { it.copy(downloadProgress = progress) } }
                }
                part.delete()
                if (!target.exists()) throw IOException("The downloaded file is missing")
                _state.update { it.copy(downloadProgress = null, readyToInstall = true, downloadFailed = false) }
                install(target)
            } catch (cancel: CancellationException) {
                part.delete()
                _state.update { it.copy(downloadProgress = null, readyToInstall = false) }
                throw cancel
            } catch (error: Throwable) {
                part.delete()
                _state.update { it.copy(downloadProgress = null, readyToInstall = false, downloadFailed = true) }
                AppLog.e("Updater", "APK download failed", error)
                _messages.tryEmit("Download failed. Open GitHub to get the update.")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.update { it.copy(downloadProgress = null, readyToInstall = false) }
    }

    fun installDownloaded() {
        val version = _state.value.available?.version ?: return
        install(File(downloadsDir, "auralis-$version.apk"))
    }

    /** MainActivity.onResume: finish the install after the user granted "install unknown apps". */
    fun onAppForeground() {
        val file = pendingInstall ?: return
        if (file.exists() && context.packageManager.canRequestPackageInstalls()) install(file)
    }

    private suspend fun autoCheck() {
        val last = runCatching { settings.settings.first().lastUpdateCheckAt }.getOrDefault(0L)
        if (System.currentTimeMillis() - last < UpdateConfig.CHECK_INTERVAL_MS) return
        runCheck(manual = false)
    }

    private suspend fun runCheck(manual: Boolean) {
        _state.update { it.copy(checking = true) }
        val result = checker.check()
        if (result is UpdateCheck.Failed) {
            AppLog.w("Updater", "Update check failed: ${result.message}", result.error)
        }
        runCatching { settings.update { it.copy(lastUpdateCheckAt = System.currentTimeMillis()) } }

        val skippedTag = runCatching { settings.settings.first().skippedUpdateTag }.getOrDefault("")
        when (result) {
            is UpdateCheck.Available -> {
                val skipped = !manual && skippedTag == result.release.tag
                _state.update {
                    it.copy(
                        available = result.release,
                        currentVersion = result.currentVersion,
                        checking = false,
                        status = "Version ${result.release.version} is available",
                        skipped = skipped,
                        showDialog = !skipped,
                    )
                }
            }
            is UpdateCheck.UpToDate -> {
                _state.update {
                    it.copy(
                        available = null, checking = false, showDialog = false, skipped = false,
                        status = "You're up to date",
                    )
                }
                if (manual) _messages.tryEmit("You're up to date")
            }
            UpdateCheck.NoRelease -> {
                _state.update {
                    it.copy(
                        available = null, checking = false, showDialog = false, skipped = false,
                        status = "You're up to date",
                    )
                }
                if (manual) _messages.tryEmit("You're up to date — no releases published yet")
            }
            is UpdateCheck.Failed -> {
                _state.update { it.copy(checking = false, status = result.message) }
                if (manual) _messages.tryEmit(result.message)
            }
        }
    }

    /** Downloads to a `.part` file with progress, then renames it to the final APK name. */
    private fun download(url: String, part: File, onProgress: (Float) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub returned HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (!part.renameTo(File(part.parentFile, part.name.removeSuffix(".part")))) {
                throw IOException("Could not save the update")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun install(file: File) {
        if (!file.exists()) {
            _messages.tryEmit("The update file is missing. Download it again.")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            _messages.tryEmit("Allow installs from AURALIS, then come back")
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { AppLog.w("Updater", "Could not open install permission settings", it) }
            return
        }
        pendingInstall = null
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            AppLog.e("Updater", "Could not start the package installer", it)
            _messages.tryEmit("Could not start the installer")
        }
    }

    /** Removes APKs for versions at or below the one now installed (a finished update). */
    private fun cleanupOldDownloads() {
        val installed = parseVersion(BuildConfig.VERSION_NAME) ?: return
        downloadsDir.listFiles()?.forEach { file ->
            val version = parseVersion(file.name) ?: return@forEach
            if (compareVersions(version, installed) <= 0 && !file.delete()) {
                AppLog.w("Updater", "Could not delete an old update file")
            }
        }
    }
}
