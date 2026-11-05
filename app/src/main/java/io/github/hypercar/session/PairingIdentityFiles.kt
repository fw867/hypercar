package io.github.hypercar.session

import java.io.File
import java.io.IOException

/** Unchanged cars keep the legacy shared identity; a reset car gets its own identity namespace. */
object PairingIdentityFiles {
    const val NAME = "controller_identity.txt"
    data class Location(val file: File, val backupKey: String?)

    fun location(filesDir: File, mac: String?): Location {
        val key = PairingEvidence.receiverKey(mac)
        val directory = key?.let { File(filesDir, "pairing/$it") }
        return if (directory?.isDirectory == true) Location(File(directory, NAME), key)
            else Location(File(filesDir, NAME), null)
    }

    fun resetAll(filesDir: File) {
        val rootFiles = filesDir.listFiles() ?: throw IOException("无法读取配对文件")
        for (file in rootFiles.filter { it.name == NAME || it.name.startsWith("$NAME.paired-") }) {
            if (!file.isFile || !file.delete()) throw IOException("无法清除配对身份文件")
        }
        val scoped = File(filesDir, "pairing")
        if (scoped.exists() && !scoped.deleteRecursively()) throw IOException("无法清除独立车机身份")
    }

    /** Retain the directory as the durable selector, even before Rust creates the new key. */
    fun resetReceiver(filesDir: File, mac: String) {
        val key = requireNotNull(PairingEvidence.receiverKey(mac)) { "无效的车机地址" }
        val directory = File(filesDir, "pairing/$key")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法建立车机身份目录")
        val files = directory.listFiles() ?: throw IOException("无法读取车机身份目录")
        for (file in files.filter { it.name == NAME || it.name.startsWith("$NAME.paired-") }) {
            if (!file.isFile || !file.delete()) throw IOException("无法清除该车机的身份文件")
        }
        // Only this MAC's old marker is attributable. Other legacy PK/IP markers belong to
        // the old shared identity and must remain untouched for other cars.
        val oldMarker = File(filesDir, "$NAME.paired-$key")
        if (oldMarker.exists() && (!oldMarker.isFile || !oldMarker.delete())) {
            throw IOException("无法清除该车机的旧配对标记")
        }
    }
}

/** Pure record filtering: SSIDs can contain '|', and several cars may share one SSID. */
object PairingResetRecords {
    fun pairedCars(rows: Set<String>, mac: String): Set<String> =
        rows.filterNot { it.substringBefore('|').equals(mac, ignoreCase = true) }.toSet()
    fun networks(rows: Set<String>, mac: String): Set<String> =
        rows.filterNot { it.substringAfterLast('|', "").equals(mac, ignoreCase = true) }.toSet()
}
