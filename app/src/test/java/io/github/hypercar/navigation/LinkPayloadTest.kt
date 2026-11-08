package io.github.hypercar.navigation

import java.io.ByteArrayInputStream
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinkPayloadTest {
    private fun begin(size: Int, count: Int = 2) = """{"type":"begin_transfer","transferId":"test","totalSize":$size,"totalChunks":$count}"""
    private fun chunk(index: Int, text: String) = JSONObject().put("type", "chunk_data").put("transferId", "test")
        .put("chunkIndex", index).put("data", Base64.getEncoder().encodeToString(text.toByteArray())).toString()
    private val end = """{"type":"end_transfer","transferId":"test","status":"success"}"""
    @Test fun fdPayloadIsNotTruncatedAtTheOld64KiBLimit() {
        val data = "x".repeat(70000)
        assertEquals(data, LinkPayload.read(ByteArrayInputStream(data.toByteArray())))
    }
    @Test fun fdMemoryUseIsBounded() {
        assertThrows(IllegalStateException::class.java) {
            LinkPayload.read(ByteArrayInputStream(ByteArray(LinkPayload.MAX_BYTES + 1)))
        }
    }
    @Test fun onlyCompleteTransferIsDeliveredAndOrderDoesNotChangePayload() {
        val p = LinkPayload()
        assertNull(p.accept(begin(4), 0))
        assertNull(p.accept(chunk(1, "cd"), 1))
        assertNull(p.accept(chunk(0, "ab"), 2))
        assertEquals("abcd", p.accept(end, 3))
        assertNull(p.accept(end, 4))
    }
    @Test fun incompleteExpiredConflictingAndOversizedTransfersNeverReachTheParser() {
        val p = LinkPayload()
        p.accept(begin(4), 0); p.accept(chunk(0, "ab"), 1)
        assertNull(p.accept(end, 2))
        p.accept(begin(4), 0); p.accept(chunk(0, "ab"), 1); p.accept(chunk(1, "cd"), 10001)
        assertNull(p.accept(end, 10002))
        p.accept(begin(4), 0); p.accept(chunk(0, "ab"), 1); p.accept(chunk(0, "xy"), 2)
        p.accept(chunk(1, "cd"), 3); assertNull(p.accept(end, 4))
        assertNull(p.accept(begin(LinkPayload.MAX_BYTES + 1), 0))
    }
}
