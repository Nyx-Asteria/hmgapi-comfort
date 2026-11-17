package dev.lemoyne.comfort;

import android.os.SystemClock;
import android.util.Log;

import hmgapi.client.HmgApi;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Ultra-low-latency boot & QuickBoot STR resume daemon run directly under app_process
 * (user=system, start=boot) by hmgapid.
 *
 * Unlike Android BroadcastReceivers or Services—which wait behind PackageManager sync,
 * ActivityManager startup, BOOT_COMPLETED broadcast queues, and multi-signal vehicle reads—
 * WelcomeDaemon starts at t=0, polls sys.boot_completed in memory every 50 ms on cold boot,
 * and watches both kernel monotonic-vs-uptime suspend gaps (>=3 s) and /data/hmgapi/public/wake
 * every 150 ms across QuickBoot STR cycles.
 */
public final class WelcomeDaemon {
    private static final String TAG = "HmgComfort";
    private static final File STATE_FILE = new File("/data/hmgapi/public/state.json");
    private static final File WAKE_FILE = new File("/data/hmgapi/public/wake");

    private static Method getPropMethod;
    private static volatile OccupantSignalProbe activeProbe;
    private static boolean welcomeDismiss = true;
    private static int welcomeDelayMs = 0;
    private static long lastStateMtime = -1L;

    private WelcomeDaemon() {}

    public static void main(String[] args) {
        initSystemProperties();
        log("welcome fast-path daemon started");

        // 1. Cold-boot wait: poll sys.boot_completed in-process every 50 ms
        while (!"1".equals(getProp("sys.boot_completed"))) {
            sleepQuietly(50);
        }

        long lastSleepGap = currentSleepGap();
        long lastWakeSeq = readWakeSeq();
        long lastEnsureMs = 0L;

        ensureComfortServiceAsync(false);
        lastEnsureMs = SystemClock.elapsedRealtime();
        startProbeServer();

        refreshSettings(true);
        if (welcomeDismiss) {
            dismissWithRetry("cold-boot", true);
        }

        // Re-ensure ComfortService after cold-boot ModApps.sync finishes any APK install/update
        ensureComfortServiceAsync(false);
        lastEnsureMs = SystemClock.elapsedRealtime();

        int mobisTick = 0;
        int lastMobisSleeping = activeProbe == null ? -1 : activeProbe.readMobisSleepingFast();

        // 2. Resident QuickBoot STR, wake.sh, Mobis BlackScreen/AV-Off, and ComfortService watchdog
        while (true) {
            sleepQuietly(150);
            refreshSettings(false);

            long now = SystemClock.elapsedRealtime();
            long gap = currentSleepGap();
            long wakeSeq = readWakeSeq();
            boolean gapWoke = gap - lastSleepGap >= 3000L;
            boolean seqWoke = wakeSeq > lastWakeSeq;
            lastSleepGap = gap;
            lastWakeSeq = wakeSeq;

            boolean mobisWoke = false;
            mobisTick++;
            if (mobisTick >= 10) {
                mobisTick = 0;
                OccupantSignalProbe p = activeProbe;
                int ms = p == null ? -1 : p.readMobisSleepingFast();
                if (ms == 0 || ms == 1) {
                    if (lastMobisSleeping == 1 && ms == 0) {
                        mobisWoke = true;
                    } else if (lastMobisSleeping == 0 && ms == 1 && p != null) {
                        p.onWake();
                    }
                    lastMobisSleeping = ms;
                }
            }

            if (gapWoke || seqWoke || mobisWoke) {
                OccupantSignalProbe p = activeProbe;
                if (p != null) {
                    p.onWake();
                }
                ensureComfortServiceAsync(true);
                lastEnsureMs = now;
                if (welcomeDismiss) {
                    String trigger = gapWoke ? "quickboot-gap" : (seqWoke ? "wake-hook" : "mobis-wake");
                    dismissWithRetry(trigger, false);
                    // Re-baseline after dismissal in case time advanced during retries
                    lastSleepGap = currentSleepGap();
                    lastWakeSeq = readWakeSeq();
                    if (p != null) {
                        int msAfter = p.readMobisSleepingFast();
                        if (msAfter == 0 || msAfter == 1) {
                            lastMobisSleeping = msAfter;
                        }
                    }
                }
            } else if (now - lastEnsureMs >= 30000L) {
                lastEnsureMs = now;
                ensureComfortServiceAsync(false);
            }
        }
    }

    private static void dismissWithRetry(String trigger, boolean coldBoot) {
        int delay = welcomeDelayMs;
        if (coldBoot && delay < 2000) {
            // On cold boot, sys.boot_completed=1 fires while the OEM boot animation is still
            // finishing and before the safety consent screen is subscribed; wait at least 2.0 s.
            delay = 2000;
        }
        if (delay > 0) {
            sleepQuietly(delay);
        }
        int maxAttempts = coldBoot ? 60 : 40;
        long retryMs = coldBoot ? 500L : 250L;
        int confirmedOutOfPark = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HmgApi api = HmgApi.connect().timeout(5000);
                HmgApi.Result who = api.call("whoami");
                if (who.code == 13) {
                    log("welcome fast-path (" + trigger + "): mod disabled");
                    return;
                }
                if (who.code != 0 || "root".equals(who.text.trim()) || who.text.trim().isEmpty()) {
                    // Transient hmgapid busy (code 75) or still registering
                    sleepQuietly(retryMs);
                    continue;
                }
                String res = api.command("startup_notice", "dismiss");
                log("welcome fast-path (" + trigger + ", attempt " + attempt + ") -> " + res);
                return;
            } catch (HmgApi.HmgApiException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("once per start")
                        || msg.contains("hasn't allowed")
                        || msg.contains("didn't allow")) {
                    log("welcome fast-path (" + trigger + "): " + msg);
                    return;
                }
                if (msg.contains("isn't in P") || msg.contains("not in P")) {
                    try {
                        HmgApi checkApi = HmgApi.connect().timeout(4500);
                        java.util.Map<String, String> v = checkApi.vehicle("gear.park", "hv.ready");
                        if (ComfortPolicy.isConfirmedOutOfPark(v)) {
                            confirmedOutOfPark++;
                            if (confirmedOutOfPark >= 3) {
                                log("welcome fast-path (" + trigger + "): " + msg);
                                return;
                            }
                        } else {
                            confirmedOutOfPark = 0;
                        }
                    } catch (Throwable ignored) {}
                }
                sleepQuietly(retryMs);
            } catch (Throwable ignored) {
                sleepQuietly(retryMs);
            }
        }
        log("welcome fast-path (" + trigger + "): timed out waiting for vehicle hub / Park");
    }

    private static void ensureComfortServiceAsync(final boolean wakeEvent) {
        if (!"1".equals(getProp("sys.boot_completed"))) return;
        Thread t = new Thread("ComfortServiceEnsure") {
            @Override
            public void run() {
                try {
                    HmgApi api = HmgApi.connect().timeout(3500);
                    if (ComfortEngine.isModExplicitlyDisabled(api)) return;
                    String action = wakeEvent
                            ? ComfortService.ACTION_MOBIS_SCREEN_ON
                            : ComfortService.ACTION_ENSURE_RUNNING;
                    Process p = new ProcessBuilder(
                            "/system/bin/am", "startservice", "--user", "0",
                            "-a", action,
                            "-n", "dev.lemoyne.comfort/.ComfortService")
                            .redirectErrorStream(true)
                            .start();
                    p.waitFor();
                } catch (Throwable ignored) {}
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private static void startProbeServer() {
        OccupantSignalProbe probe = new OccupantSignalProbe(
                new OccupantSignalProbe.ReflectionGen5wSignalSource(new CabinPushBridge()),
                "UID 1000 daemon");
        activeProbe = probe;
        try {
            OccupantSignalProbe.LoopbackServer srv =
                    new OccupantSignalProbe.LoopbackServer(probe, OccupantSignalProbe.DEFAULT_PORT);
            srv.start();
            log("occupant signal probe listening on 127.0.0.1:" + OccupantSignalProbe.DEFAULT_PORT);
        } catch (Throwable t) {
            log("occupant signal probe server unavailable: " + t);
        }
    }

    private static void refreshSettings(boolean force) {
        long mtime = STATE_FILE.isFile() ? STATE_FILE.lastModified() : 0L;
        if (!force && mtime == lastStateMtime) return;
        try {
            HmgApi api = HmgApi.connect().timeout(3500);
            if (ComfortEngine.isModExplicitlyDisabled(api)) return;
            JSONObject s = api.settings();
            if (s != null) {
                welcomeDismiss = s.optBoolean("welcome_dismiss", true);
                int d = s.optInt("welcome_delay_ms", 800);
                welcomeDelayMs = Math.max(0, Math.min(10000, d));
                lastStateMtime = mtime;
            }
        } catch (Throwable ignored) {}
    }

    private static long currentSleepGap() {
        return SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
    }

    static long readWakeSeq() {
        if (!WAKE_FILE.isFile()) return 0L;
        FileInputStream in = null;
        try {
            in = new FileInputStream(WAKE_FILE);
            byte[] b = new byte[32];
            int n = in.read(b);
            if (n <= 0) return 0L;
            return Long.parseLong(new String(b, 0, n, "UTF-8").trim());
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void initSystemProperties() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            getPropMethod = sp.getMethod("get", String.class);
        } catch (Throwable ignored) {}
    }

    private static String getProp(String key) {
        if (getPropMethod != null) {
            try {
                Object v = getPropMethod.invoke(null, key);
                return v == null ? "" : String.valueOf(v).trim();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String msg) {
        String line = "[hmgapi-comfort] " + msg;
        System.out.println(line);
        try {
            Log.i(TAG, line);
        } catch (Throwable ignored) {}
    }
}
