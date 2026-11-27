package io.github.hypercar.session

import io.github.hypercar.iap2.RfcommClose

/** Manual retry may restart an invite-wait round; ACL flaps only rediscover, never pause. */
internal object WaitingInvitationRetryPolicy {
    fun shouldSoftRestart(
        manual: Boolean,
        awaitingInvitation: Boolean,
        sessionActive: Boolean,
        streaming: Boolean,
    ): Boolean = manual && awaitingInvitation && sessionActive && !streaming

    /** Captured at the tap. Commit under the session-control mutex, before waiting for old jobs. */
    class Request(val attempt: Long, private val captured: RfcommClose?) {
        fun mayCommit(current: Long, awaiting: Boolean, active: Boolean, streaming: Boolean,
                      hasValidInvitation: Boolean): Boolean =
            attempt == current && !hasValidInvitation && shouldSoftRestart(true, awaiting, active, streaming)

        fun markLocalStop(): RfcommClose? {
            captured?.noteLocalStop()
            return captured
        }
    }
}
