package com.studynotes.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.studynotes.app.model.Palette
import com.studynotes.app.model.Tool
import com.studynotes.app.model.ToolState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class SortOrder { RECENT, NAME, DATE_ADDED }

/** User preferences. Immutable snapshot; change through [AppSettings.update]. */
data class Settings(
    /**
     * Palm rejection: when true only a stylus draws and fingers always scroll/zoom.
     * null = not decided yet; the editor switches it to true the first time a stylus touches the screen.
     */
    val stylusOnly: Boolean? = null,
    val autosaveMinutes: Int = DEFAULT_AUTOSAVE_MINUTES,
    val toolState: ToolState = ToolState(),
    val sortOrder: SortOrder = SortOrder.RECENT,
    val onboardingDone: Boolean = false,
) {
    companion object {
        const val DEFAULT_AUTOSAVE_MINUTES = 5
        val AUTOSAVE_CHOICES = listOf(1, 2, 5, 10)
    }
}

/**
 * SharedPreferences-backed settings exposed as a [StateFlow]. Also stores small per-document
 * bookkeeping (last-opened times) and the last folder used in the system picker per provider.
 * Thread-safe: all mutation goes through synchronized [update].
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("studynotes_settings", Context.MODE_PRIVATE)
    private val recentsPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("studynotes_recents", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        write(next)
        _state.value = next
    }

    fun updateToolState(transform: (ToolState) -> ToolState) =
        update { it.copy(toolState = transform(it.toolState)) }

    // ---- Recents (fileName -> last opened epoch millis) ----

    fun markOpened(fileName: String, at: Long = System.currentTimeMillis()) =
        recentsPrefs.edit { putLong(fileName, at) }

    fun lastOpened(fileName: String): Long? =
        recentsPrefs.getLong(fileName, -1L).takeIf { it > 0 }

    fun renameRecent(oldFileName: String, newFileName: String) {
        val t = lastOpened(oldFileName) ?: return
        recentsPrefs.edit {
            remove(oldFileName)
            putLong(newFileName, t)
        }
    }

    fun removeRecent(fileName: String) = recentsPrefs.edit { remove(fileName) }

    // ---- Last folder per document provider, used as EXTRA_INITIAL_URI for the system picker ----

    fun lastPickerUri(authority: String): String? = prefs.getString(KEY_PICKER_PREFIX + authority, null)

    fun setLastPickerUri(authority: String, uri: String) =
        prefs.edit { putString(KEY_PICKER_PREFIX + authority, uri) }

    // ---- Persistence ----

    private fun read(): Settings {
        val defaults = Settings()
        val stylusOnly = when (prefs.getInt(KEY_STYLUS_ONLY, -1)) {
            0 -> false
            1 -> true
            else -> null
        }
        val tools = prefs.getString(KEY_TOOLS, null)?.let { runCatching { decodeTools(JSONObject(it)) }.getOrNull() }
            ?: defaults.toolState
        return Settings(
            stylusOnly = stylusOnly,
            autosaveMinutes = prefs.getInt(KEY_AUTOSAVE, defaults.autosaveMinutes).coerceIn(1, 60),
            toolState = tools,
            sortOrder = runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT, null)!!) }.getOrDefault(defaults.sortOrder),
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false),
        )
    }

    private fun write(s: Settings) = prefs.edit {
        putInt(KEY_STYLUS_ONLY, when (s.stylusOnly) { null -> -1; false -> 0; true -> 1 })
        putInt(KEY_AUTOSAVE, s.autosaveMinutes)
        putString(KEY_TOOLS, encodeTools(s.toolState).toString())
        // Show/Hide notes is per document and per session now; drop the old app-wide value.
        remove(LEGACY_KEY_NOTES_VISIBLE)
        putString(KEY_SORT, s.sortOrder.name)
        putBoolean(KEY_ONBOARDING, s.onboardingDone)
    }

    private fun encodeTools(t: ToolState) = JSONObject().apply {
        put("tool", t.tool.name)
        put("hlColor", t.highlighterColor)
        put("hlWidth", t.highlighterWidth.toDouble())
        put("penColor", t.penColor)
        put("penWidth", t.penWidth.toDouble())
        put("noteColor", t.noteColor)
        put("snap", t.snapToText)
        put("straight", t.straightHighlighter)
    }

    private fun decodeTools(o: JSONObject): ToolState {
        val d = ToolState()
        return ToolState(
            tool = runCatching { Tool.valueOf(o.getString("tool")) }.getOrDefault(d.tool),
            highlighterColor = o.optInt("hlColor", d.highlighterColor),
            highlighterWidth = o.optDouble("hlWidth", d.highlighterWidth.toDouble()).toFloat()
                .takeIf { it.isFinite() && it > 0f } ?: d.highlighterWidth,
            penColor = o.optInt("penColor", d.penColor),
            penWidth = o.optDouble("penWidth", d.penWidth.toDouble()).toFloat()
                .takeIf { it.isFinite() && it > 0f } ?: d.penWidth,
            noteColor = o.optInt("noteColor", Palette.NOTE_COLORS[0]),
            snapToText = o.optBoolean("snap", d.snapToText),
            straightHighlighter = o.optBoolean("straight", d.straightHighlighter),
        )
    }

    private companion object {
        const val KEY_STYLUS_ONLY = "stylus_only"
        const val KEY_AUTOSAVE = "autosave_minutes"
        const val KEY_TOOLS = "tools"
        const val LEGACY_KEY_NOTES_VISIBLE = "notes_visible"
        const val KEY_SORT = "sort"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_PICKER_PREFIX = "picker_uri_"
    }
}
