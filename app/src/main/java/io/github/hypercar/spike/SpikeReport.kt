package io.github.hypercar.spike

import io.github.hypercar.session.HyperCarRepo

/** Production encoder/shell diagnostics use the repository's single log buffer. */
object SpikeReport {
    fun add(msg: String) = HyperCarRepo.log("SPIKE: $msg")
    fun add(tag: String, msg: String) = add("[$tag] $msg")

    fun stats(values: LongArray): String {
        if (values.isEmpty()) return "n=0"
        val sorted = values.sortedArray()
        fun p(q: Double): Long =
            sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
        return "n=${sorted.size} min=${sorted.first()} p50=${p(0.5)} p90=${p(0.9)} max=${sorted.last()}"
    }

}
