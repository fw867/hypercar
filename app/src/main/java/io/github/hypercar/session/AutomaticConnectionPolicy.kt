package io.github.hypercar.session

/** 自动模式只决定待机监听/重连，不限制手动会话。 */
object AutomaticConnectionPolicy {
    enum class IdleAction { KEEP_SESSION, START_LISTENING, STOP_LISTENING }
    fun modeChanged(enabled: Boolean, sessionActive: Boolean): IdleAction = when {
        sessionActive -> IdleAction.KEEP_SESSION
        enabled -> IdleAction.START_LISTENING
        else -> IdleAction.STOP_LISTENING
    }
    fun mayStart(manual: Boolean, autoConnect: Boolean): Boolean = manual || autoConnect
    fun keepListeningAfterStop(autoConnect: Boolean): Boolean = autoConnect
}
