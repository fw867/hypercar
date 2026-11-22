package io.github.hypercar.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class DensoOutbackProbeTest {
    private val endpoint = AirplayServiceProbe.Endpoint("10.12.1.4", 8123, "carplay._airplay._tcp.local", null)

    @Test fun requiresBothIdentifiers() {
        assertTrue(DensoOutbackProbe.matches("outback", "DENSO"))
        assertFalse(DensoOutbackProbe.matches("Legacy", "DENSO"))
        assertFalse(DensoOutbackProbe.matches("Outback", null))
        assertFalse(DensoOutbackProbe.matches("Outback", "Other"))
    }

    @Test fun inviteDuringDiscoverySuppressesInfoRequest() = runTest {
        var invited = false
        val result = DensoOutbackProbe.probe({ !invited }, {
            invited = true
            endpoint
        }) { fail("invitation already won"); 500 }
        assertEquals("skipped", result)
    }

    @Test fun onlyTheFreshDiscoveredEndpointIsProbedOnce() = runTest {
        val requests = mutableListOf<Pair<String, Int>>()
        val result = DensoOutbackProbe.probe({ true }, { endpoint }) {
            requests += it.host to it.port
            455
        }
        assertEquals(listOf("10.12.1.4" to 8123), requests)
        assertTrue(result.contains("status=455"))
    }

    @Test fun noDiscoveryDoesNotProbeAnAddress() = runTest {
        assertEquals("mdns_not_found", DensoOutbackProbe.probe({ true }, { null }) {
            fail("must not guess the address"); 500
        })
    }

    @Test fun sendsOneRtspInfoRequestAndReportsReceiverStatus() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 2_000
            val server = async(Dispatchers.IO) {
                listener.accept().use { peer ->
                    peer.soTimeout = 2_000
                    val reader = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                    assertEquals("GET /info RTSP/1.0", reader.readLine())
                    val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    assertTrue("CSeq: 1" in headers)
                    assertTrue("Content-Length: 42" in headers)
                    assertEquals("bplist00", CharArray(8) { reader.read().toChar() }.concatToString())
                    repeat(34) { assertNotEquals(-1, reader.read()) }
                    peer.getOutputStream().write("RTSP/1.0 455 Method Not Valid In This State\r\n".toByteArray())
                }
            }
            val target = endpoint.copy(host = "127.0.0.1", port = listener.localPort)
            assertEquals(455, DensoOutbackProbe.requestInfo(target, ::Socket) { true })
            server.await()
        }
    }
}
