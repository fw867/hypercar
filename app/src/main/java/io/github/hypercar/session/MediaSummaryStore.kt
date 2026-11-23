package io.github.hypercar.session

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A small, atomic journal independent of the diagnostic log recording switch. */
internal class MediaSummaryStore(private val file: File) {
    @Synchronized fun save(rows: Map<String, String>) {
        if (rows.isEmpty()) return
        val updated = read()
        updated.putAll(rows)
        val retained = updated.entries.toList().takeLast(MAX_ROWS)
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { stream ->
            val bytes = retained.joinToString("\n", postfix = "\n") { (key, summary) ->
                "$key\t${summary.take(MAX_SUMMARY_CHARS)}"
            }.encodeToByteArray()
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun previous(live: Map<String, String>): List<String> =
        read().filterKeys { it !in live }.values.toList()

    private fun read(): LinkedHashMap<String, String> {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return linkedMapOf()
        return file.useLines { lines ->
            lines.take(MAX_ROWS).mapNotNull { line ->
                val split = line.indexOf('\t')
                if (split in 1..128) line.substring(0, split) to line.substring(split + 1) else null
            }.toMap(linkedMapOf())
        }
    }

    private companion object {
        const val MAX_ROWS = 8
        const val MAX_SUMMARY_CHARS = 16_384
        const val MAX_FILE_BYTES = MAX_ROWS * (MAX_SUMMARY_CHARS * 3L + 130)
    }
}
