package io.github.hypercar.navigation

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder

/** The Link 'datas' transport can add quoting/URI escaping around the business JSON. */
internal object LinkJson {
    fun objectValue(raw: String): JSONObject? = runCatching {
        require(raw.length <= 131072)
        var text = raw.trim()
        repeat(4) {
            if (text.startsWith("%7B", true) || text.startsWith("%22", true) || text.startsWith("%5B", true)) {
                text = URLDecoder.decode(text, "UTF-8")
            }
            when (val value = JSONTokener(text).nextValue()) {
                is JSONObject -> return@runCatching value
                is String -> { require(value != text); text = value.trim() }
                else -> return@runCatching null
            }
        }
        null
    }.getOrNull()

    fun encoding(raw: String): String = when {
        raw.startsWith("%") -> "percent"
        raw.startsWith("[") -> "array"
        raw.startsWith("\"") -> "quoted"
        raw.startsWith("{") -> "object"
        raw.any { it.code < 32 && !it.isWhitespace() } -> "binary-string"
        raw.length % 4 != 1 && raw.matches(Regex("[A-Za-z0-9+/=_-]+")) -> "base64-like"
        else -> "other"
    }
}
