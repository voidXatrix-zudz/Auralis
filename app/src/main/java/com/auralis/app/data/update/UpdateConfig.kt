package com.auralis.app.data.update

/** Everything the updater needs to know about the GitHub project. Public repo, no tokens. */
object UpdateConfig {
    const val GITHUB_OWNER = "voidXatrix-zudz"
    const val GITHUB_REPO = "Auralis"

    const val RELEASES_PAGE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** Automatic launch check runs at most this often. */
    const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    const val TIMEOUT_MS = 10_000
    const val USER_AGENT = "AURALIS-Updater"
}
