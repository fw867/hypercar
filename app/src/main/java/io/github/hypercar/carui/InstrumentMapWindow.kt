package io.github.hypercar.carui

private val displayHeader = Regex("""Display: mDisplayId=(\d+)\b""")
private val mapFocus = Regex("""\bmCurrentFocus=Window\{([0-9a-fA-F]+) u(\d+) com\.autonavi\.minimap/com\.autonavi\.map\.activity\.UcarMapActivity\}""")
private val imeVisibility = Regex("""\btype=ime\b.*?\bvisible=(true|false)\b""")

internal data class InstrumentMapWindow(val token: String, val userId: Int)

internal fun instrumentMapWindow(lines: Sequence<String>, displayId: Int): InstrumentMapWindow? {
    var display: Int? = null
    var map: InstrumentMapWindow? = null
    var hidden = false
    var visible = false
    fun fields(line: String) {
        if (display == displayId) {
            if (line.contains("mCurrentFocus=")) map = mapFocus.find(line)?.let { match ->
                match.groupValues[2].toIntOrNull()?.let { InstrumentMapWindow(match.groupValues[1], it) }
            }
            if (line.trim() == "mImeWindow=null") hidden = true
            when (imeVisibility.find(line)?.groupValues?.get(1)) {
                "true" -> visible = true
                "false" -> hidden = true
            }
        }
    }
    for (line in lines) {
        if ("DUMP TIMEOUT" in line) return null
        val header = displayHeader.find(line)
        if (header == null) fields(line) else {
            // Xiaomi joins the previous display's last mImeWindow and the next header on one line.
            fields(line.substring(0, header.range.first))
            display = header.groupValues[1].toIntOrNull()
            fields(line.substring(header.range.last + 1))
        }
    }
    return map.takeIf { hidden && !visible }
}

/** The reader must use the requested UID as a compositor layer filter. */
internal fun <T, R> readInstrumentMap(
    uid: Int, userId: Int, window: () -> InstrumentMapWindow?, captureUid: (Int) -> T?,
    publish: (T) -> R?, release: (T) -> Unit,
): R? {
    val before = window() ?: return null
    if (uid < 0 || before.userId != userId) return null
    val frame = captureUid(uid) ?: return null
    return try { if (window() == before) publish(frame) else null } finally { release(frame) }
}
