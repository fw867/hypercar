package io.github.hypercar.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MdnsDiscoveryTest {
    private class SilentTransport : MdnsDiscovery.Transport {
        val sent = mutableListOf<ByteArray>()
        @Volatile var closed = false
        val receiving = CountDownLatch(1)
        val closeSignal = CountDownLatch(1)
        var blockUntilClosed = false
        override fun send(query: ByteArray) { sent += query }
        override fun receive(timeoutMillis: Int): MdnsDiscovery.Reply? {
            receiving.countDown()
            if (blockUntilClosed) closeSignal.await(10, TimeUnit.SECONDS) else Thread.sleep(timeoutMillis.toLong())
            return null
        }
        override fun close() { closed = true; closeSignal.countDown() }
    }

    @Test fun failingIpv4StillQueriesIpv6AndRetriesWithinBudget() = runBlocking {
        val ipv6 = SilentTransport()
        val browser = MdnsDiscovery { }
        val result = browser.browse(750, { family ->
            if (family == 4) error("IPv4 unavailable") else ipv6
        }) { true }
        assertNull(result)
        assertTrue(ipv6.sent.size >= 2)
        assertTrue(ipv6.closed)
    }

    @Test(timeout = 5000) fun cancellationClosesBothTransportsAndPropagatesCancellation() = runBlocking {
        val ipv4 = SilentTransport().apply { blockUntilClosed = true }; val ipv6 = SilentTransport()
        val task = async(Dispatchers.Default) {
            MdnsDiscovery { }.browse(30_000, { family -> if (family == 4) ipv4 else ipv6 }) { true }
        }
        assertTrue(ipv4.receiving.await(3, TimeUnit.SECONDS))
        task.cancel()
        assertTrue(ipv4.closed)
        assertTrue(ipv6.closed)
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(ipv4.closed)
        assertTrue(ipv6.closed)
    }
    private fun name(value: String) = name(value.split('.'))
    private fun name(labels: List<String>) = ByteArrayOutputStream().apply {
        labels.forEach { label ->
            val bytes = label.toByteArray()
            write(bytes.size)
            write(bytes)
        }
        write(0)
    }.toByteArray()
    private fun packet(owner: String, type: Int, body: ByteArray) = packet(owner.split('.'), type, body)
    private fun packet(owner: List<String>, type: Int, body: ByteArray) = ByteArrayOutputStream().apply {
        val out = DataOutputStream(this)
        out.writeShort(0); out.writeShort(0x8400); out.writeShort(0); out.writeShort(1)
        out.writeShort(0); out.writeShort(0); out.write(name(owner)); out.writeShort(type)
        out.writeShort(1); out.writeInt(120); out.writeShort(body.size); out.write(body)
    }.toByteArray()

    @Test fun ptrThenSrvThenAaaaAcrossFamiliesActivelyResolvesReceiver() = runBlocking {
        val instance = "BMW._airplay._tcp.local"
        var stage = 0
        val questions = mutableListOf<ByteArray>()
        fun queried(type: Byte) = questions.any { it.toList().windowed(4).any { bytes -> bytes == listOf<Byte>(0,type,0,1) } }
        val ipv4 = object : MdnsDiscovery.Transport {
            override fun send(query: ByteArray) { questions += query }
            override fun receive(timeoutMillis: Int): MdnsDiscovery.Reply? = when {
                stage == 0 -> { stage = 1; MdnsDiscovery.Reply(packet("_airplay._tcp.local",12,name(instance))) }
                stage == 2 && queried(28) -> { stage = 3; MdnsDiscovery.Reply(packet("car.local",28,
                    byteArrayOf(0x20,1,0xd.toByte(),0xb8.toByte(),0,0,0,0,0,0,0,0,0,0,0,1))) }
                else -> null
            }
            override fun close() { }
        }
        val ipv6 = object : MdnsDiscovery.Transport {
            override fun send(query: ByteArray) { }
            override fun receive(timeoutMillis: Int): MdnsDiscovery.Reply? = if (stage == 1 && queried(33)) {
                stage = 2
                MdnsDiscovery.Reply(packet(instance,33,byteArrayOf(0,0,0,0,0x1b,0x58) + name("car.local")))
            } else null
            override fun close() { }
        }
        val result = MdnsDiscovery { }.browse(1500, { if (it == 4) ipv4 else ipv6 }) { true }
        assertEquals("2001:db8:0:0:0:0:0:1", result?.host)
        assertEquals("car.local", result?.targetHost)
        // The second round is triggered by PTR, and the third by SRV, without waiting for retries.
        assertTrue(questions.size >= 3)
        assertTrue(questions.any { it.toList().windowed(4).any { bytes -> bytes == listOf<Byte>(0,33,0,1) } })
        assertTrue(questions.any { it.toList().windowed(4).any { bytes -> bytes == listOf<Byte>(0,28,0,1) } })
    }

    @Test fun splitRepliesResolveAnInstanceWithLiteralDotsAndUtf8() = runBlocking {
        val service = listOf("_airplay", "_tcp", "local")
        val instance = listOf("BMW.Ä\\导航") + service
        val queryName = name(listOf("bmw.Ä\\导航") + service) + byteArrayOf(0,33,0,1)
        val replies = ArrayDeque<MdnsDiscovery.Reply>()
        replies.add(MdnsDiscovery.Reply(packet(service, 12, name(instance))))
        var repliedToResolve = false
        val transport = object : MdnsDiscovery.Transport {
            override fun send(query: ByteArray) {
                if (!repliedToResolve && query.toList().windowed(queryName.size).any { it == queryName.toList() }) {
                    repliedToResolve = true
                    replies.add(MdnsDiscovery.Reply(packet(instance, 33,
                        byteArrayOf(0,0,0,0,0x1b,0x58) + name("car.local"))))
                    replies.add(MdnsDiscovery.Reply(packet("car.local", 1, byteArrayOf(10,0,0,2))))
                }
            }
            override fun receive(timeoutMillis: Int): MdnsDiscovery.Reply? =
                replies.removeFirstOrNull() ?: run { Thread.sleep(timeoutMillis.toLong()); null }
            override fun close() { }
        }
        val result = MdnsDiscovery { }.browse(1000, { family ->
            if (family == 4) transport else error("IPv6 unavailable")
        }) { true }
        assertEquals("10.0.0.2", result?.host)
        assertEquals(7000, result?.port)
    }

    @Test fun firstUsableIpv4DoesNotLoseLaterSplitIpv6Address() = runBlocking {
        var stage = 0
        val instance = "BMW._airplay._tcp.local"
        val ipv4 = object : MdnsDiscovery.Transport {
            override fun send(query: ByteArray) { }
            override fun receive(timeoutMillis: Int): MdnsDiscovery.Reply? = when (stage) {
                0 -> { stage = 1; MdnsDiscovery.Reply(packet(instance,33,
                    byteArrayOf(0,0,0,0,0x1b,0x58) + name("car.local"))) }
                1 -> { stage = 2; MdnsDiscovery.Reply(packet("car.local",1,byteArrayOf(10,0,0,2))) }
                else -> { Thread.sleep(timeoutMillis.toLong()); null }
            }
            override fun close() { }
        }
        val ipv6 = object : MdnsDiscovery.Transport {
            override fun send(query: ByteArray) { }
            override fun receive(timeoutMillis: Int): MdnsDiscovery.Reply? = if (stage == 2) {
                stage = 3
                MdnsDiscovery.Reply(packet("car.local",28,
                    byteArrayOf(0x20,1,0xd.toByte(),0xb8.toByte(),0,0,0,0,0,0,0,0,0,0,0,1)))
            } else null
            override fun close() { }
        }
        val result = MdnsDiscovery { }.browse(1500, { if (it == 4) ipv4 else ipv6 }) { true }
        assertEquals(listOf("10.0.0.2", "2001:db8:0:0:0:0:0:1"), result?.addresses)
    }
}
