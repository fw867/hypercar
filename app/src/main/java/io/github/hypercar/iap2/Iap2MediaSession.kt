package io.github.hypercar.iap2

/** One authenticated CSM transport owns its components, descriptors and key edges. */
internal class Iap2MediaSession(private val log: (String) -> Unit, private val key: (Int) -> Unit) {
    private val components = linkedSetOf<Int>()
    private val devices = mutableMapOf<Int, ConsumerReport>()
    private val deferred = ArrayDeque<Iap2Csm.Frame>()
    private var identified = false
    private var ready = false
    private var diagnostics = 0
    val registeredCount: Int get() = devices.size

    fun identify(hids: List<Iap2Messages.HidComponent>) {
        val next = hids.filter { it.function == 1 }.take(32).map { it.identifier }.toSet()
        if (identified && components != next) { devices.clear(); deferred.clear() }
        identified = true
        components.clear()
        components.addAll(next)
        note("declared media components=$components; AirPlay HID is independent")
    }
    fun authenticated() {
        ready = true
        val queued = deferred.toList(); deferred.clear()
        queued.forEach(::handle)
    }
    /** Link-only resync keeps the business auth context; reports require a new StartHID. */
    fun restartLink() { devices.clear(); deferred.clear() }
    fun reset() { restartLink(); components.clear(); identified = false; ready = false }

    fun handle(frame: Iap2Csm.Frame): Boolean {
        if (frame.messageId !in 0x6800..0x6803 || frame.messageId == 0x6801) return false
        val params = Iap2Csm.parseParams(frame.body)
        if (params.sumOf { it.value.size + 4 } != frame.body.size || params.count { it.id == 0 } != 1 ||
            params.first { it.id == 0 }.value.size != 2) {
            note("invalid id=0x${frame.messageId.toString(16)} bytes=${frame.body.size}"); return true
        }
        val component = params.first { it.id == 0 }.value.let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
        if (identified && component !in components) return false // Do not interpret keyboards/touch as media.
        if (!ready || !identified) {
            if (deferred.size < 64 && frame.body.size <= 8192) deferred += frame
            else note("deferred overflow/drop component=$component")
            return true
        }
        when (frame.messageId) {
            0x6800 -> {
                val descriptor = params.singleOrNull { it.id == 4 }?.value
                val parsed = descriptor?.let(ConsumerReport::parse)
                if (parsed == null) { devices.remove(component); note("StartHID component=$component invalid/unsupported descriptor"); return true }
                if (devices[component]?.descriptor?.contentEquals(descriptor) != true) devices[component] = parsed
                note("StartHID component=$component descriptor=${descriptor.size}B registered; no fabricated DeviceHIDReport")
            }
            0x6802 -> {
                val report = params.singleOrNull { it.id == 1 }?.value
                val device = devices[component]
                if (device == null || report == null) { note("report component=$component missing registration/report parameter1"); return true }
                val edges = device.report(report)
                note("report component=$component bytes=${report.size} edges=${edges.map { "0x${it.toString(16)}" }}")
                edges.forEach { usage -> runCatching { key(usage) }.onFailure { note("dispatch failed component=$component error=${it.javaClass.simpleName}") } }
            }
            0x6803 -> { devices.remove(component); note("StopHID component=$component") }
        }
        return true
    }
    private fun note(text: String) { if (diagnostics++ < 96) log("IAP2-MEDIA: $text") }
}

/** Strict descriptor-driven Consumer decoder. No direct-usage/04 guess for iAP2. */
internal class ConsumerReport private constructor(val descriptor: ByteArray) {
    private data class Globals(var page: Int = 0, var size: Int = 0, var count: Int = 0, var id: Int = 0, var min: Long = 0)
    private data class Field(val id: Int, val offset: Int, val size: Int, val count: Int, val variable: Boolean, val min: Long, val usages: List<Long>)
    private val fields = mutableListOf<Field>()
    private val pressed = mutableMapOf<Int, Set<Int>>()
    private val lengths = mutableMapOf<Int, Int>()
    private var numbered = false
    fun report(bytes: ByteArray): List<Int> {
        if (bytes.size > 4097) return emptyList()
        val id = if (numbered) bytes.firstOrNull()?.toInt()?.and(255) ?: return emptyList() else 0
        val shift = if (numbered) 1 else 0
        val bits = (bytes.size - shift) * 8
        val required = lengths[id] ?: return emptyList()
        if (bits < required) return emptyList()
        val selected = fields.filter { it.id == id }
        if (selected.isEmpty()) return emptyList()
        val down = linkedSetOf<Int>()
        for (f in selected) for (i in 0 until f.count) {
            var value = 0L
            for (b in 0 until f.size) {
                val bit = f.offset + i * f.size + b
                value = value or (((bytes[shift + bit / 8].toInt() ushr (bit % 8)) and 1).toLong() shl b)
            }
            val usage = if (f.variable) {
                if (value == 0L) continue
                f.usages.getOrNull(i) ?: f.usages.lastOrNull()
            } else f.usages.getOrNull((value - f.min).takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: continue)
            if (usage != null && usage ushr 16 == 0x0cL && (usage.toInt() and 0xffff) in supported) down += usage.toInt() and 0xffff
        }
        val edges = down - pressed.getOrDefault(id, emptySet())
        pressed[id] = down
        return edges.toList()
    }
    companion object {
        private val supported = setOf(0xb0, 0xb1, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb9, 0xcd)
        fun parse(bytes: ByteArray): ConsumerReport? = runCatching {
            require(bytes.isNotEmpty() && bytes.size <= 4096)
            val result = ConsumerReport(bytes.copyOf())
            var g = Globals(); val stack = ArrayDeque<Globals>()
            val usages = mutableListOf<Long>(); var range: Long? = null
            var at = 0
            while (at < bytes.size) {
                val prefix = bytes[at++].toInt() and 255
                if (prefix == 254) {
                    require(at + 2 <= bytes.size)
                    val size = bytes[at].toInt() and 255
                    at += 2 + size; require(at <= bytes.size); continue
                }
                val size = if (prefix and 3 == 3) 4 else prefix and 3
                require(at + size <= bytes.size)
                var value = 0L
                repeat(size) { value = value or ((bytes[at++].toLong() and 255) shl (it * 8)) }
                val type = (prefix ushr 2) and 3; val tag = prefix ushr 4
                fun usage(v: Long) = if (size == 4) v else (g.page.toLong() shl 16) or v
                when (type to tag) {
                    1 to 0 -> { require(value <= 65535); g.page = value.toInt() }
                    1 to 1 -> g.min = when (size) { 1 -> value.toByte().toLong(); 2 -> value.toShort().toLong(); else -> value.toInt().toLong() }
                    1 to 7 -> { require(value in 1..32); g.size = value.toInt() }
                    1 to 8 -> { require(value in 1..255); g.id = value.toInt(); result.numbered = true }
                    1 to 9 -> { require(value <= 256); g.count = value.toInt() }
                    1 to 10 -> { require(stack.size < 16); stack += g.copy() }
                    1 to 11 -> { require(stack.isNotEmpty()); g = stack.removeLast() }
                    2 to 0 -> { require(usages.size < 512); usages += usage(value) }
                    2 to 1 -> range = usage(value)
                    2 to 2 -> {
                        val first = requireNotNull(range); val last = usage(value)
                        require(last >= first && last - first <= 511 && usages.size + last - first + 1 <= 512)
                        usages.addAll(first..last); range = null
                    }
                    0 to 8 -> {
                        require(g.size in 1..32 && g.count in 1..256)
                        val offset = result.lengths[g.id] ?: 0
                        val end = offset + g.size * g.count
                        require(end <= 32768); result.lengths[g.id] = end
                        if (value and 1 == 0L && usages.any { it ushr 16 == 0x0cL }) {
                            require(result.fields.size < 64)
                            result.fields += Field(g.id, offset, g.size, g.count, value and 2 != 0L, g.min, usages.toList())
                        }
                    }
                }
                if (type == 0) { usages.clear(); range = null }
            }
            require(result.fields.isNotEmpty())
            result
        }.getOrNull()
    }
}
