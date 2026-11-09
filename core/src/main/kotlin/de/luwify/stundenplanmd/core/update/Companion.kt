package de.luwify.stundenplanmd.core.update

/**
 * Another app of the same author the settings point at ("Mensa MD"): its package and where to get it. This app never
 * downloads or installs it: the in-app install of another app is what made Play Protect block Mensa MD (2026-11).
 */
data class CompanionApp(
    val packageName: String,
    /** Opened in the browser by the banner's download button. */
    val releasesPage: String,
) {
    companion object {
        val MENSA = CompanionApp(
            packageName = "de.luwify.mensamd",
            releasesPage = "https://github.com/Luwify/ovgu-mensa/releases/latest",
        )
    }
}

/** What a banner for a [CompanionApp] shows. */
enum class BannerState { HIDDEN, OPEN, DOWNLOAD }

object CompanionPolicy {
    /**
     * Whether the banner is wanted: what the user chose in the settings ([chosenHidden], null = never chosen), else only
     * while the app isn't installed yet.
     */
    fun wanted(chosenHidden: Boolean?, installed: Boolean): Boolean = chosenHidden?.not() ?: !installed

    /** The banner: hidden by the user, "Open" once installed, else the download button. */
    fun banner(hidden: Boolean, installed: Boolean): BannerState = when {
        hidden -> BannerState.HIDDEN
        installed -> BannerState.OPEN
        else -> BannerState.DOWNLOAD
    }
}
