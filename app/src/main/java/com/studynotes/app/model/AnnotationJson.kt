package com.studynotes.app.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToLong

/** Identifies the source document a sidecar belongs to; used to detect that a file changed. */
data class SourceInfo(
    val fileName: String,
    val type: DocType,
    val size: Long,
    val lastModified: Long,
)

/** Contents of one annotation sidecar file. */
data class AnnotationFile(
    val source: SourceInfo,
    val savedAt: Long,
    val annotations: DocumentAnnotations,
)

class AnnotationFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Sidecar JSON (de)serialisation. Format (version 1):
 * ```
 * { "format": "studynotes.annotations", "version": 1,
 *   "source": { "fileName": "a.pdf", "type": "pdf", "size": 1, "lastModified": 1 },
 *   "savedAt": 1,
 *   "viewState": { "page": 0, "offset": 0.0, "zoom": 1.0 },
 *   "pages": [ { "index": 0,
 *       "strokes": [ { "id": "…", "tool": "highlighter", "color": "#FFFFE94D", "width": 14.0,
 *                      "flat": false, "group": null, "points": [x0, y0, x1, y1] } ],
 *       "notes": [ { "id": "…", "x": 1.0, "y": 2.0, "text": "…", "color": "#FFFFF3A6",
 *                    "created": 1, "modified": 1 } ] } ] }
 * ```
 * Decoding is lenient per item: a malformed stroke or note is skipped rather than losing the file.
 */
object AnnotationJson {
    const val FORMAT = "studynotes.annotations"
    const val VERSION = 1

    fun encode(file: AnnotationFile): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("source", JSONObject().apply {
            put("fileName", file.source.fileName)
            put("type", file.source.type.extension)
            put("size", file.source.size)
            put("lastModified", file.source.lastModified)
        })
        root.put("savedAt", file.savedAt)
        val vs = file.annotations.viewState
        root.put("viewState", JSONObject().apply {
            put("page", vs.page)
            put("offset", round4(vs.pageOffsetFraction))
            put("zoom", round4(vs.zoom))
        })
        val pages = JSONArray()
        for ((index, page) in file.annotations.pages.toSortedMap()) {
            if (page.isEmpty) continue
            val strokes = JSONArray()
            for (s in page.strokes) strokes.put(encodeStroke(s))
            val notes = JSONArray()
            for (n in page.notes) notes.put(encodeNote(n))
            pages.put(JSONObject().apply {
                put("index", index)
                put("strokes", strokes)
                put("notes", notes)
            })
        }
        root.put("pages", pages)
        return root.toString()
    }

    fun decode(json: String): AnnotationFile {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw AnnotationFormatException("Not valid JSON", e)
        }
        if (root.optString("format") != FORMAT) throw AnnotationFormatException("Unknown format")
        val version = root.optInt("version", -1)
        if (version < 1 || version > VERSION) throw AnnotationFormatException("Unsupported version $version")

        val src = root.optJSONObject("source") ?: throw AnnotationFormatException("Missing source")
        val type = DocType.fromFileName("x." + src.optString("type"))
            ?: throw AnnotationFormatException("Unknown source type")
        val source = SourceInfo(
            fileName = src.optString("fileName"),
            type = type,
            size = src.optLong("size", -1L),
            lastModified = src.optLong("lastModified", 0L),
        )

        val vsJson = root.optJSONObject("viewState")
        val viewState = if (vsJson == null) ViewState() else ViewState(
            page = vsJson.optInt("page", 0).coerceAtLeast(0),
            pageOffsetFraction = finiteOr(vsJson.optDouble("offset", 0.0), 0.0).toFloat().coerceIn(0f, 1f),
            zoom = finiteOr(vsJson.optDouble("zoom", 1.0), 1.0).toFloat().coerceIn(0.1f, 20f),
        )

        val pages = HashMap<Int, PageAnnotations>()
        val pagesJson = root.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until pagesJson.length()) {
            val p = pagesJson.optJSONObject(i) ?: continue
            val index = p.optInt("index", -1)
            if (index < 0) continue
            val strokes = ArrayList<Stroke>()
            val strokesJson = p.optJSONArray("strokes")
            if (strokesJson != null) for (j in 0 until strokesJson.length()) {
                decodeStroke(strokesJson.optJSONObject(j))?.let(strokes::add)
            }
            val notes = ArrayList<Note>()
            val notesJson = p.optJSONArray("notes")
            if (notesJson != null) for (j in 0 until notesJson.length()) {
                decodeNote(notesJson.optJSONObject(j))?.let(notes::add)
            }
            val existing = pages[index] ?: PageAnnotations.EMPTY
            val merged = PageAnnotations(existing.strokes + strokes, existing.notes + notes)
            if (!merged.isEmpty) pages[index] = merged
        }

        return AnnotationFile(
            source = source,
            savedAt = root.optLong("savedAt", 0L),
            annotations = DocumentAnnotations(pages = pages, viewState = viewState),
        )
    }

    private fun encodeStroke(s: Stroke): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("tool", if (s.tool == InkTool.HIGHLIGHTER) "highlighter" else "pen")
        put("color", colorToHex(s.color))
        put("width", round2(s.width))
        put("flat", s.flatCaps)
        put("group", s.groupId ?: JSONObject.NULL)
        val pts = JSONArray()
        for (v in s.points) pts.put(round2(v))
        put("points", pts)
    }

    private fun decodeStroke(o: JSONObject?): Stroke? {
        if (o == null) return null
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val tool = when (o.optString("tool")) {
            "highlighter" -> InkTool.HIGHLIGHTER
            "pen" -> InkTool.PEN
            else -> return null
        }
        val color = parseColor(o.optString("color")) ?: return null
        val width = o.optDouble("width", Double.NaN)
        if (!width.isFinite() || width <= 0.0) return null
        val ptsJson = o.optJSONArray("points") ?: return null
        val n = ptsJson.length()
        if (n < 2 || n % 2 != 0) return null
        val pts = FloatArray(n)
        for (k in 0 until n) {
            val v = ptsJson.optDouble(k, Double.NaN)
            if (!v.isFinite()) return null
            pts[k] = v.toFloat()
        }
        val group = if (o.isNull("group")) null else o.optString("group").takeIf { it.isNotEmpty() }
        return Stroke(id, tool, color, width.toFloat(), pts, o.optBoolean("flat", false), group)
    }

    private fun encodeNote(n: Note): JSONObject = JSONObject().apply {
        put("id", n.id)
        put("x", round2(n.x))
        put("y", round2(n.y))
        put("text", n.text)
        put("color", colorToHex(n.color))
        put("created", n.createdAt)
        put("modified", n.modifiedAt)
    }

    private fun decodeNote(o: JSONObject?): Note? {
        if (o == null) return null
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val x = o.optDouble("x", Double.NaN)
        val y = o.optDouble("y", Double.NaN)
        if (!x.isFinite() || !y.isFinite()) return null
        return Note(
            id = id,
            x = x.toFloat(),
            y = y.toFloat(),
            text = o.optString("text", ""),
            color = parseColor(o.optString("color")) ?: DEFAULT_NOTE_COLOR,
            createdAt = o.optLong("created", 0L),
            modifiedAt = o.optLong("modified", 0L),
        )
    }

    fun colorToHex(color: Int): String = "#%08X".format(color)

    fun parseColor(hex: String?): Int? {
        if (hex == null || !hex.startsWith("#")) return null
        val body = hex.substring(1)
        return when (body.length) {
            8 -> body.toLongOrNull(16)?.toInt()
            6 -> body.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            else -> null
        }
    }

    private const val DEFAULT_NOTE_COLOR = 0xFFFFF3A6.toInt()

    private fun round2(v: Float): Double = (v * 100.0).roundToLong() / 100.0
    private fun round4(v: Float): Double = (v * 10000.0).roundToLong() / 10000.0
    private fun finiteOr(v: Double, fallback: Double) = if (v.isFinite()) v else fallback
}
