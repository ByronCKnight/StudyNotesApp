package com.studynotes.app.docx

import org.json.JSONException
import org.json.JSONObject

/**
 * Bookkeeping stored next to a DOCX rendition (`<fileName>.meta.json`): which version of the source
 * file the PDF was made from, and by which converter.
 */
internal data class RenditionMeta(
    val sourceSize: Long,
    val sourceLastModified: Long,
    val converterVersion: Int,
    val createdAt: Long,
    /**
     * Size and lastModified of the source version the *previous* rendition was made from, when this
     * rendition replaced it because the source changed. Null otherwise, or once acknowledged.
     */
    val replacedSourceSize: Long? = null,
    val replacedSourceLastModified: Long? = null,
) {
    fun matches(size: Long, lastModified: Long): Boolean = sourceSize == size && sourceLastModified == lastModified

    val replacedOtherSource: Boolean get() = replacedSourceSize != null

    fun encode(): String = JSONObject().apply {
        put(KEY_SOURCE_SIZE, sourceSize)
        put(KEY_SOURCE_LAST_MODIFIED, sourceLastModified)
        put(KEY_CONVERTER_VERSION, converterVersion)
        put(KEY_CREATED_AT, createdAt)
        replacedSourceSize?.let { put(KEY_REPLACED_SIZE, it) }
        replacedSourceLastModified?.let { put(KEY_REPLACED_LAST_MODIFIED, it) }
    }.toString()

    companion object {
        private const val KEY_SOURCE_SIZE = "sourceSize"
        private const val KEY_SOURCE_LAST_MODIFIED = "sourceLastModified"
        private const val KEY_CONVERTER_VERSION = "converterVersion"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_REPLACED_SIZE = "replacedSourceSize"
        private const val KEY_REPLACED_LAST_MODIFIED = "replacedSourceLastModified"

        /** Parses [json]; null when it is unreadable or lacks the source fields. */
        fun decode(json: String): RenditionMeta? = try {
            val o = JSONObject(json)
            if (!o.has(KEY_SOURCE_SIZE) || !o.has(KEY_SOURCE_LAST_MODIFIED)) {
                null
            } else {
                RenditionMeta(
                    sourceSize = o.getLong(KEY_SOURCE_SIZE),
                    sourceLastModified = o.getLong(KEY_SOURCE_LAST_MODIFIED),
                    converterVersion = o.optInt(KEY_CONVERTER_VERSION, 0),
                    createdAt = o.optLong(KEY_CREATED_AT, 0L),
                    replacedSourceSize = if (o.has(KEY_REPLACED_SIZE)) o.getLong(KEY_REPLACED_SIZE) else null,
                    replacedSourceLastModified = if (o.has(KEY_REPLACED_LAST_MODIFIED)) o.getLong(KEY_REPLACED_LAST_MODIFIED) else null,
                )
            }
        } catch (_: JSONException) {
            null
        }
    }
}
