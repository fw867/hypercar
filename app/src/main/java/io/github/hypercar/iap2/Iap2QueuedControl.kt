package io.github.hypercar.iap2

/** An optional complete CSM and the condition for assigning its first link sequence. */
internal class Iap2QueuedControl(
    val frame: Iap2Csm.Frame,
    val canStart: () -> Boolean,
    val commitStart: (() -> Boolean)? = null,
    val onWritten: (() -> Unit)? = null,
    val onDiscarded: (() -> Unit)? = null,
    val authorizeFirstWrite: ((cancel: () -> Unit) -> Boolean)? = null,
    val liveSourceAllowsFirstWrite: (() -> Boolean)? = null,
)
