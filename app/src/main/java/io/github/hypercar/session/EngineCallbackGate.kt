package io.github.hypercar.session

/** Engine starts within one connection attempt need distinct callback owners. */
internal class EngineCallbackGate {
    private var serial = 0L
    private var owner = 0L
    @Synchronized fun begin(): Long = (++serial).also { owner = it }
    @Synchronized fun current(): Long = owner
    @Synchronized fun retire() { owner = 0 }
    @Synchronized fun isCurrent(expected: Long): Boolean = expected != 0L && owner == expected
    @Synchronized fun deliver(expected: Long, action: () -> Unit): Boolean {
        if (!isCurrent(expected)) return false
        action()
        return true
    }
}
