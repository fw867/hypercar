package io.github.hypercar.session

/** Manual retry may restart an invite-wait round; ACL flaps only rediscover, never pause. */
internal object WaitingInvitationRetryPolicy {
    fun shouldSoftRestart(
        manual: Boolean,
        awaitingInvitation: Boolean,
        sessionActive: Boolean,
        streaming: Boolean,
    ): Boolean = manual && awaitingInvitation && sessionActive && !streaming
}
