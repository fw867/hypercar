package io.github.hypercar.yadea

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class YadeaTransportTest {
    @Test
    fun projectsOverLoopbackAndReleasesSocketsForTheNextSession() {
        val host = InetAddress.getByName("127.0.0.1")
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, host).use { tcp ->
                DatagramSocket(InetSocketAddress(host, 0)).use { udp ->
                    tcp.soTimeout = 3000
                    udp.soTimeout = 3000
                    repeat(2) {
                        val peer = worker.submit {
                            tcp.accept().use { client ->
                                client.soTimeout = 3000
                                val reader = YadeaWire.ReplyReader()
                                fun readCommand(): Int {
                                    while (true) {
                                        reader.poll()?.let { return it.command }
                                        reader.readFrom(client.getInputStream())
                                    }
                                }
                                assertEquals(0x1F, readCommand())
                                assertEquals(0x10, readCommand())
                                client.getOutputStream().write(START_REPLY)
                                assertEquals(0x13, readCommand())
                                client.getOutputStream().write(END_REPLY)
                            }
                        }
                        YadeaTransport().use { transport ->
                            val display = transport.connect(host.hostAddress!!, tcp.localPort, udp.localPort)
                            assertEquals(YadeaDisplay(1024, 600, 25, true, 0, 0xF6), display)
                            val image = ByteArray(1500) { it.toByte() }
                            transport.sendJpeg(image, 100)
                            val received = List(2) {
                                val packet = DatagramPacket(ByteArray(1500), 1500)
                                udp.receive(packet)
                                assertEquals(0x12.toByte(), packet.data[7])
                                packet.data.copyOfRange(32, packet.length - 3)
                            }
                            assertArrayEquals(image, received[0] + received[1])
                            transport.finish()
                        }
                        peer.get(3, TimeUnit.SECONDS)
                    }
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }

    companion object {
        private val START_REPLY = hex("59 44 4C 00 00 00 10 20 00 00 00 08 04 00 02 58 00 00 F6 19 F1 4B 4A")
        private val END_REPLY = hex("59 44 4C 00 00 00 08 23 00 00 00 00 77 4B 4A")
        private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    }
}
