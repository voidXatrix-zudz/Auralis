package com.auralis.app.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.auralis.app.domain.audio.EqPresets
import com.auralis.app.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** One real hardware EQ band, read from the device's Equalizer effect. */
data class EffectBand(
    val index: Int,
    val centerHz: Int,
    val minMillibels: Int,
    val maxMillibels: Int,
    val levelMillibels: Int,
)

/** Snapshot the Equalizer screen renders. Band levels are only refreshed on structural changes. */
data class AudioEffectsState(
    val sessionId: Int = 0,
    val attached: Boolean = false,
    val hasControl: Boolean = false,
    val equalizerAvailable: Boolean = false,
    val equalizerEnabled: Boolean = false,
    val bands: List<EffectBand> = emptyList(),
    val bassAvailable: Boolean = false,
    val bassStrengthSupported: Boolean = false,
    val bassEnabled: Boolean = false,
    val bassStrength: Int = 0,
    val virtualizerAvailable: Boolean = false,
    val virtualizerStrengthSupported: Boolean = false,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
    val preampDb: Float = 0f,
    val limiterAvailable: Boolean = false,
    val limiterEnabled: Boolean = true,
    val message: String? = null,
)

/**
 * Owns the platform audio effects attached to the real ExoPlayer audio session.
 *
 * The playback service creates and updates this singleton, so effects also work while the UI is
 * closed. It reads the device's real band count/frequencies/ranges, applies gains in millibels,
 * re-creates effects whenever Media3 reports a new audio session id, and reports unsupported
 * features instead of pretending to change them.
 */
object AudioEffectsEngine {
    private const val TAG = "AuralisEffects"

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null

    private var sessionId: Int = AudioEffect.ERROR_BAD_VALUE
    private var settings: AppSettings = AppSettings()
    /** Current hardware levels in millibels, kept in sync with drags so the limiter can see them. */
    private var liveLevels: List<Int> = emptyList()

    private val _state = MutableStateFlow(AudioEffectsState())
    val state: StateFlow<AudioEffectsState> = _state.asStateFlow()

    @Synchronized
    fun current(): AudioEffectsState = _state.value

    /**
     * Attaches to [audioSessionId] and applies [initial]. Safe to call repeatedly: a new session id
     * releases the old effects and re-creates them; the same session only re-applies settings.
     */
    @Synchronized
    fun attach(audioSessionId: Int, initial: AppSettings) {
        settings = initial
        if (audioSessionId <= 0) {
            Log.i(TAG, "attach skipped: audio session not ready (id=$audioSessionId)")
            return
        }
        if (audioSessionId == sessionId && equalizer != null) {
            applyLocked()
            return
        }
        releaseLocked()
        sessionId = audioSessionId
        val messages = mutableListOf<String>()

        equalizer = try {
            Equalizer(0, audioSessionId)
        } catch (error: Exception) {
            Log.w(TAG, "Equalizer unavailable for session $audioSessionId", error)
            messages += "Equalizer is not available on this device"
            null
        }
        bassBoost = try {
            BassBoost(0, audioSessionId)
        } catch (error: Exception) {
            Log.w(TAG, "Bass boost unavailable", error)
            null
        }
        virtualizer = try {
            Virtualizer(0, audioSessionId)
        } catch (error: Exception) {
            Log.w(TAG, "Virtualizer unavailable", error)
            null
        }
        loudness = try {
            LoudnessEnhancer(audioSessionId)
        } catch (error: Exception) {
            Log.w(TAG, "Loudness enhancer unavailable", error)
            null
        }

        val eqControl = equalizer?.let { runCatching { it.hasControl() }.getOrDefault(false) } ?: false
        val bassSupported = bassBoost?.let { runCatching { it.strengthSupported }.getOrDefault(false) } ?: false
        val virtSupported = virtualizer?.let { runCatching { it.strengthSupported }.getOrDefault(false) } ?: false
        Log.i(
            TAG,
            "attach session=$audioSessionId eq=${equalizer != null} eqControl=$eqControl " +
                "bass=${bassBoost != null} bassSupported=$bassSupported " +
                "virtualizer=${virtualizer != null} virtualizerSupported=$virtSupported " +
                "loudness=${loudness != null}",
        )
        if (!eqControl && equalizer != null) {
            messages += "Another app may be controlling the equalizer; changes may be ignored"
        }

        _state.value = AudioEffectsState(
            sessionId = audioSessionId,
            attached = equalizer != null,
            hasControl = eqControl,
            equalizerAvailable = equalizer != null,
            bassAvailable = bassBoost != null,
            bassStrengthSupported = bassSupported,
            virtualizerAvailable = virtualizer != null,
            virtualizerStrengthSupported = virtSupported,
            limiterAvailable = loudness != null,
            message = messages.firstOrNull(),
        )
        refreshBands()
        applyLocked()
    }

    /** Applies persisted settings after every settings change or session change. */
    @Synchronized
    fun apply(newSettings: AppSettings) {
        settings = newSettings
        applyLocked()
    }

    private fun applyLocked() {
        val eq = equalizer
        val bands = readBands()
        if (eq != null && bands.isNotEmpty()) {
            val targets = resolveLevels(settings, bands)
            runCatching {
                // Write levels first, then enable, so turning the EQ on never glitches.
                targets.forEachIndexed { index, level ->
                    eq.setBandLevel(index.toShort(), level.toShort())
                }
                eq.enabled = settings.eqEnabled
            }.onFailure { Log.w(TAG, "Could not apply equalizer", it) }
            liveLevels = targets
        } else {
            liveLevels = bands.map { it.levelMillibels }
        }

        applyBass()
        applyVirtualizer()
        applyPreamp(settings, liveLevels)

        val eqControl = eq?.let { runCatching { it.hasControl() }.getOrDefault(false) } ?: false
        Log.i(
            TAG,
            "apply session=$sessionId eqEnabled=${settings.eqEnabled} hasControl=$eqControl " +
                "bands=${liveLevels.joinToString(",")} " +
                "bass=${settings.bassBoostEnabled}/${bassStrengthFromDb(settings.bassDb)} " +
                "virtualizer=${settings.virtualizerEnabled}/${settings.virtualizerStrength} " +
                "preamp=${settings.preampDb}dB limiter=${settings.limiterEnabled}",
        )

        _state.value = _state.value.copy(
            attached = eq != null,
            hasControl = eqControl,
            equalizerEnabled = settings.eqEnabled,
            bands = bands.mapIndexed { index, band ->
                band.copy(levelMillibels = liveLevels.getOrElse(index) { band.levelMillibels })
            },
            bassEnabled = settings.bassBoostEnabled && _state.value.bassStrengthSupported,
            bassStrength = bassStrengthFromDb(settings.bassDb),
            virtualizerEnabled = settings.virtualizerEnabled && _state.value.virtualizerStrengthSupported,
            virtualizerStrength = settings.virtualizerStrength.coerceIn(0, 1000),
            preampDb = settings.preampDb.coerceIn(0f, 12f),
            limiterEnabled = settings.limiterEnabled,
        )
    }

    /** Real device bands: count, centre frequencies and millibel range. */
    private fun readBands(): List<EffectBand> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            val count = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val min = range.getOrNull(0)?.toInt() ?: -1500
            val max = range.getOrNull(1)?.toInt() ?: 1500
            (0 until count).map { index ->
                EffectBand(
                    index = index,
                    centerHz = (eq.getCenterFreq(index.toShort()) / 1000).coerceAtLeast(1),
                    minMillibels = min,
                    maxMillibels = max,
                    levelMillibels = eq.getBandLevel(index.toShort()).toInt(),
                )
            }
        }.onFailure { Log.w(TAG, "Could not read device equalizer bands", it) }.getOrDefault(emptyList())
    }

    private fun refreshBands() {
        val bands = readBands()
        if (bands.isNotEmpty()) {
            liveLevels = bands.map { it.levelMillibels }
            _state.value = _state.value.copy(bands = bands)
        }
    }

    /** Prefers the user's saved device-band curve; falls back to the canonical preset curve. */
    private fun resolveLevels(s: AppSettings, bands: List<EffectBand>): List<Int> {
        if (s.eqBandCount == bands.size && s.eqBandLevels.size == bands.size) {
            return bands.mapIndexed { index, band ->
                s.eqBandLevels[index].coerceIn(band.minMillibels, band.maxMillibels)
            }
        }
        return bands.map { band ->
            canonicalGainMb(s.eqGains, band.centerHz).coerceIn(band.minMillibels, band.maxMillibels)
        }
    }

    /** Maps the ten canonical preset bands onto a real device band using log-frequency distance. */
    private fun canonicalGainMb(gainsDb: List<Float>, centerHz: Int): Int {
        val index = EqPresets.FREQS_HZ.indices.minByOrNull { candidate ->
            abs(ln(EqPresets.FREQS_HZ[candidate].toDouble()) - ln(centerHz.coerceAtLeast(1).toDouble()))
        } ?: 0
        return (gainsDb.getOrElse(index) { 0f } * 100f).roundToInt()
    }

    private fun applyBass() {
        val bass = bassBoost ?: return
        runCatching {
            val supported = bass.strengthSupported
            val enabled = supported && settings.bassBoostEnabled
            bass.enabled = enabled
            if (enabled) bass.setStrength(bassStrengthFromDb(settings.bassDb).toShort())
        }.onFailure { Log.w(TAG, "Could not apply bass boost", it) }
    }

    private fun applyVirtualizer() {
        val effect = virtualizer ?: return
        runCatching {
            val supported = effect.strengthSupported
            val enabled = supported && settings.virtualizerEnabled
            effect.enabled = enabled
            if (enabled) effect.setStrength(settings.virtualizerStrength.coerceIn(0, 1000).toShort())
        }.onFailure { Log.w(TAG, "Could not apply virtualizer", it) }
    }

    /**
     * Positive output gain through LoudnessEnhancer. When the limiter is on, headroom equal to the
     * largest positive band boost is removed so a boosted EQ cannot clip the output.
     */
    private fun applyPreamp(s: AppSettings, levels: List<Int>) {
        val effect = loudness ?: return
        runCatching {
            val wanted = (s.preampDb.coerceIn(0f, 12f) * 100f).roundToInt()
            val headroom = if (s.limiterEnabled) (levels.maxOrNull() ?: 0).coerceAtLeast(0) else 0
            val gain = (wanted - headroom).coerceIn(0, 1200)
            effect.setTargetGain(gain)
            effect.enabled = gain > 0
            Log.i(TAG, "preamp wanted=${wanted}mB headroom=${headroom}mB applied=${gain}mB")
        }.onFailure { Log.w(TAG, "Could not apply pre-amp", it) }
    }

    // --- Live setters (called by the Equalizer screen for instant response) ---

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        settings = settings.copy(eqEnabled = enabled)
        equalizer?.let { eq -> runCatching { eq.enabled = enabled }.onFailure { Log.w(TAG, "setEnabled failed", it) } }
        _state.value = _state.value.copy(equalizerEnabled = enabled)
        Log.i(TAG, "eq enabled=$enabled")
    }

    /**
     * Applies one band immediately. Deliberately does not emit the full state on every drag, so the
     * slider stays smooth; the caller persists the final curve on drag end.
     */
    @Synchronized
    fun setBandLevel(index: Int, levelMillibels: Int) {
        val eq = equalizer ?: return
        val band = _state.value.bands.firstOrNull { it.index == index } ?: return
        val bounded = levelMillibels.coerceIn(band.minMillibels, band.maxMillibels)
        runCatching { eq.setBandLevel(index.toShort(), bounded.toShort()) }
            .onFailure { Log.w(TAG, "setBandLevel($index) failed", it) }
        val updated = liveLevels.toMutableList()
        while (updated.size <= index) updated.add(0)
        updated[index] = bounded
        liveLevels = updated
        if (settings.limiterEnabled) applyPreamp(settings, liveLevels)
    }

    @Synchronized
    fun setBassEnabled(enabled: Boolean) {
        settings = settings.copy(bassBoostEnabled = enabled)
        applyBass()
        _state.value = _state.value.copy(bassEnabled = enabled && _state.value.bassStrengthSupported)
        Log.i(TAG, "bass enabled=$enabled")
    }

    @Synchronized
    fun setBassDb(db: Float) {
        settings = settings.copy(bassDb = db.coerceIn(0f, 12f))
        applyBass()
        _state.value = _state.value.copy(bassStrength = bassStrengthFromDb(settings.bassDb))
    }

    @Synchronized
    fun setVirtualizerEnabled(enabled: Boolean) {
        settings = settings.copy(virtualizerEnabled = enabled)
        applyVirtualizer()
        _state.value = _state.value.copy(virtualizerEnabled = enabled && _state.value.virtualizerStrengthSupported)
        Log.i(TAG, "virtualizer enabled=$enabled")
    }

    @Synchronized
    fun setVirtualizerStrength(strength: Int) {
        settings = settings.copy(virtualizerStrength = strength.coerceIn(0, 1000))
        applyVirtualizer()
        _state.value = _state.value.copy(virtualizerStrength = settings.virtualizerStrength)
    }

    @Synchronized
    fun setPreampDb(db: Float) {
        settings = settings.copy(preampDb = db.coerceIn(0f, 12f))
        applyPreamp(settings, liveLevels)
        _state.value = _state.value.copy(preampDb = settings.preampDb)
    }

    @Synchronized
    fun setLimiterEnabled(enabled: Boolean) {
        settings = settings.copy(limiterEnabled = enabled)
        applyPreamp(settings, liveLevels)
        _state.value = _state.value.copy(limiterEnabled = enabled)
    }

    /** Applies a preset to the real device bands and returns the resulting millibel levels. */
    @Synchronized
    fun applyPreset(name: String): List<Int> {
        val eq = equalizer ?: return emptyList()
        val bands = _state.value.bands
        if (bands.isEmpty()) return emptyList()
        val canonical = EqPresets.gains(name)
        val levels = bands.map { band ->
            canonicalGainMb(canonical, band.centerHz).coerceIn(band.minMillibels, band.maxMillibels)
        }
        runCatching {
            levels.forEachIndexed { index, level -> eq.setBandLevel(index.toShort(), level.toShort()) }
        }.onFailure { Log.w(TAG, "Could not apply preset $name", it) }
        liveLevels = levels
        if (settings.limiterEnabled) applyPreamp(settings, liveLevels)
        _state.value = _state.value.copy(
            bands = bands.mapIndexed { index, band -> band.copy(levelMillibels = levels[index]) },
        )
        Log.i(TAG, "preset=$name levels=${levels.joinToString(",")}")
        return levels
    }

    @Synchronized
    fun release() {
        releaseLocked()
        sessionId = AudioEffect.ERROR_BAD_VALUE
        liveLevels = emptyList()
        _state.value = AudioEffectsState()
        Log.i(TAG, "released")
    }

    private fun releaseLocked() {
        runCatching { equalizer?.enabled = false }
        runCatching { bassBoost?.enabled = false }
        runCatching { virtualizer?.enabled = false }
        runCatching { loudness?.enabled = false }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudness = null
    }

    private fun bassStrengthFromDb(db: Float): Int =
        ((db.coerceIn(0f, 12f) / 12f) * 1000f).roundToInt().coerceIn(0, 1000)
}
