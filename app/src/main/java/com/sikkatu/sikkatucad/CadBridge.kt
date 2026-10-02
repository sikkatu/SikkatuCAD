package com.sikkatu.sikkatucad

import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI bridge to the Rust `cadbridge` library (acadrust-based).
 *
 * All native calls return a status string:
 *  - cbOpen         -> "<session_id>" (positive integer as string) or "ERR:..."
 *  - cbSourceFormat -> "dwg" | "dxf" or "ERR:..."
 *  - cbExtractTexts -> JSON array [{"id","type","text","layer"}] or "ERR:..."
 *  - cbApplyTexts   -> "OK:<applied_count>" or "ERR:..."
 *  - cbSaveDxf/Dwg  -> "OK" or "ERR:..."
 *  - cbProbeDump    -> JSON dump (debug) or "ERR:..."
 */
object CadBridge {

    data class TextItem(
        val id: String,
        val type: String,
        val text: String,
        val layer: String
    )

    init {
        System.loadLibrary("cadbridge")
    }

    private external fun cbOpen(path: String): String
    private external fun cbClose(sessionId: Long)
    private external fun cbSourceFormat(sessionId: Long): String
    private external fun cbExtractTexts(sessionId: Long): String
    private external fun cbApplyTexts(sessionId: Long, json: String): String
    private external fun cbSaveDxf(sessionId: Long, path: String): String
    private external fun cbSaveDwg(sessionId: Long, path: String): String
    private external fun cbProbeDump(sessionId: Long): String

    fun open(path: String): Result<Long> = runCatching {
        val r = cbOpen(path)
        if (r.startsWith("ERR:")) error(r.removePrefix("ERR:"))
        r.trim().toLongOrNull() ?: error("bad session id: $r")
    }

    fun close(sessionId: Long) = runCatching { cbClose(sessionId) }

    fun sourceFormat(sessionId: Long): Result<String> = runCatching {
        val r = cbSourceFormat(sessionId)
        if (r.startsWith("ERR:")) error(r.removePrefix("ERR:"))
        r.trim()
    }

    fun extractTexts(sessionId: Long): Result<List<TextItem>> = runCatching {
        val r = cbExtractTexts(sessionId)
        if (r.startsWith("ERR:")) error(r.removePrefix("ERR:"))
        val arr = JSONArray(r)
        buildList {
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.getJSONObject(i)
                add(
                    TextItem(
                        id = o.optString("id"),
                        type = o.optString("type"),
                        text = o.optString("text"),
                        layer = o.optString("layer")
                    )
                )
            }
        }
    }

    /** [edits] maps entity id -> replacement text. Returns applied count. */
    fun applyTexts(sessionId: Long, edits: Map<String, String>): Result<Int> = runCatching {
        val arr = JSONArray()
        edits.forEach { (id, text) ->
            arr.put(JSONObject().put("id", id).put("text", text))
        }
        val r = cbApplyTexts(sessionId, arr.toString())
        if (r.startsWith("ERR:")) error(r.removePrefix("ERR:"))
        r.removePrefix("OK:").trim().toIntOrNull() ?: 0
    }

    fun saveDxf(sessionId: Long, path: String): Result<Unit> = runCatching {
        val r = cbSaveDxf(sessionId, path)
        if (r.startsWith("ERR:")) error(r.removePrefix("ERR:"))
    }

    fun saveDwg(sessionId: Long, path: String): Result<Unit> = runCatching {
        val r = cbSaveDwg(sessionId, path)
        if (r.startsWith("ERR:")) error(r.removePrefix("ERR:"))
    }

    fun probeDump(sessionId: Long): Result<String> = runCatching {
        val r = cbProbeDump(sessionId)
        if (r.startsWith("ERR:")) error(r.removePrefix("ERR:"))
        r
    }

    /** Parse a translation JSON file: [{"id","en","th",...}] -> (id -> en, id -> th). */
    fun parseTranslationJson(json: String): Pair<Map<String, String>, Map<String, String>> {
        val en = linkedMapOf<String, String>()
        val th = linkedMapOf<String, String>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue
            val e = o.optString("en")
            val t = o.optString("th")
            if (e.isNotBlank()) en[id] = e
            if (t.isNotBlank()) th[id] = t
        }
        return en to th
    }

    /** Serialize rows to a translation JSON file body. */
    fun buildTranslationJson(rows: List<TranslationRowJson>): String {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("type", r.type)
                    .put("text", r.text)
                    .put("layer", r.layer)
                    .put("en", r.en)
                    .put("th", r.th)
            )
        }
        return arr.toString(2)
    }

    data class TranslationRowJson(
        val id: String,
        val type: String,
        val text: String,
        val layer: String,
        val en: String,
        val th: String
    )
}