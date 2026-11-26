package io.github.hypercar.spike;

import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Shell-only adapter for the system API behind TelephonyCallback.PreciseCallStateListener. */
@SuppressLint("BlockedPrivateApi")
final class ShellTelephonyRegistry {
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String TELEPHONY = "com.android.internal.telephony.";

    private final IBinder binder;
    private final Object registry;
    private final IBinder subscriptionBinder;
    private final Object subscriptionService;
    private final Method getActiveSubscriptions;
    private final Method addSubscriptionsListener;
    private final Method listen;
    private final CallbackType subscriptionsCallback;
    private final CallbackType preciseCallback;
    private final CallbackType disconnectCallback;
    private final int preciseEvent;
    private final int disconnectEvent;
    private final Parcelable.Creator<?> preciseCreator;
    private final Method getRinging;
    private final Method getForeground;
    private final Method getBackground;

    interface PreciseStateListener {
        void onState(int ringing, int foreground, int background);
    }

    ShellTelephonyRegistry() throws Exception {
        Method getService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
        binder = Objects.requireNonNull((IBinder) getService.invoke(null, "telephony.registry"));
        Class<?> registryType = Class.forName(TELEPHONY + "ITelephonyRegistry");
        registry = Class.forName(registryType.getName() + "$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        // app_process has no TelephonyFrameworkInitializer service manager. Query the same
        // permission-checked subscription service directly and request only active IDs.
        subscriptionBinder = Objects.requireNonNull((IBinder) getService.invoke(null, "isub"));
        Class<?> subscriptionType = Class.forName(TELEPHONY + "ISub");
        subscriptionService = Class.forName(subscriptionType.getName() + "$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, subscriptionBinder);
        getActiveSubscriptions = subscriptionType.getMethod("getActiveSubIdList", boolean.class);
        subscriptionsCallback = new CallbackType("IOnSubscriptionsChangedListener", "onSubscriptionsChanged");
        preciseCallback = new CallbackType("IPhoneStateListener", "onPreciseCallStateChanged");
        disconnectCallback = new CallbackType("IPhoneStateListener", "onCallDisconnectCauseChanged");
        addSubscriptionsListener = registryType.getMethod("addOnSubscriptionsChangedListener",
                String.class, String.class, subscriptionsCallback.type);
        Method listenWithEvents;
        try {
            listenWithEvents = registryType.getMethod("listenWithEventList", boolean.class, boolean.class,
                    int.class, String.class, String.class, preciseCallback.type, int[].class, boolean.class);
        } catch (NoSuchMethodException oldRegistry) {
            listenWithEvents = registryType.getMethod("listenWithEventList",
                    int.class, String.class, String.class, preciseCallback.type, int[].class, boolean.class);
        }
        listen = listenWithEvents;
        preciseEvent = TelephonyCallback.class.getField("EVENT_PRECISE_CALL_STATE_CHANGED").getInt(null);
        disconnectEvent = TelephonyCallback.class.getField("EVENT_CALL_DISCONNECT_CAUSE_CHANGED").getInt(null);
        Class<?> precise = Class.forName("android.telephony.PreciseCallState");
        preciseCreator = (Parcelable.Creator<?>) precise.getField("CREATOR").get(null);
        getRinging = precise.getMethod("getRingingCallState");
        getForeground = precise.getMethod("getForegroundCallState");
        getBackground = precise.getMethod("getBackgroundCallState");
    }

    Set<Integer> activeSubscriptions() throws Exception {
        Set<Integer> active = new LinkedHashSet<>();
        int[] subscriptions = (int[]) getActiveSubscriptions.invoke(subscriptionService, false);
        for (int subscription : subscriptions) {
            if (!SubscriptionManager.isValidSubscriptionId(subscription)) {
                throw new IllegalStateException("invalid active subscription");
            }
            active.add(subscription);
        }
        return active;
    }

    void linkToDeath(IBinder.DeathRecipient death) throws RemoteException {
        binder.linkToDeath(death, 0);
        subscriptionBinder.linkToDeath(death, 0);
    }

    Object watchSubscriptions(Runnable changed) throws Exception {
        Object callback = subscriptionsCallback.create(data -> changed.run());
        addSubscriptionsListener.invoke(registry, SHELL_PACKAGE, null, callback);
        return callback;
    }

    AutoCloseable watchPreciseState(int subscription, PreciseStateListener state, Runnable disconnected) throws Exception {
        Object disconnectListener = disconnectCallback.create(data -> {
            try {
                int cause = data.readInt();
                int preciseCause = data.readInt();
                if (cause != -1 || preciseCause != -1 || data.dataAvail() != 0) disconnected.run();
            } catch (RuntimeException malformed) {
                disconnected.run();
            }
        });
        Object callback = preciseCallback.create(data -> {
            int ringing = -1;
            int foreground = -1;
            int background = -1;
            try {
                Object precise = Objects.requireNonNull(data.readTypedObject(preciseCreator));
                if (data.dataAvail() != 0) throw new IllegalArgumentException("unexpected call state data");
                ringing = (Integer) getRinging.invoke(precise);
                foreground = (Integer) getForeground.invoke(precise);
                background = (Integer) getBackground.invoke(precise);
            } catch (Exception | LinkageError failure) {
                Log.w("HyperCar", "CALL-AUDIO: precise state unreadable " + failure.getClass().getSimpleName());
            }
            state.onState(ringing, foreground, background);
        });
        // The registry retains the last disconnect cause. Only live notifications
        // are relevant to the currently tracked call.
        setEvents(subscription, disconnectListener, new int[]{disconnectEvent}, false);
        try {
            setEvents(subscription, callback, new int[]{preciseEvent}, true);
        } catch (Exception failed) {
            try { setEvents(subscription, disconnectListener, new int[0], false); }
            catch (Exception cleanup) { failed.addSuppressed(cleanup); }
            throw failed;
        }
        return () -> {
            try { setEvents(subscription, callback, new int[0], false); }
            finally { setEvents(subscription, disconnectListener, new int[0], false); }
        };
    }

    private void setEvents(int subscription, Object callback, int[] events, boolean now) throws Exception {
        if (listen.getParameterCount() == 8) {
            listen.invoke(registry, true, true, subscription, SHELL_PACKAGE, null, callback, events, now);
        } else {
            listen.invoke(registry, subscription, SHELL_PACKAGE, null, callback, events, now);
        }
    }

    // Resolve the device's AIDL transaction and Parcelable decoder at runtime. The SDK omits
    // this listener's interface, whereas the framework enforces its normal shell permissions.
    private static final class CallbackType {
        final Class<?> type;
        private final int transaction;
        private final Method asInterface;

        CallbackType(String name, String method) throws Exception {
            type = Class.forName(TELEPHONY + name);
            Class<?> stub = Class.forName(type.getName() + "$Stub");
            var field = stub.getDeclaredField("TRANSACTION_" + method);
            field.setAccessible(true);
            transaction = field.getInt(null);
            asInterface = stub.getMethod("asInterface", IBinder.class);
        }

        Object create(java.util.function.Consumer<Parcel> receive) throws Exception {
            Binder callback = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    if (code != transaction) return super.onTransact(code, data, reply, flags);
                    if (Binder.getCallingUid() != Process.SYSTEM_UID) return false;
                    data.enforceInterface(type.getName());
                    receive.accept(data);
                    return true;
                }
            };
            return asInterface.invoke(null, callback);
        }
    }
}
