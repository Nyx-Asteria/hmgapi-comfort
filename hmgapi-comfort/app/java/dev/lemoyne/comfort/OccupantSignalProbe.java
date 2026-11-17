package dev.lemoyne.comfort;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only, privacy-safe Gen5W OEM service & signal inspector for discovering front-passenger
 * and rear-seat occupancy, seatbelt buckle state, door open/close state, and Rear Occupant Alert
 * (ROA) signals on Hyundai / Kia / Genesis head units.
 *
 * Hard privacy & safety invariants:
 *  - Never invokes mutators ({@code set*}, {@code request*}, {@code send*}, {@code register*}).
 *  - Never reads {@code String}, {@code CharSequence}, {@code byte[]}, or {@code char[]} getters.
 *  - Word-aware + substring privacy filter blocks VIN, serials, phone/modem/account/auth/GPS/MAC identifiers
 *    while preserving legitimate cabin names ({@code passenger}, {@code pass}, {@code telltale},
 *    {@code moving}, {@code driving}, {@code latch}).
 */
public final class OccupantSignalProbe {

    public static final int DEFAULT_PORT = 35821;

    private static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    private static final int CHANGE_LOG_CAP = 250;
    private static final int MAX_HISTORY_STEPS = 8;
    private static final long AUTO_CAPTURE_WINDOW_MS = 10L * 60L * 1000L;

    public interface PushTelemetrySource {
        void onWake();
        void ensure(boolean ignOn);
        String outsideTempF();
        String outsideTempC();
        String wheelHeat();
        boolean isDatcSentinel();
    }

    public interface SignalSource {
        String serviceStatusSummary();
        String deviceBuildSummary();
        List<String> signatureInventory();
        Map<String, String> readSignals(boolean fullScan);
        PassengerPresence readPassengerPresence();
        CabinState readCabinState();
        int readMobisSleepingFast();
        void onWake();
    }

    /**
     * Live front-passenger seat occupancy snapshot from Gen5W HSCAN & SYSTEM services:
     *  - HSCAN.getOccupancy: 1 = unoccupied / solo driver, 2 = front passenger occupied
     *  - SYS.getClassStatus: 1 = ODS empty, 2 = adult occupant classified on passenger mat
     *  - HSCAN.getAsstStBltSwSta: 0 = unbuckled, 1 = front-passenger belt buckled
     *  - HSCAN.getPassStOccSta: 1 = occupied on trims exposing direct SBR mat flag
     */
    public static final class PassengerPresence {
        public static final PassengerPresence UNAVAILABLE =
                new PassengerPresence(false, false, -1, -1, -1, -1);

        public final boolean available;
        public final boolean occupied;
        public final int hscanOccupancy;
        public final int sysClassStatus;
        public final int asstBelt;
        public final int passStOcc;

        public PassengerPresence(
                boolean available,
                boolean occupied,
                int hscanOccupancy,
                int sysClassStatus,
                int asstBelt,
                int passStOcc) {
            this.available = available;
            this.occupied = occupied;
            this.hscanOccupancy = hscanOccupancy;
            this.sysClassStatus = sysClassStatus;
            this.asstBelt = asstBelt;
            this.passStOcc = passStOcc;
        }

        public static PassengerPresence fromRaw(
                int hscanOccupancy, int sysClassStatus, int asstBelt, int passStOcc) {
            boolean occupied = (hscanOccupancy == 2)
                    || (sysClassStatus == 2)
                    || (passStOcc == 1)
                    || (asstBelt == 1);
            boolean matReporting = (hscanOccupancy >= 1 && hscanOccupancy <= 14)
                    || (sysClassStatus >= 1 && sysClassStatus <= 14);
            boolean available = occupied || matReporting;
            return new PassengerPresence(
                    available, occupied, hscanOccupancy, sysClassStatus, asstBelt, passStOcc);
        }

        public static PassengerPresence fromSignalMap(Map<String, String> m) {
            if (m == null || m.isEmpty()) return UNAVAILABLE;
            return fromRaw(
                    parseRawInt(m.get("HSCAN.getOccupancy")),
                    parseRawInt(m.get("SYS.getClassStatus")),
                    parseRawInt(m.get("HSCAN.getAsstStBltSwSta")),
                    parseRawInt(m.get("HSCAN.getPassStOccSta")));
        }

        public String encode() {
            return (available ? "1" : "0") + ","
                    + (occupied ? "1" : "0") + ","
                    + hscanOccupancy + ","
                    + sysClassStatus + ","
                    + asstBelt + ","
                    + passStOcc;
        }

        public static PassengerPresence decode(String wire) {
            if (wire == null || wire.isEmpty()) return UNAVAILABLE;
            String[] p = wire.trim().split(",");
            if (p.length < 6) return UNAVAILABLE;
            return new PassengerPresence(
                    "1".equals(p[0]),
                    "1".equals(p[1]),
                    parseRawInt(p[2]),
                    parseRawInt(p[3]),
                    parseRawInt(p[4]),
                    parseRawInt(p[5]));
        }

        public String summary() {
            if (!available) return "sensor unavailable";
            return (occupied ? "occupied" : "empty")
                    + " (occ=" + hscanOccupancy
                    + ", ods=" + sysClassStatus
                    + ", belt=" + asstBelt + ")";
        }

        static int parseRawInt(String s) {
            if (s == null || s.isEmpty()) return -1;
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /**
     * Tri-state optional seat climate hardware presence from Gen5W BCM / USM option getters
     * ({@code 1} = equipped, {@code 0} = confirmed absent, {@code -1} = unknown / pre-CAN):
     *  - {@code frontVent}: front seat ventilation ({@code *SeatHeatOption} in {@code 3..14}, e.g. {@code 6})
     *  - {@code rearLeftHeat}: rear-left seat heater ({@code RLSeatHeatOption} in {@code 1..14})
     *  - {@code rearRightHeat}: rear-right seat heater ({@code RRSeatHeatOption} in {@code 1..14})
     *
     * Absent ({@code 0}) is only asserted once at least one seat option reports a valid positive
     * equipped enum ({@code 1..14}), preventing uninitialized pre-CAN zeros from hiding controls.
     */
    public static final class SeatCapabilities {
        public static final SeatCapabilities UNKNOWN = new SeatCapabilities(-1, -1, -1);
        public static final SeatCapabilities ALL_EQUIPPED = new SeatCapabilities(1, 1, 1);

        public final int frontVent;
        public final int rearLeftHeat;
        public final int rearRightHeat;

        public SeatCapabilities(int frontVent, int rearLeftHeat, int rearRightHeat) {
            this.frontVent = normTri(frontVent);
            this.rearLeftHeat = normTri(rearLeftHeat);
            this.rearRightHeat = normTri(rearRightHeat);
        }

        public boolean isFrontVentSupported() {
            return frontVent != 0;
        }

        public boolean isRearLeftHeatSupported() {
            return rearLeftHeat != 0;
        }

        public boolean isRearRightHeatSupported() {
            return rearRightHeat != 0;
        }

        public boolean hasAnyRearHeatSupported() {
            return isRearLeftHeatSupported() || isRearRightHeatSupported();
        }

        public SeatCapabilities mergeKnown(SeatCapabilities live) {
            if (live == null) return this;
            int fv = live.frontVent != -1 ? live.frontVent : this.frontVent;
            int rl = live.rearLeftHeat != -1 ? live.rearLeftHeat : this.rearLeftHeat;
            int rr = live.rearRightHeat != -1 ? live.rearRightHeat : this.rearRightHeat;
            return new SeatCapabilities(fv, rl, rr);
        }

        public static int pickOption(int primaryBcm, int fallbackUsm) {
            if (primaryBcm >= 1 && primaryBcm <= 14) return primaryBcm;
            if (fallbackUsm >= 1 && fallbackUsm <= 14) return fallbackUsm;
            if (primaryBcm == 0 || fallbackUsm == 0) return 0;
            return -1;
        }

        public static SeatCapabilities fromRawOptions(int drvOpt, int astOpt, int rlOpt, int rrOpt) {
            boolean known = isEquippedCode(drvOpt)
                    || isEquippedCode(astOpt)
                    || isEquippedCode(rlOpt)
                    || isEquippedCode(rrOpt);
            if (!known) {
                return UNKNOWN;
            }
            int fv = (isVentCode(drvOpt) || isVentCode(astOpt)) ? 1 : 0;
            int rl = isEquippedCode(rlOpt) ? 1 : (rlOpt == 0 ? 0 : -1);
            int rr = isEquippedCode(rrOpt) ? 1 : (rrOpt == 0 ? 0 : -1);
            return new SeatCapabilities(fv, rl, rr);
        }

        public static SeatCapabilities fromSignalMap(Map<String, String> m) {
            if (m == null || m.isEmpty()) return UNKNOWN;
            int drv = pickOption(
                    PassengerPresence.parseRawInt(m.get("BCM.getDrvSeatHeatOption")),
                    PassengerPresence.parseRawInt(m.get("USM.getFrontSeatHeat_DrvOption")));
            int ast = pickOption(
                    PassengerPresence.parseRawInt(m.get("BCM.getAstSeatHeatOption")),
                    PassengerPresence.parseRawInt(m.get("USM.getFrontSeatHeat_AstOption")));
            int rl = pickOption(
                    PassengerPresence.parseRawInt(m.get("BCM.getRLSeatHeatOption")),
                    PassengerPresence.parseRawInt(m.get("USM.getRearSeatHeat_LeftOption")));
            int rr = pickOption(
                    PassengerPresence.parseRawInt(m.get("BCM.getRRSeatHeatOption")),
                    PassengerPresence.parseRawInt(m.get("USM.getRearSeatHeat_RightOption")));
            return fromRawOptions(drv, ast, rl, rr);
        }

        public String encode() {
            return frontVent + "," + rearLeftHeat + "," + rearRightHeat;
        }

        public static SeatCapabilities decode(String wire) {
            if (wire == null || wire.isEmpty()) return UNKNOWN;
            String[] p = wire.trim().split(",");
            if (p.length < 3) return UNKNOWN;
            return new SeatCapabilities(
                    PassengerPresence.parseRawInt(p[0]),
                    PassengerPresence.parseRawInt(p[1]),
                    PassengerPresence.parseRawInt(p[2]));
        }

        public String summary() {
            return "vent=" + triChar(frontVent)
                    + ",rl=" + triChar(rearLeftHeat)
                    + ",rr=" + triChar(rearRightHeat);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SeatCapabilities)) return false;
            SeatCapabilities s = (SeatCapabilities) o;
            return frontVent == s.frontVent
                    && rearLeftHeat == s.rearLeftHeat
                    && rearRightHeat == s.rearRightHeat;
        }

        @Override
        public int hashCode() {
            return (frontVent + 2) * 31 * 31 + (rearLeftHeat + 2) * 31 + (rearRightHeat + 2);
        }

        private static boolean isEquippedCode(int opt) {
            return opt >= 1 && opt <= 14;
        }

        private static boolean isVentCode(int opt) {
            return opt >= 3 && opt <= 14;
        }

        private static int normTri(int v) {
            return v == 1 ? 1 : (v == 0 ? 0 : -1);
        }

        private static String triChar(int v) {
            return v == 1 ? "1" : (v == 0 ? "0" : "-");
        }
    }

    /**
     * Combined UID-1000 cabin, ignition, display-standby, push-listener fallback, and seat-option snapshot:
     *  - passenger: front-passenger occupancy & buckle state
     *  - outsideTempF / outsideTempC / wheelHeat: fresh Binder listener push values when HMG-API hub is "-"
     *  - ignOn: BCM.getIGNStatus (1 = Ignition ON, 0 = ACC/OFF, -1 = unknown)
     *  - ign3On: DATC.isIGN3Status (1 = IGN3 active during charging/utility/ready, 0 = OFF, -1 = unknown)
     *  - avAwake: 1 = display & AV active, 0 = BlackScreen / AV-Off / Display-Off, -1 = unknown
     *  - mobisSleeping: 1 = Mobis BlackScreen or AV-Off QuickBoot standby, 0 = awake, -1 = unknown
     *  - datcSentinel: true when DATC pushed -40.0 F raw-zero sentinel (unpowered IG1/ACC)
     *  - seatCaps: vehicle hardware presence for ventilated front seats & heated rear seats
     */
    public static final class CabinState {
        public static final CabinState UNAVAILABLE = new CabinState(
                PassengerPresence.UNAVAILABLE, "-", "-", "-", -1, -1, -1, -1, false,
                SeatCapabilities.UNKNOWN);

        public final PassengerPresence passenger;
        public final String outsideTempF;
        public final String outsideTempC;
        public final String wheelHeat;
        public final int ignOn;
        public final int ign3On;
        public final int avAwake;
        public final int mobisSleeping;
        public final boolean datcSentinel;
        public final SeatCapabilities seatCaps;

        public CabinState(
                PassengerPresence passenger,
                String outsideTempF,
                String outsideTempC,
                String wheelHeat,
                int ignOn,
                int ign3On,
                int avAwake,
                int mobisSleeping,
                boolean datcSentinel) {
            this(passenger, outsideTempF, outsideTempC, wheelHeat,
                    ignOn, ign3On, avAwake, mobisSleeping, datcSentinel,
                    SeatCapabilities.UNKNOWN);
        }

        public CabinState(
                PassengerPresence passenger,
                String outsideTempF,
                String outsideTempC,
                String wheelHeat,
                int ignOn,
                int ign3On,
                int avAwake,
                int mobisSleeping,
                boolean datcSentinel,
                SeatCapabilities seatCaps) {
            this.passenger = passenger == null ? PassengerPresence.UNAVAILABLE : passenger;
            this.outsideTempF = normField(outsideTempF);
            this.outsideTempC = normField(outsideTempC);
            this.wheelHeat = normField(wheelHeat);
            this.ignOn = ignOn;
            this.ign3On = ign3On;
            this.avAwake = avAwake;
            this.mobisSleeping = mobisSleeping;
            this.datcSentinel = datcSentinel;
            this.seatCaps = seatCaps == null ? SeatCapabilities.UNKNOWN : seatCaps;
        }

        public static int computeAvAwake(int blackScreen, int avPowerOff, int displayOffMode) {
            if (blackScreen == 1 || avPowerOff == 1 || displayOffMode == 1) {
                return 0;
            }
            if (blackScreen == 0 || avPowerOff == 0 || displayOffMode == 0) {
                return 1;
            }
            return -1;
        }

        public static int computeMobisSleeping(int blackScreen, int avPowerOff) {
            if (blackScreen == 1 || avPowerOff == 1) {
                return 1;
            }
            if (blackScreen == 0 || avPowerOff == 0) {
                return 0;
            }
            return -1;
        }

        public static CabinState fromSignalMap(Map<String, String> m) {
            if (m == null || m.isEmpty()) return UNAVAILABLE;
            int black = parseTriState(m.get("BLACKSCREEN.isBlackScreenShown"));
            int avOff = parseTriState(m.get("POWER.isAvPowerOff"));
            int dispOff = parseTriState(m.get("POWER.isDisplayOffMode"));
            return new CabinState(
                    PassengerPresence.fromSignalMap(m),
                    m.get("PUSH.outside_temp_f"),
                    m.get("PUSH.outside_temp"),
                    m.get("PUSH.wheel_heat"),
                    parseTriState(m.get("BCM.getIGNStatus")),
                    parseTriState(m.get("DATC.isIGN3Status")),
                    computeAvAwake(black, avOff, dispOff),
                    computeMobisSleeping(black, avOff),
                    parseTriState(m.get("PUSH.datc_sentinel")) == 1,
                    SeatCapabilities.fromSignalMap(m));
        }

        public String encode() {
            return passenger.encode() + "\t"
                    + outsideTempF + "\t"
                    + outsideTempC + "\t"
                    + wheelHeat + "\t"
                    + ignOn + "\t"
                    + ign3On + "\t"
                    + avAwake + "\t"
                    + mobisSleeping + "\t"
                    + (datcSentinel ? "1" : "0") + "\t"
                    + seatCaps.encode();
        }

        public static CabinState decode(String wire) {
            if (wire == null || wire.isEmpty()) return UNAVAILABLE;
            String[] p = wire.split("\t", -1);
            if (p.length < 9) return UNAVAILABLE;
            SeatCapabilities caps = p.length >= 10
                    ? SeatCapabilities.decode(p[9])
                    : SeatCapabilities.UNKNOWN;
            return new CabinState(
                    PassengerPresence.decode(p[0]),
                    p[1],
                    p[2],
                    p[3],
                    PassengerPresence.parseRawInt(p[4]),
                    PassengerPresence.parseRawInt(p[5]),
                    PassengerPresence.parseRawInt(p[6]),
                    PassengerPresence.parseRawInt(p[7]),
                    "1".equals(p[8].trim()),
                    caps);
        }

        public String summary() {
            return "ign=" + triName(ignOn)
                    + " ign3=" + triName(ign3On)
                    + " av_awake=" + triName(avAwake)
                    + " push_temp_f=" + outsideTempF
                    + (datcSentinel ? "(sentinel)" : "")
                    + " push_wheel=" + wheelHeat
                    + " caps=[" + seatCaps.summary() + "]";
        }

        private static String normField(String s) {
            if (s == null) return "-";
            String t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
            return t.isEmpty() ? "-" : t;
        }

        static int parseTriState(String s) {
            if (s == null) return -1;
            String t = s.trim();
            if ("1".equals(t) || "true".equalsIgnoreCase(t)) return 1;
            if ("0".equals(t) || "false".equalsIgnoreCase(t)) return 0;
            return -1;
        }

        private static String triName(int v) {
            return v == 1 ? "1" : (v == 0 ? "0" : "-");
        }
    }

    public static final class UiState {
        public final String sourceBanner;
        public final String baselineSummary;
        public final int changedCount;
        public final String changesText;
        public final String candidatesText;

        public UiState(
                String sourceBanner,
                String baselineSummary,
                int changedCount,
                String changesText,
                String candidatesText) {
            this.sourceBanner = sourceBanner;
            this.baselineSummary = baselineSummary;
            this.changedCount = changedCount;
            this.changesText = changesText;
            this.candidatesText = candidatesText;
        }
    }

    private static final String[] BLOCKED_SUBSTRINGS = {
        "imei", "iccid", "imsi", "msisdn", "meid", "serial",
        "passwd", "password", "passcode", "passkey", "pincode",
        "bssid", "ssid", "uuid", "guid", "udid",
        "bluetooth", "btaddr", "bdaddr", "macaddr", "ipaddr",
        "account", "userid", "username", "latitude", "longitude",
        "bluelink", "modem"
    };

    private static final String[] BLOCKED_WORDS = {
        "vin", "sn", "phone", "tel", "call", "sms", "dial", "mdn", "esn",
        "pin", "token", "secret", "cert", "mac", "lat", "lon", "lng",
        "gps", "coord", "coordinate", "dest", "destination",
        "addr", "address", "email", "wifi", "hotspot"
    };

    private static final String[] BLOCKED_SERVICE_TOKENS = {
        "micomdata", "factory", "modem", "bluetooth", "bt_", "_bt",
        "phone", "call", "navi", "dipo", "carplay", "iap", "wifi", "tms", "mts"
    };

    private static final String[] OCCUPANT_PRIORITY_TOKENS = {
        "seat", "asst", "psg", "pass", "drv", "driver", "front", "rear",
        "rl_", "rr_", "fl_", "fr_", "rlseat", "rrseat", "2nd", "row",
        "belt", "stblt", "sbr", "buckle", "occup", "ocs", "classstatus", "mat", "weight",
        "airbag", "pab", "srs", "telltale",
        "door", "dr_", "ajar", "latch", "lock", "trunk", "tailgate", "hood",
        "roa", "child", "presence", "person", "ultrasonic", "cabin"
    };

    private static final String[] OCCUPANT_PRIORITY_WORDS = {
        "ast", "asst", "ods"
    };

    private static final String[] NOISY_COUNTER_TOKENS = {
        "odometer", "drivetime", "idletime", "runtime", "runningtime",
        "carspeed", "car_speed", "speeddist", "sasangle", "sasspd", "steering",
        "cylprsr", "accelpdl", "longaccel", "lataccel", "yawrate", "whlspd", "wheelspd",
        "ambienttemp", "outtemp", "battemp", "celltemp", "coolant",
        "remaintime", "remaindis", "abletodrive",
        "gettime", "getheading", "clmtpwrcnsmptn", "clmtpwrsum", "pwr_other_ecu",
        "energymap_1000ms", "added_dist_datc", "ldc_loadpwrsum"
    };

    private final SignalSource source;
    private final String modeLabel;

    private final Map<String, String> initialValues = new TreeMap<String, String>();
    private final Map<String, String> baselineValues = new TreeMap<String, String>();
    private final Map<String, String> latestValues = new TreeMap<String, String>();
    private final Map<String, Integer> sessionChangeCounts = new TreeMap<String, Integer>();
    private final Map<String, Integer> sinceBaselineChanges = new TreeMap<String, Integer>();
    private final Map<String, List<String>> sinceBaselineHistory = new TreeMap<String, List<String>>();

    private final String[] changeLog = new String[CHANGE_LOG_CAP];
    private int changeHead;
    private int changeSize;

    private long baselineWallMs;
    private volatile long autoCaptureUntilMs;
    private int markCounter;
    private String baselineLabel = "";
    private int changedSinceRefCount;
    private int activeDiffCount;
    private int toggledReturnedCount;

    public OccupantSignalProbe() {
        this(new ReflectionGen5wSignalSource(), "in-process");
    }

    public OccupantSignalProbe(SignalSource source, String modeLabel) {
        this.source = source == null ? new ReflectionGen5wSignalSource() : source;
        this.modeLabel = (modeLabel == null || modeLabel.isEmpty()) ? "probe" : modeLabel;
    }

    public PassengerPresence readPassengerPresence() {
        try {
            PassengerPresence p = source.readPassengerPresence();
            return p == null ? PassengerPresence.UNAVAILABLE : p;
        } catch (Throwable t) {
            return PassengerPresence.UNAVAILABLE;
        }
    }

    public CabinState readCabinState() {
        try {
            CabinState c = source.readCabinState();
            return c == null ? CabinState.UNAVAILABLE : c;
        } catch (Throwable t) {
            return CabinState.UNAVAILABLE;
        }
    }

    public int readMobisSleepingFast() {
        try {
            return source.readMobisSleepingFast();
        } catch (Throwable t) {
            return -1;
        }
    }

    public void onWake() {
        try {
            source.onWake();
        } catch (Throwable ignored) {
        }
    }

    public boolean isAutoCaptureActive() {
        return System.currentTimeMillis() < autoCaptureUntilMs;
    }

    public synchronized int getChangedCount() {
        return changedSinceRefCount;
    }

    public void poll(boolean fullScan) {
        Map<String, String> snap;
        try {
            snap = source.readSignals(fullScan);
        } catch (Throwable t) {
            return;
        }
        if (snap == null || snap.isEmpty()) {
            return;
        }

        synchronized (this) {
            long wallNow = System.currentTimeMillis();
            boolean firstEver = initialValues.isEmpty();

            for (Map.Entry<String, String> e : snap.entrySet()) {
                String key = e.getKey();
                String val = e.getValue();
                if (key == null || val == null || !isSafeSignalName(key)) {
                    continue;
                }
                if (!initialValues.containsKey(key)) {
                    initialValues.put(key, val);
                }
                if (baselineWallMs > 0L && !baselineValues.containsKey(key)) {
                    baselineValues.put(key, val);
                    List<String> hist = new ArrayList<String>(4);
                    hist.add(val);
                    sinceBaselineHistory.put(key, hist);
                }

                String prev = latestValues.put(key, val);
                if (!firstEver && prev != null && !prev.equals(val)) {
                    Integer sc = sessionChangeCounts.get(key);
                    sessionChangeCounts.put(key, Integer.valueOf(sc == null ? 1 : sc.intValue() + 1));

                    if (baselineWallMs > 0L) {
                        Integer bc = sinceBaselineChanges.get(key);
                        sinceBaselineChanges.put(key, Integer.valueOf(bc == null ? 1 : bc.intValue() + 1));
                        List<String> hist = sinceBaselineHistory.get(key);
                        if (hist == null) {
                            hist = new ArrayList<String>(4);
                            hist.add(prev);
                            sinceBaselineHistory.put(key, hist);
                        }
                        if (hist.isEmpty() || !val.equals(hist.get(hist.size() - 1))) {
                            if (hist.size() < MAX_HISTORY_STEPS) {
                                hist.add(val);
                            } else {
                                hist.set(MAX_HISTORY_STEPS - 1, val);
                            }
                        }
                    }

                    if (!isNoisyCounter(key)) {
                        boolean cand = isOccupantCandidate(key);
                        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(wallNow));
                        pushChangeLine("[" + ts + "] " + key + ": " + prev + " -> " + val
                                + (cand ? "  [CANDIDATE]" : ""));
                    }
                }
            }

            recomputeStatsLocked();
        }
    }

    public String markBaseline(String label) {
        Map<String, String> snap;
        try {
            snap = source.readSignals(true);
        } catch (Throwable t) {
            snap = Collections.emptyMap();
        }
        synchronized (this) {
            long now = System.currentTimeMillis();
            markCounter++;
            baselineWallMs = now;
            autoCaptureUntilMs = now + AUTO_CAPTURE_WINDOW_MS;
            baselineLabel = label == null ? "" : label.trim();

            if (snap != null) {
                for (Map.Entry<String, String> e : snap.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k != null && v != null && isSafeSignalName(k)) {
                        latestValues.put(k, v);
                        if (!initialValues.containsKey(k)) {
                            initialValues.put(k, v);
                        }
                    }
                }
            }

            baselineValues.clear();
            baselineValues.putAll(latestValues);
            sinceBaselineChanges.clear();
            sinceBaselineHistory.clear();
            for (Map.Entry<String, String> e : baselineValues.entrySet()) {
                List<String> hist = new ArrayList<String>(4);
                hist.add(e.getValue());
                sinceBaselineHistory.put(e.getKey(), hist);
            }

            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(now));
            pushChangeLine("[" + ts + "] === BASELINE #" + markCounter
                    + (baselineLabel.isEmpty() ? "" : (" (" + baselineLabel + ")"))
                    + " · " + baselineValues.size() + " signals ===");
            recomputeStatsLocked();
            return "Baseline #" + markCounter + " (" + baselineValues.size() + " signals)";
        }
    }

    public UiState snapshotUi(boolean doPoll, boolean fullScan, String hmgSummary) {
        if (doPoll) {
            poll(fullScan);
        }
        String svcSummary = source.serviceStatusSummary();
        synchronized (this) {
            String banner = modeLabel + " · " + svcSummary + " · " + latestValues.size() + " getters";
            String baseSum;
            if (baselineWallMs > 0L) {
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(baselineWallMs));
                boolean cap = System.currentTimeMillis() < autoCaptureUntilMs;
                baseSum = "Baseline #" + markCounter + " (" + ts + ")"
                        + (cap ? " · 1s walk-around capture ON" : "");
            } else {
                baseSum = "No baseline marked yet (comparing against session start)";
            }

            String changes = buildChangesSectionLocked(32, 26);
            String candidates = buildCandidatesSectionLocked(hmgSummary, 48);
            return new UiState(banner, baseSum, changedSinceRefCount, changes, candidates);
        }
    }

    public String buildFullReport(boolean refreshFullScan, String hmgSummary) {
        if (refreshFullScan) {
            poll(true);
        }
        String svcSummary = source.serviceStatusSummary();
        String buildInfo = source.deviceBuildSummary();
        List<String> sigInv = source.signatureInventory();

        synchronized (this) {
            StringBuilder sb = new StringBuilder(16384);
            sb.append("=== Comfort & Startup — Gen5W Occupant / Belt / Door Signal Inspector ===\n");
            sb.append("Generated       : ")
              .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
              .append('\n');
            sb.append("Probe Mode      : ").append(modeLabel).append('\n');
            sb.append("Head Unit Build : ").append(buildInfo).append('\n');
            sb.append("OEM Services    : ").append(svcSummary).append('\n');
            sb.append("Total Safe Sigs : ").append(latestValues.size()).append('\n');
            if (baselineWallMs > 0L) {
                sb.append("Active Baseline : #").append(markCounter).append(" at ")
                  .append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(baselineWallMs)))
                  .append(" (active diffs=").append(activeDiffCount)
                  .append(", toggled-and-returned=").append(toggledReturnedCount).append(")\n");
            } else {
                sb.append("Active Baseline : none (comparing against session start; diffs=")
                  .append(changedSinceRefCount).append(")\n");
            }
            if (hmgSummary != null && !hmgSummary.trim().isEmpty()) {
                sb.append("HMG-API State   : ").append(hmgSummary.trim()).append('\n');
            }

            // Section 1: Signals changed or toggled since baseline
            sb.append("\n--- 1. Signals Changed or Toggled Since ")
              .append(baselineWallMs > 0L ? ("Baseline #" + markCounter) : "Session Start")
              .append(" ---\n");
            sb.append(buildChangesListLocked(Integer.MAX_VALUE));

            // Section 2: Chronological live event log (newest first)
            sb.append("\n--- 2. Timestamped Signal Transition Log (newest first) ---\n");
            if (changeSize == 0) {
                sb.append("  (no discrete non-noisy signal transitions observed yet)\n");
            } else {
                for (int i = changeSize - 1; i >= 0; i--) {
                    int idx = (changeHead + i) % CHANGE_LOG_CAP;
                    sb.append("  ").append(changeLog[idx]).append('\n');
                }
            }

            // Section 3: All candidate occupant / seat / belt / door / ROA signals
            sb.append("\n--- 3. Candidate Seat / Belt / Door / Occupant / ROA Getters ---\n");
            int candCount = 0;
            for (Map.Entry<String, String> e : latestValues.entrySet()) {
                String k = e.getKey();
                if (isOccupantCandidate(k)) {
                    candCount++;
                    sb.append("  ").append(k).append(" = ").append(e.getValue());
                    Integer sc = sessionChangeCounts.get(k);
                    if (sc != null && sc.intValue() > 0) {
                        sb.append("  [changes=").append(sc.intValue()).append(']');
                    }
                    sb.append('\n');
                }
            }
            if (candCount == 0) {
                sb.append("  (none matched candidate keywords)\n");
            }

            // Section 4: Parameterized getter & listener callback signature inventory
            sb.append("\n--- 4. Reflected Parameterized Getters & Listener Callback Signatures (")
              .append(sigInv == null ? 0 : sigInv.size()).append(") ---\n");
            if (sigInv == null || sigInv.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (int i = 0; i < sigInv.size(); i++) {
                    sb.append("  ").append(sigInv.get(i)).append('\n');
                }
            }

            // Section 5: Complete safe getter dump across all attached services
            sb.append("\n--- 5. All Safe Zero-Arg & HEVFlag Signals (")
              .append(latestValues.size()).append(") ---\n");
            for (Map.Entry<String, String> e : latestValues.entrySet()) {
                String k = e.getKey();
                String cur = e.getValue();
                Integer sc = sessionChangeCounts.get(k);
                sb.append("  ").append(k).append(" = ").append(cur);
                if ("65535".equals(cur)) {
                    sb.append(" (0xFFFF invalid)");
                }
                if (sc != null && sc.intValue() > 0) {
                    sb.append("  [changes=").append(sc.intValue()).append(']');
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    private void recomputeStatsLocked() {
        Map<String, String> ref = baselineWallMs > 0L ? baselineValues : initialValues;
        int active = 0;
        int toggled = 0;

        for (Map.Entry<String, String> e : latestValues.entrySet()) {
            String k = e.getKey();
            if (isNoisyCounter(k)) {
                continue;
            }
            String cur = e.getValue();
            String base = ref.get(k);
            boolean isDiffNow = (base != null && !base.equals(cur));
            if (isDiffNow) {
                active++;
            } else if (baselineWallMs > 0L) {
                Integer bc = sinceBaselineChanges.get(k);
                if (bc != null && bc.intValue() > 0) {
                    toggled++;
                }
            }
        }
        this.activeDiffCount = active;
        this.toggledReturnedCount = toggled;
        this.changedSinceRefCount = active + toggled;
    }

    private String buildChangesSectionLocked(int maxDiffLines, int maxLogLines) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(buildChangesListLocked(maxDiffLines));
        sb.append("\nRecent Transitions:\n");
        if (changeSize == 0) {
            sb.append("  (waiting for door / seat / belt transitions...)\n");
        } else {
            int shown = 0;
            for (int i = changeSize - 1; i >= 0 && shown < maxLogLines; i--, shown++) {
                int idx = (changeHead + i) % CHANGE_LOG_CAP;
                sb.append("  ").append(changeLog[idx]).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String buildChangesListLocked(int limit) {
        Map<String, String> ref = baselineWallMs > 0L ? baselineValues : initialValues;
        List<String> priorityLines = new ArrayList<String>();
        List<String> otherLines = new ArrayList<String>();

        for (Map.Entry<String, String> e : latestValues.entrySet()) {
            String k = e.getKey();
            if (isNoisyCounter(k)) {
                continue;
            }
            String cur = e.getValue();
            String base = ref.get(k);
            boolean activeDiff = (base != null && !base.equals(cur));
            Integer bc = baselineWallMs > 0L ? sinceBaselineChanges.get(k) : sessionChangeCounts.get(k);
            int chgCount = bc == null ? 0 : bc.intValue();
            boolean toggledBack = (!activeDiff && baselineWallMs > 0L && chgCount > 0);

            if (!activeDiff && !toggledBack) {
                continue;
            }

            boolean cand = isOccupantCandidate(k);
            StringBuilder line = new StringBuilder(120);
            line.append(cand ? "★ " : "  ").append(k).append(" : ");
            List<String> hist = baselineWallMs > 0L ? sinceBaselineHistory.get(k) : null;
            if (hist != null && hist.size() > 1) {
                for (int i = 0; i < hist.size(); i++) {
                    if (i > 0) line.append(" -> ");
                    line.append(hist.get(i));
                }
            } else {
                line.append(base).append(" -> ").append(cur);
            }
            line.append(activeDiff ? "  [ACTIVE]" : "  [TOGGLED]");
            if (chgCount > 1) {
                line.append(" (x").append(chgCount).append(')');
            }

            if (cand) {
                priorityLines.add(line.toString());
            } else {
                otherLines.add(line.toString());
            }
        }

        if (priorityLines.isEmpty() && otherLines.isEmpty()) {
            return "  (no non-noisy signals changed yet)\n";
        }

        StringBuilder out = new StringBuilder(1024);
        int count = 0;
        for (int i = 0; i < priorityLines.size() && count < limit; i++, count++) {
            out.append(priorityLines.get(i)).append('\n');
        }
        for (int i = 0; i < otherLines.size() && count < limit; i++, count++) {
            out.append(otherLines.get(i)).append('\n');
        }
        int total = priorityLines.size() + otherLines.size();
        if (total > count) {
            out.append("  ... +").append(total - count).append(" more (Export USB for full list)\n");
        }
        return out.toString();
    }

    private String buildCandidatesSectionLocked(String hmgSummary, int maxLines) {
        StringBuilder sb = new StringBuilder(2048);
        if (hmgSummary != null && !hmgSummary.trim().isEmpty()) {
            sb.append("HMG-API:\n  ").append(hmgSummary.trim()).append("\n\n");
        }
        sb.append("OEM Candidate Getters:\n");
        int shown = 0;
        int totalCand = 0;
        for (Map.Entry<String, String> e : latestValues.entrySet()) {
            String k = e.getKey();
            if (!isOccupantCandidate(k)) {
                continue;
            }
            totalCand++;
            if (shown < maxLines) {
                shown++;
                sb.append("  ").append(k).append(" = ").append(e.getValue());
                Integer sc = sessionChangeCounts.get(k);
                if (sc != null && sc.intValue() > 0) {
                    sb.append("  [chg=").append(sc.intValue()).append(']');
                }
                sb.append('\n');
            }
        }
        if (totalCand == 0) {
            if (latestValues.isEmpty()) {
                sb.append("  (no OEM services attached on this host)\n");
            } else {
                sb.append("  (0 keyword matches; full scan watching all ")
                  .append(latestValues.size()).append(" getters)\n");
            }
        } else if (totalCand > shown) {
            sb.append("  ... +").append(totalCand - shown)
              .append(" more in USB export (").append(totalCand).append(" total)\n");
        }
        return sb.toString().trim();
    }

    private void pushChangeLine(String line) {
        if (changeSize < CHANGE_LOG_CAP) {
            int idx = (changeHead + changeSize) % CHANGE_LOG_CAP;
            changeLog[idx] = line;
            changeSize++;
        } else {
            changeLog[changeHead] = line;
            changeHead = (changeHead + 1) % CHANGE_LOG_CAP;
        }
    }

    /**
     * Splits camelCase, PascalCase, acronyms, digits, and punctuation into lowercase tokens:
     * e.g., {@code "IsCarMoving"} -> {@code ["is", "car", "moving"]},
     * {@code "getVIN2"} -> {@code ["get", "vin", "2"]},
     * {@code "BCM.getAstSeatBelt"} -> {@code ["bcm", "get", "ast", "seat", "belt"]}.
     */
    public static List<String> tokenizeIdentifier(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<String>(8);
        StringBuilder cur = new StringBuilder(16);
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString().toLowerCase(Locale.US));
                    cur.setLength(0);
                }
                continue;
            }
            if (cur.length() > 0) {
                char prev = cur.charAt(cur.length() - 1);
                boolean prevDigit = Character.isDigit(prev);
                boolean curDigit = Character.isDigit(c);
                boolean split = (prevDigit != curDigit);
                if (!split && Character.isLowerCase(prev) && Character.isUpperCase(c)) {
                    split = true;
                } else if (!split && Character.isUpperCase(prev) && Character.isUpperCase(c)
                        && i + 1 < len && Character.isLowerCase(text.charAt(i + 1))) {
                    split = true;
                }
                if (split) {
                    tokens.add(cur.toString().toLowerCase(Locale.US));
                    cur.setLength(0);
                }
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString().toLowerCase(Locale.US));
        }
        return tokens;
    }

    public static boolean isSafeSignalName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        for (int i = 0; i < BLOCKED_SUBSTRINGS.length; i++) {
            if (lower.contains(BLOCKED_SUBSTRINGS[i])) {
                return false;
            }
        }
        List<String> words = tokenizeIdentifier(name);
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            for (int j = 0; j < BLOCKED_WORDS.length; j++) {
                if (BLOCKED_WORDS[j].equals(w)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isSafeGetterMethod(Method m) {
        if (m == null || m.getParameterTypes().length != 0) {
            return false;
        }
        if (!isGetterPrefix(m.getName()) || !isSafeSignalName(m.getName())) {
            return false;
        }
        Class<?> rt = m.getReturnType();
        return isSafePrimitiveType(rt)
                || rt == int[].class
                || rt == boolean[].class;
    }

    static boolean isSafeStatusObjectGetter(Method m) {
        if (m == null || m.getParameterTypes().length != 0) {
            return false;
        }
        if (!isGetterPrefix(m.getName()) || !isSafeSignalName(m.getName())) {
            return false;
        }
        Class<?> rt = m.getReturnType();
        if (rt == null || rt.isPrimitive() || rt.isArray() || rt.isInterface()) {
            return false;
        }
        String clsName = rt.getName();
        return clsName.startsWith("com.hkmc.") && isSafeSignalName(rt.getSimpleName());
    }

    private static boolean isGetterPrefix(String name) {
        if (name == null) return false;
        if ("getClass".equals(name) || "asBinder".equals(name)
                || "getInterfaceDescriptor".equals(name) || "hashCode".equals(name)) {
            return false;
        }
        return ((name.startsWith("get") || name.startsWith("Get")) && name.length() > 3)
                || ((name.startsWith("is") || name.startsWith("Is")) && name.length() > 2);
    }

    static boolean isSafePrimitiveType(Class<?> rt) {
        return rt == int.class
                || rt == boolean.class
                || rt == byte.class
                || rt == short.class
                || rt == long.class
                || rt == float.class
                || rt == double.class;
    }

    public static boolean isOccupantCandidate(String key) {
        if (matchesAnyToken(key, OCCUPANT_PRIORITY_TOKENS)) {
            return true;
        }
        List<String> words = tokenizeIdentifier(key);
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            for (int j = 0; j < OCCUPANT_PRIORITY_WORDS.length; j++) {
                if (OCCUPANT_PRIORITY_WORDS[j].equals(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean matchesAnyToken(String text, String[] tokens) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.US);
        for (int i = 0; i < tokens.length; i++) {
            if (lower.contains(tokens[i])) {
                return true;
            }
        }
        return false;
    }

    static boolean isNoisyCounter(String key) {
        return matchesAnyToken(key, NOISY_COUNTER_TOKENS);
    }

    static String formatValue(Object v) {
        if (v == null) return null;
        if (v instanceof Byte) {
            return String.valueOf(((Byte) v).intValue() & 0xFF);
        }
        if (v instanceof Float) {
            float f = ((Float) v).floatValue();
            if (Float.isNaN(f) || Float.isInfinite(f)) return "NaN";
            return String.format(Locale.US, "%.2f", Float.valueOf(f));
        }
        if (v instanceof Double) {
            double d = ((Double) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "NaN";
            return String.format(Locale.US, "%.2f", Double.valueOf(d));
        }
        if (v instanceof int[]) {
            int[] a = (int[]) v;
            int n = Math.min(a.length, 16);
            StringBuilder sb = new StringBuilder(32);
            sb.append('[');
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            if (a.length > n) sb.append(",...");
            sb.append(']');
            return sb.toString();
        }
        if (v instanceof boolean[]) {
            boolean[] a = (boolean[]) v;
            int n = Math.min(a.length, 16);
            StringBuilder sb = new StringBuilder(32);
            sb.append('[');
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i] ? '1' : '0');
            }
            if (a.length > n) sb.append(",...");
            sb.append(']');
            return sb.toString();
        }
        return String.valueOf(v);
    }

    // ---- Loopback Server & Client (UID 1000 WelcomeDaemon <-> Comfort App) ----

    private static void writeFrame(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes("UTF-8");
        out.writeInt(b.length);
        if (b.length > 0) {
            out.write(b);
        }
    }

    private static String readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("invalid frame length: " + len);
        }
        if (len == 0) {
            return "";
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, "UTF-8");
    }

    public static final class LoopbackServer {
        private final OccupantSignalProbe probe;
        private final ServerSocket serverSocket;
        private volatile boolean running = true;

        public LoopbackServer(OccupantSignalProbe probe, int port) throws IOException {
            this.probe = probe;
            this.serverSocket = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        }

        public int getLocalPort() {
            return serverSocket.getLocalPort();
        }

        public void start() {
            Thread acceptThread = new Thread("ComfortProbeServer") {
                @Override
                public void run() {
                    acceptLoop();
                }
            };
            acceptThread.setDaemon(true);
            acceptThread.start();

            Thread captureThread = new Thread("ComfortProbeAutoCapture") {
                @Override
                public void run() {
                    while (running) {
                        try {
                            Thread.sleep(1000L);
                            if (running && probe.isAutoCaptureActive()) {
                                probe.poll(true);
                            }
                        } catch (InterruptedException e) {
                            break;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };
            captureThread.setDaemon(true);
            captureThread.start();
        }

        public void stop() {
            running = false;
            try {
                serverSocket.close();
            } catch (Throwable ignored) {}
        }

        private void acceptLoop() {
            while (running) {
                Socket client = null;
                try {
                    client = serverSocket.accept();
                    InetAddress addr = client.getInetAddress();
                    if (addr == null || !addr.isLoopbackAddress()) {
                        client.close();
                        continue;
                    }
                    client.setSoTimeout(4000);
                    handleClient(client);
                } catch (Throwable t) {
                    if (!running) break;
                } finally {
                    if (client != null) {
                        try { client.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }

        private void handleClient(Socket client) throws IOException {
            DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream(), 1024));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(client.getOutputStream(), 4096));
            String cmd = readFrame(in);
            if ("OCCUPANCY".equals(cmd)) {
                PassengerPresence p = probe.readPassengerPresence();
                writeFrame(out, p.encode());
                out.flush();
            } else if ("CABIN_STATE".equals(cmd)) {
                CabinState c = probe.readCabinState();
                writeFrame(out, c.encode());
                out.flush();
            } else if ("WAKE".equals(cmd)) {
                probe.onWake();
                writeFrame(out, "1");
                out.flush();
            } else if ("UI_POLL".equals(cmd)) {
                boolean full = "1".equals(readFrame(in));
                String hmgSummary = readFrame(in);
                UiState ui = probe.snapshotUi(true, full, hmgSummary);
                writeUiState(out, ui);
            } else if ("MARK".equals(cmd)) {
                String label = readFrame(in);
                String hmgSummary = readFrame(in);
                probe.markBaseline(label);
                UiState ui = probe.snapshotUi(false, false, hmgSummary);
                writeUiState(out, ui);
            } else if ("EXPORT".equals(cmd)) {
                String joinedFolders = readFrame(in);
                String hmgSummary = readFrame(in);
                List<String> extra = new ArrayList<String>();
                if (!joinedFolders.isEmpty()) {
                    String[] parts = joinedFolders.split("\n");
                    for (int i = 0; i < parts.length; i++) {
                        if (!parts[i].trim().isEmpty()) {
                            extra.add(parts[i].trim());
                        }
                    }
                }
                String report = probe.buildFullReport(true, hmgSummary);
                ComfortUsbExporter.ExportResult res = ComfortUsbExporter.exportReport(report, extra);
                writeFrame(out, res.ok ? "1" : "0");
                writeFrame(out, res.reportPath);
                writeFrame(out, res.errorMessage);
                writeFrame(out, report);
                out.flush();
            }
        }

        private static void writeUiState(DataOutputStream out, UiState ui) throws IOException {
            writeFrame(out, ui.sourceBanner);
            writeFrame(out, ui.baselineSummary);
            writeFrame(out, String.valueOf(ui.changedCount));
            writeFrame(out, ui.changesText);
            writeFrame(out, ui.candidatesText);
            out.flush();
        }
    }

    public static final class LoopbackClient {
        private final int port;
        private final OccupantSignalProbe fallbackProbe;

        public LoopbackClient(int port, OccupantSignalProbe fallbackProbe) {
            this.port = port;
            this.fallbackProbe = fallbackProbe == null
                    ? new OccupantSignalProbe(new ReflectionGen5wSignalSource(), "app fallback")
                    : fallbackProbe;
        }

        public boolean pingDaemon() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 350);
                return true;
            } catch (IOException e) {
                return false;
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public PassengerPresence queryPassengerPresence() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                s.setSoTimeout(1200);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 128));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 256));
                writeFrame(out, "OCCUPANCY");
                out.flush();
                return PassengerPresence.decode(readFrame(in));
            } catch (Throwable ignored) {
                return fallbackProbe.readPassengerPresence();
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public CabinState queryCabinState() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 350);
                s.setSoTimeout(1600);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 128));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 512));
                writeFrame(out, "CABIN_STATE");
                out.flush();
                return CabinState.decode(readFrame(in));
            } catch (Throwable ignored) {
                return fallbackProbe.readCabinState();
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public void notifyWake() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                s.setSoTimeout(1000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64));
                writeFrame(out, "WAKE");
                out.flush();
                readFrame(in);
            } catch (Throwable ignored) {
                fallbackProbe.onWake();
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public UiState pollUi(boolean fullScan, String hmgSummary) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 450);
                s.setSoTimeout(4500);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 512));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 4096));
                writeFrame(out, "UI_POLL");
                writeFrame(out, fullScan ? "1" : "0");
                writeFrame(out, hmgSummary);
                out.flush();
                return readUiState(in);
            } catch (Throwable ignored) {
                return fallbackProbe.snapshotUi(true, fullScan, hmgSummary);
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public UiState markBaseline(String label, String hmgSummary) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 450);
                s.setSoTimeout(5000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 512));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 4096));
                writeFrame(out, "MARK");
                writeFrame(out, label);
                writeFrame(out, hmgSummary);
                out.flush();
                return readUiState(in);
            } catch (Throwable ignored) {
                fallbackProbe.markBaseline(label);
                return fallbackProbe.snapshotUi(false, false, hmgSummary);
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public ComfortUsbExporter.ExportResult exportToUsb(List<String> extraUsbFolders, String hmgSummary) {
            StringBuilder joined = new StringBuilder();
            if (extraUsbFolders != null) {
                for (int i = 0; i < extraUsbFolders.size(); i++) {
                    String f = extraUsbFolders.get(i);
                    if (f != null && !f.trim().isEmpty()) {
                        if (joined.length() > 0) joined.append('\n');
                        joined.append(f.trim());
                    }
                }
            }

            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 450);
                s.setSoTimeout(6000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1024));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 8192));
                writeFrame(out, "EXPORT");
                writeFrame(out, joined.toString());
                writeFrame(out, hmgSummary);
                out.flush();

                boolean ok = "1".equals(readFrame(in));
                String path = readFrame(in);
                String err = readFrame(in);
                String report = readFrame(in);
                if (ok && path != null && !path.isEmpty()) {
                    return new ComfortUsbExporter.ExportResult(true, path, null);
                }
                // Try app-side USB write as backup using the daemon's UID-1000 generated report
                if (report != null && !report.isEmpty()) {
                    ComfortUsbExporter.ExportResult retry = ComfortUsbExporter.exportReport(report, extraUsbFolders);
                    if (retry.ok) {
                        return retry;
                    }
                }
                return new ComfortUsbExporter.ExportResult(false, null, err);
            } catch (Throwable ignored) {
                String report = fallbackProbe.buildFullReport(true, hmgSummary);
                return ComfortUsbExporter.exportReport(report, extraUsbFolders);
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        private static UiState readUiState(DataInputStream in) throws IOException {
            String banner = readFrame(in);
            String baseSum = readFrame(in);
            int chg = 0;
            try {
                chg = Integer.parseInt(readFrame(in).trim());
            } catch (NumberFormatException ignored) {}
            String changes = readFrame(in);
            String candidates = readFrame(in);
            return new UiState(banner, baseSum, chg, changes, candidates);
        }
    }

    // ---- Reflection-Backed Gen5W OEM Service Signal Source ----

    static final class ReflectionGen5wSignalSource implements SignalSource {

        private static final String HCAN_JAR = "/system/framework/com.hkmc.ext.hcan.jar";

        private final PushTelemetrySource pushSource;
        private boolean pushAwake = true;

        private boolean initialized;
        private boolean coreServicesReady;
        private long lastInitAttemptMs;
        private Object hevService;
        private Method getHevStatusMethod;
        private final Map<String, Integer> hevFlags = new LinkedHashMap<String, Integer>();

        private final List<BoundGetter> fastGetters = new ArrayList<BoundGetter>();
        private final List<BoundGetter> allGetters = new ArrayList<BoundGetter>();
        private final List<BoundStatusObject> statusObjects = new ArrayList<BoundStatusObject>();
        private final List<String> signatures = new ArrayList<String>();

        private BoundGetter bgHscanOccupancy;
        private BoundGetter bgSysClassStatus;
        private BoundGetter bgAsstBelt;
        private BoundGetter bgPassStOcc;
        private BoundGetter bgBcmIgnStatus;
        private BoundGetter bgDatcIgn3Status;
        private BoundGetter bgBlackScreenShown;
        private BoundGetter bgAvPowerOff;
        private BoundGetter bgDisplayOffMode;
        private BoundGetter bgBcmDrvSeatOpt;
        private BoundGetter bgBcmAstSeatOpt;
        private BoundGetter bgBcmRlSeatOpt;
        private BoundGetter bgBcmRrSeatOpt;
        private BoundGetter bgUsmDrvSeatOpt;
        private BoundGetter bgUsmAstSeatOpt;
        private BoundGetter bgUsmRlSeatOpt;
        private BoundGetter bgUsmRrSeatOpt;

        private String statusText = "uninitialized";
        private String buildText = "unknown";

        ReflectionGen5wSignalSource() {
            this(null);
        }

        ReflectionGen5wSignalSource(PushTelemetrySource pushSource) {
            this.pushSource = pushSource;
        }

        @Override
        public synchronized String serviceStatusSummary() {
            ensureInitialized();
            return statusText;
        }

        @Override
        public synchronized String deviceBuildSummary() {
            ensureInitialized();
            return buildText;
        }

        @Override
        public synchronized List<String> signatureInventory() {
            ensureInitialized();
            return new ArrayList<String>(signatures);
        }

        @Override
        public synchronized PassengerPresence readPassengerPresence() {
            ensureInitialized();
            int occ = invokeIntGetter(bgHscanOccupancy, -1);
            int cls = invokeIntGetter(bgSysClassStatus, -1);
            int belt = invokeIntGetter(bgAsstBelt, -1);
            int passOcc = invokeIntGetter(bgPassStOcc, -1);
            return PassengerPresence.fromRaw(occ, cls, belt, passOcc);
        }

        @Override
        public CabinState readCabinState() {
            PassengerPresence passenger;
            SeatCapabilities seatCaps;
            int ignOn;
            int ign3On;
            int avAwake;
            int mobisSleeping;
            boolean sleepStandby;
            boolean unregisterSleep = false;
            synchronized (this) {
                ensureInitialized();
                int occ = invokeIntGetter(bgHscanOccupancy, -1);
                int cls = invokeIntGetter(bgSysClassStatus, -1);
                int belt = invokeIntGetter(bgAsstBelt, -1);
                int passOcc = invokeIntGetter(bgPassStOcc, -1);
                passenger = PassengerPresence.fromRaw(occ, cls, belt, passOcc);

                int drvOpt = SeatCapabilities.pickOption(
                        invokeIntGetter(bgBcmDrvSeatOpt, -1),
                        invokeIntGetter(bgUsmDrvSeatOpt, -1));
                int astOpt = SeatCapabilities.pickOption(
                        invokeIntGetter(bgBcmAstSeatOpt, -1),
                        invokeIntGetter(bgUsmAstSeatOpt, -1));
                int rlOpt = SeatCapabilities.pickOption(
                        invokeIntGetter(bgBcmRlSeatOpt, -1),
                        invokeIntGetter(bgUsmRlSeatOpt, -1));
                int rrOpt = SeatCapabilities.pickOption(
                        invokeIntGetter(bgBcmRrSeatOpt, -1),
                        invokeIntGetter(bgUsmRrSeatOpt, -1));
                seatCaps = SeatCapabilities.fromRawOptions(drvOpt, astOpt, rlOpt, rrOpt);

                ignOn = invokeIntGetter(bgBcmIgnStatus, -1);
                ign3On = invokeIntGetter(bgDatcIgn3Status, -1);
                int black = invokeIntGetter(bgBlackScreenShown, -1);
                int avOff = invokeIntGetter(bgAvPowerOff, -1);
                int dispOff = invokeIntGetter(bgDisplayOffMode, -1);
                avAwake = CabinState.computeAvAwake(black, avOff, dispOff);
                mobisSleeping = CabinState.computeMobisSleeping(black, avOff);

                sleepStandby = (mobisSleeping == 1 && ignOn != 1);
                if (sleepStandby && pushAwake) {
                    pushAwake = false;
                    unregisterSleep = true;
                } else if (!sleepStandby && !pushAwake) {
                    pushAwake = true;
                }
            }

            String tempF = "-";
            String tempC = "-";
            String wheel = "-";
            boolean sentinel = false;
            if (pushSource != null) {
                if (sleepStandby) {
                    if (unregisterSleep) {
                        pushSource.onWake();
                    }
                } else {
                    pushSource.ensure(ignOn == 1);
                    tempF = pushSource.outsideTempF();
                    tempC = pushSource.outsideTempC();
                    wheel = pushSource.wheelHeat();
                    sentinel = pushSource.isDatcSentinel();
                }
            }
            return new CabinState(
                    passenger, tempF, tempC, wheel,
                    ignOn, ign3On, avAwake, mobisSleeping, sentinel, seatCaps);
        }

        @Override
        public synchronized int readMobisSleepingFast() {
            ensureInitialized();
            int black = invokeIntGetter(bgBlackScreenShown, -1);
            int avOff = invokeIntGetter(bgAvPowerOff, -1);
            return CabinState.computeMobisSleeping(black, avOff);
        }

        @Override
        public void onWake() {
            synchronized (this) {
                pushAwake = true;
            }
            if (pushSource != null) {
                pushSource.onWake();
            }
        }

        private static int invokeIntGetter(BoundGetter bg, int fallback) {
            if (bg == null) return fallback;
            try {
                Object v = bg.method.invoke(bg.target);
                if (v instanceof Number) {
                    return ((Number) v).intValue();
                }
                if (v instanceof Boolean) {
                    return ((Boolean) v).booleanValue() ? 1 : 0;
                }
            } catch (Throwable ignored) {
            }
            return fallback;
        }

        @Override
        public synchronized Map<String, String> readSignals(boolean fullScan) {
            ensureInitialized();
            Map<String, String> out = new LinkedHashMap<String, String>();

            if (hevService != null && getHevStatusMethod != null) {
                for (Map.Entry<String, Integer> f : hevFlags.entrySet()) {
                    try {
                        Object v = getHevStatusMethod.invoke(hevService, f.getValue());
                        if (v != null) {
                            out.put(f.getKey(), formatValue(v));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            List<BoundGetter> list = fullScan ? allGetters : fastGetters;
            for (int i = 0; i < list.size(); i++) {
                BoundGetter bg = list.get(i);
                try {
                    Object v = bg.method.invoke(bg.target);
                    if (v != null) {
                        out.put(bg.key, formatValue(v));
                    }
                } catch (Throwable ignored) {
                }
            }

            for (int i = 0; i < statusObjects.size(); i++) {
                BoundStatusObject bso = statusObjects.get(i);
                if (!fullScan && !bso.candidate) {
                    continue;
                }
                try {
                    Object st = bso.getter.invoke(bso.target);
                    if (st == null) continue;
                    for (int fIdx = 0; fIdx < bso.subFields.size(); fIdx++) {
                        Field f = bso.subFields.get(fIdx);
                        try {
                            Object fv = f.get(st);
                            if (fv != null) {
                                out.put(bso.prefix + "." + f.getName(), formatValue(fv));
                            }
                        } catch (Throwable ignored) {}
                    }
                    for (int mIdx = 0; mIdx < bso.subMethods.size(); mIdx++) {
                        Method sm = bso.subMethods.get(mIdx);
                        try {
                            Object mv = sm.invoke(st);
                            if (mv != null) {
                                out.put(bso.prefix + "." + sm.getName(), formatValue(mv));
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {
                }
            }
            return out;
        }

        private void ensureInitialized() {
            long now = System.currentTimeMillis();
            if (initialized && (coreServicesReady || now - lastInitAttemptMs < 5000L)) {
                return;
            }
            initialized = true;
            lastInitAttemptMs = now;
            hevService = null;
            getHevStatusMethod = null;
            hevFlags.clear();
            fastGetters.clear();
            allGetters.clear();
            statusObjects.clear();
            signatures.clear();
            bgHscanOccupancy = null;
            bgSysClassStatus = null;
            bgAsstBelt = null;
            bgPassStOcc = null;
            bgBcmIgnStatus = null;
            bgDatcIgn3Status = null;
            bgBlackScreenShown = null;
            bgAvPowerOff = null;
            bgDisplayOffMode = null;
            bgBcmDrvSeatOpt = null;
            bgBcmAstSeatOpt = null;
            bgBcmRlSeatOpt = null;
            bgBcmRrSeatOpt = null;
            bgUsmDrvSeatOpt = null;
            bgUsmAstSeatOpt = null;
            bgUsmRlSeatOpt = null;
            bgUsmRrSeatOpt = null;
            buildText = readSanitizedBuildInfo();

            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Method getService = smClass.getMethod("getService", String.class);
                Class<?> ibinderClass = Class.forName("android.os.IBinder");

                Class<?> carSvc = null;
                try {
                    carSvc = Class.forName("com.hkmc.Service");
                } catch (Throwable ignored) {}

                Set<String> boundNames = new HashSet<String>();
                StringBuilder summary = new StringBuilder(160);

                // 1. BCM (primary seat state, doors, locks, ROA)
                int bcmCnt = attachStandardService(getService, ibinderClass, carSvc, boundNames,
                        "BCM", "BCM_SERVICE", "com.hkmc.micom.bcm",
                        "com.hkmc.bcm.IBCMService", "com.hkmc.bcm.IBCMListener", null);
                appendSvcStatus(summary, "BCM", bcmCnt);

                // 2. HSCAN (telematics CAN: doors, seatbelts, trunk/hood, charging)
                int hscanCnt = -1;
                ClassLoader hcanLoader = ReflectionGen5wSignalSource.class.getClassLoader();
                try {
                    File jar = new File(HCAN_JAR);
                    if (jar.isFile()) {
                        Class<?> pcl = Class.forName("dalvik.system.PathClassLoader");
                        hcanLoader = (ClassLoader) pcl.getConstructor(String.class, ClassLoader.class)
                                .newInstance(HCAN_JAR, hcanLoader);
                    }
                    Class<?> adapter = Class.forName(
                            "com.hkmc.telematics.hscan.tm_interface.TM_HSCANAdapter", true, hcanLoader);
                    hscanCnt = attachStandardService(getService, ibinderClass, adapter, boundNames,
                            "HSCAN", "TM_HSCAN_SERVICE", "tm_hscan_service",
                            "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService", null, hcanLoader);
                } catch (Throwable ignored) {}
                appendSvcStatus(summary, "HSCAN", hscanCnt);

                // 3. SYSTEM (gear, EPB, ignition/reverse/cabin flags)
                int sysCnt = attachStandardService(getService, ibinderClass, carSvc, boundNames,
                        "SYS", "SYSTEM_SERVICE", "com.hkmc.micom.system",
                        "com.hkmc.micom.ISystemService", "com.hkmc.micom.ISystemListener", null);
                appendSvcStatus(summary, "SYS", sysCnt);

                // 4. USM (user settings, seat heat/vent options, ROA settings)
                int usmCnt = attachStandardService(getService, ibinderClass, carSvc, boundNames,
                        "USM", "USM_SERVICE", "com.hkmc.micom.usm",
                        "com.hkmc.usm.IUSMService", "com.hkmc.usm.IUSMListener", null);
                appendSvcStatus(summary, "USM", usmCnt);

                // 5. CLUSTER (warning lamps / seatbelt / airbag telltales)
                int cluCnt = attachStandardService(getService, ibinderClass, carSvc, boundNames,
                        "CLU", "CLUSTER_SERVICE", "com.hkmc.micom.cluster",
                        "com.hkmc.cluster.IClusterService", "com.hkmc.cluster.INavigationClusterListener", null);
                appendSvcStatus(summary, "CLU", cluCnt);

                // 6. DATC (dual-zone / passenger climate / ADS)
                int datcCnt = attachStandardService(getService, ibinderClass, carSvc, boundNames,
                        "DATC", "DATC_SERVICE", "com.hkmc.micom.datc",
                        "com.hkmc.datc.IDATCService", "com.hkmc.datc.IDATCListener", null);
                recordStatusClassSignatures("DatcStatus", "com.hkmc.datc.DatcStatus", null);
                appendSvcStatus(summary, "DATC", datcCnt);

                // 7. HEV + HEVManager$HEVFlag constants
                String hevSvcName = resolveServiceName(carSvc, "HEV_SERVICE", "com.hkmc.micom.hev");
                boundNames.add(hevSvcName);
                hevService = bindStub(getService, ibinderClass, hevSvcName, "com.hkmc.hev.IHEVService$Stub", null);
                int hevCnt = -1;
                if (hevService != null) {
                    hevCnt = 0;
                    try {
                        Class<?> hevIface = Class.forName("com.hkmc.hev.IHEVService");
                        getHevStatusMethod = hevIface.getMethod("getHEVStatus", int.class);
                        hevCnt += registerInterfaceGetters("HEV", hevService, hevIface);
                    } catch (Throwable ignored) {}
                    try {
                        Class<?> flagCls = Class.forName("com.hkmc.hev.HEVManager$HEVFlag");
                        Field[] fields = flagCls.getFields();
                        Map<String, Integer> sorted = new TreeMap<String, Integer>();
                        for (int i = 0; i < fields.length; i++) {
                            Field f = fields[i];
                            int mod = f.getModifiers();
                            if (Modifier.isStatic(mod) && f.getType() == int.class && isSafeSignalName(f.getName())) {
                                sorted.put("HEVFlag." + f.getName() + "(" + f.getInt(null) + ")",
                                        Integer.valueOf(f.getInt(null)));
                            }
                        }
                        hevFlags.putAll(sorted);
                        hevCnt += hevFlags.size();
                    } catch (Throwable ignored) {}
                }
                appendSvcStatus(summary, "HEV", hevCnt);

                // 8. Secondary cabin services (VR, PGS, REG) + safe auto-discovered com.hkmc.Service binders
                attachStandardService(getService, ibinderClass, carSvc, boundNames,
                        "VR", "VR_SERVICE", "com.hkmc.micom.vr", "com.hkmc.vr.IVRService", null, null);
                attachStandardService(getService, ibinderClass, carSvc, boundNames,
                        "PGS", "PGS_SERVICE", "com.hkmc.micom.pgs", "com.hkmc.pgs.IPgsService", null, null);
                attachStandardService(getService, ibinderClass, carSvc, boundNames,
                        "REG", "REGULATION_SERVICE", "com.hkmc.regulation",
                        "com.hkmc.regulation.IRegulationManager", null, null);

                discoverExtraHkmcServices(getService, ibinderClass, carSvc, boundNames, summary);

                coreServicesReady = (bcmCnt >= 0 && hscanCnt >= 0);
                Collections.sort(signatures);
                statusText = summary.toString().trim();
            } catch (Throwable t) {
                statusText = "off-car / unavailable (" + t.getClass().getSimpleName() + ")";
            }
        }

        private int attachStandardService(
                Method getService,
                Class<?> ibinderClass,
                Class<?> nameHolder,
                Set<String> boundNames,
                String prefix,
                String fieldName,
                String fallbackSvcName,
                String ifaceName,
                String listenerName,
                ClassLoader loader) {
            String svcName = resolveServiceName(nameHolder, fieldName, fallbackSvcName);
            boundNames.add(svcName);
            if (listenerName != null) {
                recordListenerSignatures(prefix + "Listener", listenerName, loader);
            }
            Object proxy = bindStub(getService, ibinderClass, svcName, ifaceName + "$Stub", loader);
            if (proxy == null) {
                return -1;
            }
            try {
                Class<?> iface = loader == null
                        ? Class.forName(ifaceName)
                        : Class.forName(ifaceName, true, loader);
                return registerInterfaceGetters(prefix, proxy, iface);
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private void discoverExtraHkmcServices(
                Method getService,
                Class<?> ibinderClass,
                Class<?> carSvc,
                Set<String> boundNames,
                StringBuilder summary) {
            if (carSvc == null) return;
            try {
                Method getDesc = ibinderClass.getMethod("getInterfaceDescriptor");
                Field[] fields = carSvc.getFields();
                for (int i = 0; i < fields.length; i++) {
                    Field f = fields[i];
                    int mod = f.getModifiers();
                    if (!Modifier.isStatic(mod) || f.getType() != String.class) continue;
                    String fName = f.getName();
                    if (!isSafeSignalName(fName) || matchesAnyToken(fName, BLOCKED_SERVICE_TOKENS)) continue;
                    Object rawVal = f.get(null);
                    if (!(rawVal instanceof String)) continue;
                    String svcName = ((String) rawVal).trim();
                    if (svcName.isEmpty() || boundNames.contains(svcName)) continue;
                    if (!isSafeSignalName(svcName) || matchesAnyToken(svcName, BLOCKED_SERVICE_TOKENS)) continue;

                    boundNames.add(svcName);
                    try {
                        Object binder = getService.invoke(null, svcName);
                        if (binder == null) continue;
                        Object descObj = getDesc.invoke(binder);
                        if (!(descObj instanceof String)) continue;
                        String desc = ((String) descObj).trim();
                        if (!desc.startsWith("com.hkmc.") || !isSafeSignalName(desc)
                                || matchesAnyToken(desc, BLOCKED_SERVICE_TOKENS)) {
                            continue;
                        }
                        Class<?> stub = Class.forName(desc + "$Stub");
                        Object proxy = stub.getMethod("asInterface", ibinderClass).invoke(null, binder);
                        Class<?> iface = Class.forName(desc);
                        String shortPrefix = fName.endsWith("_SERVICE")
                                ? fName.substring(0, fName.length() - 8)
                                : fName;
                        int cnt = registerInterfaceGetters(shortPrefix, proxy, iface);
                        if (cnt > 0) {
                            appendSvcStatus(summary, shortPrefix, cnt);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        private int registerInterfaceGetters(String prefix, Object serviceInstance, Class<?> iface) {
            if (serviceInstance == null || iface == null) return 0;
            Method[] methods = iface.getMethods();
            Map<String, Method> sortedZeroArg = new TreeMap<String, Method>();
            Map<String, Method> sortedStatusObj = new TreeMap<String, Method>();

            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                String mName = m.getName();
                if (isSafeGetterMethod(m)) {
                    sortedZeroArg.put(mName, m);
                } else if (isSafeStatusObjectGetter(m)) {
                    sortedStatusObj.put(mName, m);
                } else if (m.getParameterTypes().length > 0
                        && isGetterPrefix(mName)
                        && isSafeSignalName(mName)
                        && isOccupantCandidate(prefix + "." + mName)) {
                    signatures.add(formatMethodSignature(prefix, m));
                }
            }

            int added = 0;
            for (Map.Entry<String, Method> e : sortedZeroArg.entrySet()) {
                String key = prefix + "." + e.getKey();
                BoundGetter bg = new BoundGetter(key, serviceInstance, e.getValue());
                allGetters.add(bg);
                added++;
                if ("BCM".equals(prefix)
                        || "HSCAN".equals(prefix)
                        || "BLACKSCREEN".equals(prefix)
                        || "POWER".equals(prefix)
                        || "DATC.isIGN3Status".equals(key)
                        || isOccupantCandidate(key)) {
                    fastGetters.add(bg);
                }
                if ("HSCAN.getOccupancy".equals(key)) {
                    bgHscanOccupancy = bg;
                } else if ("SYS.getClassStatus".equals(key)) {
                    bgSysClassStatus = bg;
                } else if ("HSCAN.getAsstStBltSwSta".equals(key)) {
                    bgAsstBelt = bg;
                } else if ("HSCAN.getPassStOccSta".equals(key)) {
                    bgPassStOcc = bg;
                } else if ("BCM.getIGNStatus".equals(key)) {
                    bgBcmIgnStatus = bg;
                } else if ("DATC.isIGN3Status".equals(key)) {
                    bgDatcIgn3Status = bg;
                } else if ("BLACKSCREEN.isBlackScreenShown".equals(key)) {
                    bgBlackScreenShown = bg;
                } else if ("POWER.isAvPowerOff".equals(key)) {
                    bgAvPowerOff = bg;
                } else if ("POWER.isDisplayOffMode".equals(key)) {
                    bgDisplayOffMode = bg;
                } else if ("BCM.getDrvSeatHeatOption".equals(key)) {
                    bgBcmDrvSeatOpt = bg;
                } else if ("BCM.getAstSeatHeatOption".equals(key)) {
                    bgBcmAstSeatOpt = bg;
                } else if ("BCM.getRLSeatHeatOption".equals(key)) {
                    bgBcmRlSeatOpt = bg;
                } else if ("BCM.getRRSeatHeatOption".equals(key)) {
                    bgBcmRrSeatOpt = bg;
                } else if ("USM.getFrontSeatHeat_DrvOption".equals(key)) {
                    bgUsmDrvSeatOpt = bg;
                } else if ("USM.getFrontSeatHeat_AstOption".equals(key)) {
                    bgUsmAstSeatOpt = bg;
                } else if ("USM.getRearSeatHeat_LeftOption".equals(key)) {
                    bgUsmRlSeatOpt = bg;
                } else if ("USM.getRearSeatHeat_RightOption".equals(key)) {
                    bgUsmRrSeatOpt = bg;
                }
            }

            for (Map.Entry<String, Method> e : sortedStatusObj.entrySet()) {
                BoundStatusObject bso = buildBoundStatusObject(prefix + "." + e.getKey(), serviceInstance, e.getValue());
                if (bso != null) {
                    statusObjects.add(bso);
                    added += bso.subFields.size() + bso.subMethods.size();
                }
            }
            return added;
        }

        private BoundStatusObject buildBoundStatusObject(String prefix, Object target, Method getter) {
            Class<?> rt = getter.getReturnType();
            List<Field> subFields = new ArrayList<Field>();
            List<Method> subMethods = new ArrayList<Method>();

            Field[] fields = rt.getFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (!Modifier.isStatic(f.getModifiers())
                        && isSafePrimitiveType(f.getType())
                        && isSafeSignalName(f.getName())) {
                    subFields.add(f);
                }
            }
            Method[] methods = rt.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (m.getParameterTypes().length == 0
                        && isGetterPrefix(m.getName())
                        && isSafePrimitiveType(m.getReturnType())
                        && isSafeSignalName(m.getName())) {
                    subMethods.add(m);
                }
            }
            if (subFields.isEmpty() && subMethods.isEmpty()) {
                return null;
            }
            boolean cand = isOccupantCandidate(prefix);
            for (int i = 0; !cand && i < subFields.size(); i++) {
                if (isOccupantCandidate(subFields.get(i).getName())) cand = true;
            }
            for (int i = 0; !cand && i < subMethods.size(); i++) {
                if (isOccupantCandidate(subMethods.get(i).getName())) cand = true;
            }
            return new BoundStatusObject(prefix, target, getter, subFields, subMethods, cand);
        }

        private void recordListenerSignatures(String label, String className, ClassLoader loader) {
            try {
                Class<?> cls = loader == null
                        ? Class.forName(className)
                        : Class.forName(className, true, loader);
                Method[] methods = cls.getMethods();
                for (int i = 0; i < methods.length; i++) {
                    Method m = methods[i];
                    String name = m.getName();
                    if ("asBinder".equals(name) || m.getDeclaringClass() == Object.class) continue;
                    if (isSafeSignalName(name) && isOccupantCandidate(name)) {
                        signatures.add(formatMethodSignature(label, m));
                    }
                }
            } catch (Throwable ignored) {}
        }

        private void recordStatusClassSignatures(String label, String className, ClassLoader loader) {
            try {
                Class<?> cls = loader == null
                        ? Class.forName(className)
                        : Class.forName(className, true, loader);
                Method[] methods = cls.getMethods();
                for (int i = 0; i < methods.length; i++) {
                    Method m = methods[i];
                    if (m.getDeclaringClass() == Object.class) continue;
                    if (isGetterPrefix(m.getName()) && isSafeSignalName(m.getName())
                            && isOccupantCandidate(m.getName())) {
                        signatures.add(formatMethodSignature(label, m));
                    }
                }
            } catch (Throwable ignored) {}
        }

        private static String formatMethodSignature(String prefix, Method m) {
            StringBuilder sb = new StringBuilder(80);
            sb.append(prefix).append('.').append(m.getName()).append('(');
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getSimpleName());
            }
            sb.append(") -> ").append(m.getReturnType().getSimpleName());
            return sb.toString();
        }

        private static void appendSvcStatus(StringBuilder sb, String name, int count) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(name).append('=');
            if (count < 0) {
                sb.append("NO");
            } else {
                sb.append("YES(").append(count).append(')');
            }
        }

        private static String readSanitizedBuildInfo() {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method get = sp.getMethod("get", String.class);
                String product = safeProp(get, "ro.build.product");
                String model = safeProp(get, "ro.product.model");
                String buildId = safeProp(get, "ro.build.id");
                String ver = safeProp(get, "ro.build.version.incremental");
                StringBuilder sb = new StringBuilder(80);
                if (!model.isEmpty()) sb.append(model);
                if (!product.isEmpty()) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(product);
                }
                if (!ver.isEmpty()) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(ver);
                } else if (!buildId.isEmpty()) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(buildId);
                }
                return sb.length() > 0 ? sb.toString() : "Android Gen5W";
            } catch (Throwable ignored) {
                return "host / off-car JVM";
            }
        }

        private static String safeProp(Method getProp, String key) {
            try {
                Object v = getProp.invoke(null, key);
                return v == null ? "" : String.valueOf(v).trim();
            } catch (Throwable ignored) {
                return "";
            }
        }

        private static String resolveServiceName(Class<?> holder, String fieldName, String fallback) {
            if (holder != null) {
                try {
                    Object v = holder.getField(fieldName).get(null);
                    if (v instanceof String && !((String) v).isEmpty()) {
                        return (String) v;
                    }
                } catch (Throwable ignored) {}
            }
            return fallback;
        }

        private static Object bindStub(
                Method getService,
                Class<?> ibinderClass,
                String serviceName,
                String stubClassName,
                ClassLoader loader) {
            try {
                Object binder = getService.invoke(null, serviceName);
                if (binder == null) return null;
                Class<?> stub = loader == null
                        ? Class.forName(stubClassName)
                        : Class.forName(stubClassName, true, loader);
                Method asInterface = stub.getMethod("asInterface", ibinderClass);
                return asInterface.invoke(null, binder);
            } catch (Throwable t) {
                return null;
            }
        }

        private static final class BoundGetter {
            final String key;
            final Object target;
            final Method method;

            BoundGetter(String key, Object target, Method method) {
                this.key = key;
                this.target = target;
                this.method = method;
            }
        }

        private static final class BoundStatusObject {
            final String prefix;
            final Object target;
            final Method getter;
            final List<Field> subFields;
            final List<Method> subMethods;
            final boolean candidate;

            BoundStatusObject(
                    String prefix,
                    Object target,
                    Method getter,
                    List<Field> subFields,
                    List<Method> subMethods,
                    boolean candidate) {
                this.prefix = prefix;
                this.target = target;
                this.getter = getter;
                this.subFields = subFields;
                this.subMethods = subMethods;
                this.candidate = candidate;
            }
        }
    }
}
