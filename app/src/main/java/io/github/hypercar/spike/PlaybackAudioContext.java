package io.github.hypercar.spike;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;

/** AudioRecord is created in the shell UserService, not with the application's UID. */
public final class PlaybackAudioContext extends ContextWrapper {
    public PlaybackAudioContext(Context base) { super(base); }
    @Override public String getPackageName() { return "com.android.shell"; }
    @Override public String getOpPackageName() { return "com.android.shell"; }
    @Override public Context getApplicationContext() { return this; }
    @Override public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName("com.android.shell").build();
    }
    @Override public int getDeviceId() { return 0; }
}
