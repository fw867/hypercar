package io.github.hypercar.spike;

interface ICallAudioRoute {
    /** Recheck the current car route after the receiver has accepted telephony SETUP. */
    boolean mayStart();
}
