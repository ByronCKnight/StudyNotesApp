package com.studynotes.app.update

import org.json.JSONObject

/** A release version "major.minor.patch", as in the tag of a GitHub release ("v1.2.3"). */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+].*)?$""")

        /**
         * "v1.2.3", "1.2.3", "1.2" (patch 0) or "1.2.3-beta" (suffix ignored); null when [text] is
         * not a version.
         */
        fun parse(text: String): AppVersion? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            return runCatching {
                AppVersion(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3].ifEmpty { "0" }.toInt())
            }.getOrNull()
        }
    }
}

/**
 * A published release with an APK attached. [apiUrl] is the asset's GitHub API URL, which works
 * for a private repository with a token; [publicUrl] is the browser download link, which only
 * works for a public one.
 */
data class Release(
    val version: AppVersion,
    val tag: String,
    val notes: String,
    val apkName: String,
    val apiUrl: String,
    val publicUrl: String,
    val apkSize: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("tag", tag)
        put("notes", notes)
        put("apkName", apkName)
        put("apiUrl", apiUrl)
        put("publicUrl", publicUrl)
        put("apkSize", apkSize)
    }.toString()

    companion object {
        /** The release saved by [toJson]; null when [json] is not one. */
        fun fromJson(json: String): Release? = runCatching {
            val o = JSONObject(json)
            val tag = o.getString("tag")
            Release(
                version = AppVersion.parse(tag) ?: return null,
                tag = tag,
                notes = o.optString("notes"),
                apkName = o.getString("apkName"),
                apiUrl = o.getString("apiUrl"),
                publicUrl = o.getString("publicUrl"),
                apkSize = o.optLong("apkSize", -1L),
            )
        }.getOrNull()
    }
}

/** Pure decisions of the update check, so they are unit-testable. */
object UpdatePolicy {

    /**
     * The release described by GitHub's "latest release" JSON (`GET /repos/{repo}/releases/latest`),
     * or null when it has no APK attached or its tag is not a version.
     */
    fun parseRelease(json: String): Release? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = o.optString("tag_name")
        val version = AppVersion.parse(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            return Release(
                version = version,
                tag = tag,
                notes = o.optString("body"),
                apkName = name,
                apiUrl = a.optString("url"),
                publicUrl = a.optString("browser_download_url"),
                apkSize = a.optLong("size", -1L),
            )
        }
        return null
    }

    /** Every newer release is mandatory: the app cannot be used until it is installed. */
    fun updateRequired(installed: AppVersion, latest: AppVersion): Boolean = latest > installed

    /**
     * Android's versionCode for a version: 1.2.3 -> 1002003, so every release can be installed
     * over the previous one. The same formula is in app/build.gradle.kts.
     */
    fun versionCode(v: AppVersion): Int = v.major * 1_000_000 + v.minor * 1_000 + v.patch
}
