package dev.lemoyne.comfort;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * UID-1000 Binder push-listener bridge for Gen5W signals that have no zero-arg getter:
 *  - {@code com.hkmc.datc.IDATCListener.onDatcStatus(DatcStatus)} -> outside temperature (°F / °C)
 *  - {@code com.hkmc.bcm.IBCMListener.onRemoteTeleHeatingStatusChanged(int, int)} -> wheel heat
 *
 * Why this exists alongside HMG-API's {@code vehicle} hub:
 *  On QuickBoot STR resume or plugged-in charging power transitions, HMG-API's {@code CarCallbacks}
 *  clears {@code outsideF10} and {@code wheelHeat} and re-calls {@code registerDATCListener} with
 *  the *already-registered* {@link IBinder} instance. Mobis {@code DATCService} ignores duplicate
 *  registrations for an existing {@link IBinder} and only emits {@code onDatcStatus} on change,
 *  leaving {@code outside_temp_f} and {@code wheel_heat} stuck at {@code "-"} during plugged-in
 *  charging sessions when HVAC settings do not change.
 *
 *  {@code CabinPushBridge} explicitly unregisters the previous listener before registering a
 *  fresh {@link Binder} instance whenever woken or awaiting valid post-wake CAN frames, ensuring
 *  {@code DATCService} and {@code BCMService} immediately deliver their initial-registration
 *  state without leaking listener entries.
 */
final class CabinPushBridge implements OccupantSignalProbe.PushTelemetrySource {
    private static final String TAG = "HmgComfort";
    private static final long RETRY_REGISTER_MS = 2500L;
    private static final long RETRY_ACC_SENTINEL_MS = 12000L;

    private volatile int outsideF10 = Integer.MIN_VALUE;
    private volatile int outsideC10 = Integer.MIN_VALUE;
    private volatile int wheelHeat = Integer.MIN_VALUE;
    private volatile boolean datcSentinel;
    private volatile int datcTempUnit = OccupantSignalProbe.TEMP_UNIT_UNKNOWN;

    private boolean reflectionResolved;
    private Method getServiceMethod;
    private String datcServiceName = "com.hkmc.micom.datc";
    private String bcmServiceName = "com.hkmc.micom.bcm";

    private Method datcStubAsInterface;
    private Method datcListenerAsInterface;
    private Method datcRegisterMethod;
    private Method datcUnregisterMethod;
    private Method datcGetTempUnit;
    private String datcListenerDescriptor;
    private int txOnDatcStatus = -1;
    private Parcelable.Creator<?> datcStatusCreator;
    private Method getAmbientTempF;
    private Method getAmbientTempC;
    private Method getAmbientTempStateF;
    private Method getAmbientTempStateC;

    private Method bcmStubAsInterface;
    private Method bcmListenerAsInterface;
    private Method bcmRegisterMethod;
    private Method bcmUnregisterMethod;
    private String bcmListenerDescriptor;
    private int txOnBcmHeating = -1;

    private Object datcSvc;
    private RawListenerBinder datcBinder;
    private Object datcProxy;
    private long lastDatcAskMs;

    private Object bcmSvc;
    private RawListenerBinder bcmBinder;
    private Object bcmProxy;
    private long lastBcmAskMs;
    private boolean prevIgnOn;

    @Override
    public synchronized void onWake() {
        unregisterDatcLocked();
        unregisterBcmLocked();
        outsideF10 = Integer.MIN_VALUE;
        outsideC10 = Integer.MIN_VALUE;
        wheelHeat = Integer.MIN_VALUE;
        datcSentinel = false;
        datcTempUnit = OccupantSignalProbe.TEMP_UNIT_UNKNOWN;
        lastDatcAskMs = 0L;
        lastBcmAskMs = 0L;
    }

    private boolean hasDatcReading() {
        return outsideF10 != Integer.MIN_VALUE || outsideC10 != Integer.MIN_VALUE;
    }

    @Override
    public void ensure(boolean ignOn) {
        boolean waitDatc = false;
        boolean waitBcm = false;
        synchronized (this) {
            resolveReflectionLocked();
            if (!prevIgnOn && ignOn && !hasDatcReading()) {
                // Ignition (IG1/IG2) just came ON: immediately rotate a fresh listener
                lastDatcAskMs = 0L;
                lastBcmAskMs = 0L;
            }
            prevIgnOn = ignOn;

            long now = SystemClock.elapsedRealtime();
            long datcRetryMs = (!ignOn && datcSentinel) ? RETRY_ACC_SENTINEL_MS : RETRY_REGISTER_MS;
            if (datcSvc != null && !isServiceAlive(datcSvc)) {
                datcSvc = null;
                datcBinder = null;
                datcProxy = null;
            }
            if (datcBinder == null
                    || (!hasDatcReading() && now - lastDatcAskMs >= datcRetryMs)) {
                waitDatc = registerFreshDatcLocked(now);
            }

            long bcmRetryMs = ignOn ? RETRY_REGISTER_MS : RETRY_ACC_SENTINEL_MS;
            if (bcmSvc != null && !isServiceAlive(bcmSvc)) {
                bcmSvc = null;
                bcmBinder = null;
                bcmProxy = null;
            }
            if (bcmBinder == null
                    || (wheelHeat == Integer.MIN_VALUE && now - lastBcmAskMs >= bcmRetryMs)) {
                waitBcm = registerFreshBcmLocked(now);
            }
        }

        // Wait briefly outside the monitor so asynchronous Binder callbacks can deliver initial state
        if (waitDatc || waitBcm) {
            for (int i = 0; i < 6; i++) {
                boolean doneDatc = !waitDatc || hasDatcReading() || datcSentinel;
                boolean doneBcm = !waitBcm || wheelHeat != Integer.MIN_VALUE;
                if (doneDatc && doneBcm) {
                    break;
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public String outsideTempF() {
        int f10 = outsideF10;
        if (f10 != Integer.MIN_VALUE) {
            return formatTenths(f10 / 10f);
        }
        int c10 = outsideC10;
        if (c10 != Integer.MIN_VALUE) {
            return formatTenths(ComfortPolicy.celsiusToFahrenheit(c10 / 10f));
        }
        return "-";
    }

    @Override
    public String outsideTempC() {
        int c10 = outsideC10;
        if (c10 != Integer.MIN_VALUE) {
            return formatTenths(c10 / 10f);
        }
        int f10 = outsideF10;
        if (f10 != Integer.MIN_VALUE) {
            return formatTenths(ComfortPolicy.fahrenheitToCelsius(f10 / 10f));
        }
        return "-";
    }

    @Override
    public String wheelHeat() {
        int w = wheelHeat;
        return w == Integer.MIN_VALUE ? "-" : String.valueOf(w);
    }

    @Override
    public boolean isDatcSentinel() {
        return datcSentinel && !hasDatcReading();
    }

    @Override
    public int tempUnit() {
        return datcTempUnit;
    }

    private void resolveReflectionLocked() {
        if (reflectionResolved) return;
        reflectionResolved = true;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            getServiceMethod = sm.getMethod("getService", String.class);
        } catch (Throwable t) {
            return;
        }

        Class<?> carSvc = null;
        try {
            carSvc = Class.forName("com.hkmc.Service");
        } catch (Throwable ignored) {}
        datcServiceName = resolveStaticString(carSvc, "DATC_SERVICE", "com.hkmc.micom.datc");
        bcmServiceName = resolveStaticString(carSvc, "BCM_SERVICE", "com.hkmc.micom.bcm");

        try {
            Class<?> datcIface = Class.forName("com.hkmc.datc.IDATCService");
            Class<?> datcStub = Class.forName("com.hkmc.datc.IDATCService$Stub");
            Class<?> listenerIface = Class.forName("com.hkmc.datc.IDATCListener");
            Class<?> listenerStub = Class.forName("com.hkmc.datc.IDATCListener$Stub");
            Class<?> statusCls = Class.forName("com.hkmc.datc.DatcStatus");

            datcStubAsInterface = datcStub.getMethod("asInterface", IBinder.class);
            datcListenerAsInterface = listenerStub.getMethod("asInterface", IBinder.class);
            datcRegisterMethod = datcIface.getMethod("registerDATCListener", listenerIface, IBinder.class);
            datcUnregisterMethod = findUnregisterMethod(datcIface, "unregisterDATCListener", listenerIface);
            datcGetTempUnit = findOptionalNoArgMethod(datcIface, "getTemperatureUnit");
            datcListenerDescriptor = listenerIface.getName();

            Field txField = listenerStub.getDeclaredField("TRANSACTION_onDatcStatus");
            txField.setAccessible(true);
            txOnDatcStatus = txField.getInt(null);

            Field creatorField = statusCls.getDeclaredField("CREATOR");
            creatorField.setAccessible(true);
            datcStatusCreator = (Parcelable.Creator<?>) creatorField.get(null);

            getAmbientTempF = findOptionalNoArgMethod(statusCls, "getAmbientTempF");
            getAmbientTempC = findOptionalNoArgMethod(statusCls, "getAmbientTempC");
            getAmbientTempStateF = findOptionalNoArgMethod(statusCls, "getAmbientTempStateF");
            getAmbientTempStateC = findOptionalNoArgMethod(statusCls, "getAmbientTempStateC");
            if (getAmbientTempF == null && getAmbientTempC == null) {
                throw new NoSuchMethodException("DatcStatus missing getAmbientTempF/C");
            }
        } catch (Throwable t) {
            Log.w(TAG, "[hmgapi-comfort] DATC push bridge unavailable: " + t);
            txOnDatcStatus = -1;
        }

        try {
            Class<?> bcmIface = Class.forName("com.hkmc.bcm.IBCMService");
            Class<?> bcmStub = Class.forName("com.hkmc.bcm.IBCMService$Stub");
            Class<?> listenerIface = Class.forName("com.hkmc.bcm.IBCMListener");
            Class<?> listenerStub = Class.forName("com.hkmc.bcm.IBCMListener$Stub");

            bcmStubAsInterface = bcmStub.getMethod("asInterface", IBinder.class);
            bcmListenerAsInterface = listenerStub.getMethod("asInterface", IBinder.class);
            bcmRegisterMethod = bcmIface.getMethod("registerBCMListener", listenerIface, IBinder.class);
            bcmUnregisterMethod = findUnregisterMethod(bcmIface, "unregisterBCMListener", listenerIface);
            bcmListenerDescriptor = listenerIface.getName();

            Field txField = listenerStub.getDeclaredField("TRANSACTION_onRemoteTeleHeatingStatusChanged");
            txField.setAccessible(true);
            txOnBcmHeating = txField.getInt(null);
        } catch (Throwable t) {
            Log.w(TAG, "[hmgapi-comfort] BCM push bridge unavailable: " + t);
            txOnBcmHeating = -1;
        }
    }

    private int queryDatcTempUnit() {
        Object svc = datcSvc;
        Method m = datcGetTempUnit;
        if (svc == null || m == null) return 0;
        try {
            Object v = m.invoke(svc);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private boolean registerFreshDatcLocked(long now) {
        if (getServiceMethod == null || datcStubAsInterface == null || datcListenerAsInterface == null
                || datcRegisterMethod == null || datcStatusCreator == null || txOnDatcStatus < 0) {
            return false;
        }
        try {
            if (datcSvc == null) {
                Object b = getServiceMethod.invoke(null, datcServiceName);
                if (b == null) return false;
                datcSvc = datcStubAsInterface.invoke(null, b);
                if (datcSvc == null) return false;
            }
            unregisterDatcLocked();
            datcSentinel = false;
            RawListenerBinder binder = new RawListenerBinder(datcListenerDescriptor, txOnDatcStatus) {
                @Override
                void onCallback(Parcel d) throws Exception {
                    if (d.readInt() == 0) return;
                    Object st = datcStatusCreator.createFromParcel(d);
                    if (st == null) return;
                    float f = getAmbientTempF != null
                            ? ((Number) getAmbientTempF.invoke(st)).floatValue()
                            : Float.NaN;
                    float c = getAmbientTempC != null
                            ? ((Number) getAmbientTempC.invoke(st)).floatValue()
                            : Float.NaN;
                    int stateF = getAmbientTempStateF != null
                            ? ((Number) getAmbientTempStateF.invoke(st)).intValue()
                            : 0;
                    int stateC = getAmbientTempStateC != null
                            ? ((Number) getAmbientTempStateC.invoke(st)).intValue()
                            : 0;
                    boolean validF = (stateF != 255) && ComfortPolicy.validAmbientF(f);
                    boolean validC = (stateC != 255) && ComfortPolicy.validAmbientC(c);
                    if (!validF && !validC) {
                        datcSentinel = true;
                        outsideF10 = Integer.MIN_VALUE;
                        outsideC10 = Integer.MIN_VALUE;
                        return;
                    }
                    datcSentinel = false;
                    if (validC && !validF) {
                        outsideC10 = Math.round(c * 10f);
                        outsideF10 = Math.round(ComfortPolicy.celsiusToFahrenheit(c) * 10f);
                        datcTempUnit = OccupantSignalProbe.TEMP_UNIT_C;
                    } else if (validF && !validC) {
                        outsideF10 = Math.round(f * 10f);
                        outsideC10 = Math.round(ComfortPolicy.fahrenheitToCelsius(f) * 10f);
                        datcTempUnit = OccupantSignalProbe.TEMP_UNIT_F;
                    } else {
                        int svcUnit = queryDatcTempUnit();
                        if (Math.abs(f - ComfortPolicy.celsiusToFahrenheit(c)) > 3.0f) {
                            if (svcUnit == 1 || (f == 0.0f && Math.abs(c + 17.8f) > 2.0f)) {
                                outsideC10 = Math.round(c * 10f);
                                outsideF10 = Math.round(ComfortPolicy.celsiusToFahrenheit(c) * 10f);
                                datcTempUnit = OccupantSignalProbe.TEMP_UNIT_C;
                                return;
                            }
                            if (svcUnit == 2 || (c == 0.0f && Math.abs(f - 32.0f) > 3.0f)) {
                                outsideF10 = Math.round(f * 10f);
                                outsideC10 = Math.round(ComfortPolicy.fahrenheitToCelsius(f) * 10f);
                                datcTempUnit = OccupantSignalProbe.TEMP_UNIT_F;
                                return;
                            }
                        }
                        outsideF10 = Math.round(f * 10f);
                        outsideC10 = Math.round(c * 10f);
                        if (svcUnit == 1) {
                            datcTempUnit = OccupantSignalProbe.TEMP_UNIT_C;
                        } else if (svcUnit == 2) {
                            datcTempUnit = OccupantSignalProbe.TEMP_UNIT_F;
                        }
                    }
                }
            };
            Object proxy = datcListenerAsInterface.invoke(null, binder);
            datcRegisterMethod.invoke(datcSvc, proxy, binder);
            datcBinder = binder;
            datcProxy = proxy;
            lastDatcAskMs = now;
            return true;
        } catch (Throwable t) {
            datcSvc = null;
            datcBinder = null;
            datcProxy = null;
            lastDatcAskMs = now;
            return false;
        }
    }

    private boolean registerFreshBcmLocked(long now) {
        if (getServiceMethod == null || bcmStubAsInterface == null || bcmListenerAsInterface == null
                || bcmRegisterMethod == null || txOnBcmHeating < 0) {
            return false;
        }
        try {
            if (bcmSvc == null) {
                Object b = getServiceMethod.invoke(null, bcmServiceName);
                if (b == null) return false;
                bcmSvc = bcmStubAsInterface.invoke(null, b);
                if (bcmSvc == null) return false;
            }
            unregisterBcmLocked();
            RawListenerBinder binder = new RawListenerBinder(bcmListenerDescriptor, txOnBcmHeating) {
                @Override
                void onCallback(Parcel d) {
                    int w = d.readInt();
                    d.readInt();   // rear defogger
                    wheelHeat = w;
                }
            };
            Object proxy = bcmListenerAsInterface.invoke(null, binder);
            bcmRegisterMethod.invoke(bcmSvc, proxy, binder);
            bcmBinder = binder;
            bcmProxy = proxy;
            lastBcmAskMs = now;
            return true;
        } catch (Throwable t) {
            bcmSvc = null;
            bcmBinder = null;
            bcmProxy = null;
            lastBcmAskMs = now;
            return false;
        }
    }

    private void unregisterDatcLocked() {
        if (datcSvc != null && datcUnregisterMethod != null && (datcProxy != null || datcBinder != null)) {
            invokeUnregister(datcUnregisterMethod, datcSvc, datcProxy, datcBinder);
        }
        datcBinder = null;
        datcProxy = null;
    }

    private void unregisterBcmLocked() {
        if (bcmSvc != null && bcmUnregisterMethod != null && (bcmProxy != null || bcmBinder != null)) {
            invokeUnregister(bcmUnregisterMethod, bcmSvc, bcmProxy, bcmBinder);
        }
        bcmBinder = null;
        bcmProxy = null;
    }

    private static Method findOptionalNoArgMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            try {
                Method m = cls.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static Method findUnregisterMethod(Class<?> svcIface, String exactName, Class<?> listenerIface) {
        Method[] methods = svcIface.getMethods();
        Method fallback = null;
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            String n = m.getName();
            String lower = n.toLowerCase(Locale.US);
            boolean exact = exactName.equals(n);
            if (!exact && !lower.startsWith("unregister") && !lower.startsWith("remove")) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            boolean validParams = (p.length == 2 && p[0] == listenerIface && p[1] == IBinder.class)
                    || (p.length == 1 && (p[0] == listenerIface || p[0] == IBinder.class));
            if (!validParams) {
                continue;
            }
            if (exact) {
                return m;
            }
            if (fallback == null) {
                fallback = m;
            }
        }
        return fallback;
    }

    private static void invokeUnregister(Method m, Object svc, Object proxy, IBinder binder) {
        try {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2) {
                m.invoke(svc, proxy, binder);
            } else if (p.length == 1 && p[0] == IBinder.class) {
                m.invoke(svc, binder);
            } else if (p.length == 1) {
                m.invoke(svc, proxy);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isServiceAlive(Object svcProxy) {
        if (svcProxy == null) return false;
        try {
            IBinder b = (IBinder) svcProxy.getClass().getMethod("asBinder").invoke(svcProxy);
            return b != null && b.isBinderAlive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String resolveStaticString(Class<?> holder, String field, String fallback) {
        if (holder != null) {
            try {
                Object v = holder.getField(field).get(null);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
            } catch (Throwable ignored) {}
        }
        return fallback;
    }

    static String formatTenths(float v) {
        int t = Math.round(v * 10f);
        if (t % 10 == 0) {
            return String.valueOf(t / 10);
        }
        return String.format(Locale.US, "%.1f", Float.valueOf(t / 10f));
    }

    /**
     * KitKat-safe raw {@link Binder} answering a single AIDL callback transaction code while
     * safely acknowledging all other interface callbacks (including {@code out byte[]} callbacks
     * such as {@code IBCMListener.onDigitalKeyDataChanged}).
     */
    private abstract static class RawListenerBinder extends Binder {
        private static final int MAX_OUT_ARRAY = 64 * 1024;
        private final String descriptor;
        private final int targetCode;

        RawListenerBinder(String descriptor, int targetCode) {
            this.descriptor = descriptor;
            this.targetCode = targetCode;
            attachInterface(null, descriptor);
        }

        abstract void onCallback(Parcel data) throws Exception;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(descriptor);
                return true;
            }
            if (code == targetCode && data != null) {
                try {
                    data.enforceInterface(descriptor);
                    onCallback(data);
                } catch (Throwable t) {
                    Log.w(TAG, "[hmgapi-comfort] callback " + code + " error: " + t);
                }
            }
            if (reply != null) {
                answerSafe(data, reply);
            }
            return true;
        }

        private static void answerSafe(Parcel data, Parcel reply) {
            reply.writeNoException();
            if (data == null) return;
            int end = data.dataSize();
            if (end < 4) return;
            data.setDataPosition(end - 4);
            int n = data.readInt();
            if (n >= 0 && n <= MAX_OUT_ARRAY) {
                reply.writeByteArray(new byte[n]);
            }
        }
    }
}
