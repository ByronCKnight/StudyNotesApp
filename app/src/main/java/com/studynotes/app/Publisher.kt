package com.studynotes.app

import java.util.Calendar

/**
 * Who publishes StudyNotes. The one place company details live; the About section of Settings
 * reads from here, so changing a value changes it everywhere.
 *
 * Fill in [website], [supportEmail] and [logo] when they exist: each stays hidden while null.
 */
object Publisher {
    const val NAME = "WireByte Technologies"

    /** Year the first copyrighted version shipped. The notice grows into a range after this year. */
    const val COPYRIGHT_SINCE = 2026

    /** Public website, shown as a tappable link once set, e.g. "https://wirebyte.co.za". */
    val website: String? = null

    /** Support address, shown as a tappable link once set. */
    val supportEmail: String? = null

    /** Drawable resource of the company logo (e.g. `R.drawable.wirebyte_logo`), or null for none. */
    val logo: Int? = null

    /** "© 2026 WireByte Technologies" or "© 2026–2027 WireByte Technologies", for the current year. */
    fun copyright(currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)): String {
        val years = if (currentYear > COPYRIGHT_SINCE) "$COPYRIGHT_SINCE–$currentYear" else "$COPYRIGHT_SINCE"
        return "© $years $NAME"
    }

    const val RIGHTS = "All rights reserved."
}
