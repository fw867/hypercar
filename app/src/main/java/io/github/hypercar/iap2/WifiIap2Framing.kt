package io.github.hypercar.iap2

/** Preserve the box transport's one link frame per iAPSendMessage command. */
internal object WifiIap2Framing {
    fun split(bytes: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            require(bytes.size-offset >= 4 && bytes[offset] == 0xff.toByte()) { "invalid Wi-Fi iAP2 output header" }
            val length = when (bytes[offset+1].toInt() and 255) {
                0x55 -> 6.also { require(bytes.copyOfRange(offset, minOf(offset+6,bytes.size)).contentEquals(byteArrayOf(0xff.toByte(),0x55,2,0,0xee.toByte(),0x10))) { "invalid DETECT output" } }
                0x5a -> (((bytes[offset+2].toInt() and 255) shl 8) or (bytes[offset+3].toInt() and 255)).also { require(it >= 9) { "invalid link length" } }
                else -> error("unknown Wi-Fi iAP2 output frame")
            }
            require(offset+length <= bytes.size) { "incomplete Wi-Fi iAP2 output frame" }
            frames += bytes.copyOfRange(offset,offset+length)
            offset += length
        }
        return frames
    }
}
