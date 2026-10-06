package com.studynotes.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    @Test
    fun parsesTagsAndPlainVersions() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse("1.2"))
        assertEquals(AppVersion(2, 0, 0), AppVersion.parse("v2"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse(" 1.2.3-beta.1 "))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("1.2.3.4"))
    }

    @Test
    fun comparesNumerically() {
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 9))
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
        assertTrue(AppVersion(1, 0, 1) > AppVersion(1, 0, 0))
        assertEquals(0, AppVersion(1, 0, 0).compareTo(AppVersion(1, 0, 0)))
    }

    @Test
    fun everyNewerReleaseIsRequired_olderOrSameIsNot() {
        assertTrue(UpdatePolicy.updateRequired(AppVersion(1, 0, 0), AppVersion(1, 0, 1)))
        assertFalse(UpdatePolicy.updateRequired(AppVersion(1, 0, 1), AppVersion(1, 0, 1)))
        assertFalse(UpdatePolicy.updateRequired(AppVersion(1, 1, 0), AppVersion(1, 0, 9)))
    }

    @Test
    fun versionCodeGrowsWithEveryPart() {
        assertEquals(1_000_000, UpdatePolicy.versionCode(AppVersion(1, 0, 0)))
        assertEquals(1_002_003, UpdatePolicy.versionCode(AppVersion(1, 2, 3)))
        assertTrue(UpdatePolicy.versionCode(AppVersion(1, 1, 0)) > UpdatePolicy.versionCode(AppVersion(1, 0, 99)))
        // The first release was shipped with versionCode 1, which every computed code exceeds.
        assertTrue(UpdatePolicy.versionCode(AppVersion(1, 0, 0)) > 1)
    }

    private val latestJson = """
        {
          "tag_name": "v1.1.0",
          "name": "StudyNotes 1.1.0",
          "body": "Snap to text previews the selection.",
          "assets": [
            {"name": "StudyNotes-1.1.0.apk.sha256", "url": "https://api.github.com/repos/o/r/releases/assets/1",
             "browser_download_url": "https://github.com/o/r/releases/download/v1.1.0/StudyNotes-1.1.0.apk.sha256", "size": 90},
            {"name": "StudyNotes-1.1.0.apk", "url": "https://api.github.com/repos/o/r/releases/assets/2",
             "browser_download_url": "https://github.com/o/r/releases/download/v1.1.0/StudyNotes-1.1.0.apk", "size": 12345678}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTheLatestReleaseAndPicksTheApk() {
        val r = UpdatePolicy.parseRelease(latestJson)!!
        assertEquals(AppVersion(1, 1, 0), r.version)
        assertEquals("v1.1.0", r.tag)
        assertEquals("Snap to text previews the selection.", r.notes)
        assertEquals("StudyNotes-1.1.0.apk", r.apkName)
        assertEquals("https://api.github.com/repos/o/r/releases/assets/2", r.apiUrl)
        assertEquals("https://github.com/o/r/releases/download/v1.1.0/StudyNotes-1.1.0.apk", r.publicUrl)
        assertEquals(12345678L, r.apkSize)
    }

    @Test
    fun releaseWithoutApkOrVersionTagIsIgnored() {
        assertNull(UpdatePolicy.parseRelease("""{"tag_name": "v1.1.0", "assets": []}"""))
        assertNull(UpdatePolicy.parseRelease("""{"tag_name": "nightly", "assets": [{"name": "a.apk", "url": "u", "browser_download_url": "b"}]}"""))
        assertNull(UpdatePolicy.parseRelease("not json"))
    }

    @Test
    fun releaseRoundTripsThroughJson() {
        val r = UpdatePolicy.parseRelease(latestJson)!!
        assertEquals(r, Release.fromJson(r.toJson()))
        assertNull(Release.fromJson("{}"))
    }
}
