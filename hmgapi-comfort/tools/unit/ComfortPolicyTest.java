package dev.lemoyne.comfort;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Off-car unit tests for ComfortPolicy, OccupantSignalProbe, and ComfortUsbExporter.
 */
public final class ComfortPolicyTest {
    private static int fail;

    @SuppressWarnings("unused")
    public interface SampleCarIface {
        int getPassSeatBelt();
        boolean IsCarMoving();
        int[] getSeatStates();
        String getVIN();
        int getSeatByIndex(int idx);
        void setSeatHeat(int level);
    }

    private static void check(String what, Object want, Object got) {
        String w = String.valueOf(want), g = String.valueOf(got);
        if (w.equals(g)) {
            System.out.println("ok    " + what);
        } else {
            System.out.println("FAIL  " + what + ": wanted [" + w + "], got [" + g + "]");
            fail = 1;
        }
    }

    private static String actions(List<ComfortPolicy.Action> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(list.get(i).toString());
        }
        return sb.toString();
    }

    private static Map<String, String> state(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        ComfortConfig def = ComfortConfig.defaults();

        // 1. Sentinel filtering
        check("rejects -40.0 F DATC sentinel", true, Float.isNaN(ComfortPolicy.parseAmbientF("-40.0")));
        check("rejects - dash", true, Float.isNaN(ComfortPolicy.parseAmbientF("-")));
        check("accepts 24.5 F", "24.5", ComfortPolicy.parseAmbientF("24.5"));

        // 2. Multi-tier winter heating (driver + wheel)
        ComfortPolicy.Plan p20 = ComfortPolicy.evaluateClimate(def, 20.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("20 F -> driver high + wheel on", "seat_heat driver high | wheel_heat on", actions(p20.actions));

        ComfortPolicy.Plan p34 = ComfortPolicy.evaluateClimate(def, 34.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("34 F -> driver medium + wheel on", "seat_heat driver medium | wheel_heat on", actions(p34.actions));

        ComfortPolicy.Plan p48 = ComfortPolicy.evaluateClimate(def, 48.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("48 F -> driver low only (above 45 F wheel threshold)", "seat_heat driver low", actions(p48.actions));

        ComfortPolicy.Plan p62 = ComfortPolicy.evaluateClimate(def, 62.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("62 F mild -> no actions", "", actions(p62.actions));

        // 3. Follower seat policies (match vs 1 step lower) + 2-step rear fallback
        ComfortConfig fam = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_MATCH, ComfortConfig.POLICY_LOWER, ComfortConfig.POLICY_MATCH,
                true, 75, 85, 95, false);
        ComfortPolicy.Plan pfam = ComfortPolicy.evaluateClimate(fam, 22.0f, state());
        check("22 F family policies (match pass, -1 rear_left, match rear_right)",
                "seat_heat driver high | seat_heat passenger high | seat_heat rear_left medium | seat_heat rear_right high | wheel_heat on",
                actions(pfam.actions));
        check("2-step rear fallback maps medium -> low", "low", ComfortPolicy.fallbackTwoStep("medium"));
        check("2-step rear fallback preserves high", "high", ComfortPolicy.fallbackTwoStep("high"));

        // 4. Summer seat ventilation (front seats only, rear never ventilated)
        ComfortConfig passLower = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_LOWER, ComfortConfig.POLICY_MATCH, ComfortConfig.POLICY_MATCH,
                true, 75, 85, 95, false);
        ComfortPolicy.Plan p78 = ComfortPolicy.evaluateClimate(passLower, 78.0f, state());
        check("78 F -> driver vent low (passenger -1 = off)", "seat_vent driver low", actions(p78.actions));

        ComfortPolicy.Plan p88 = ComfortPolicy.evaluateClimate(fam, 88.0f, state());
        check("88 F -> driver + passenger vent medium", "seat_vent driver medium | seat_vent passenger medium", actions(p88.actions));

        ComfortPolicy.Plan p98 = ComfortPolicy.evaluateClimate(fam, 98.0f, state());
        check("98 F -> driver + passenger vent high", "seat_vent driver high | seat_vent passenger high", actions(p98.actions));

        // 5. Bluelink / manual climate preservation
        ComfortPolicy.Plan pBlue = ComfortPolicy.evaluateClimate(fam, 18.0f,
                state("seat_state.driver", "8", "seat_state.passenger", "2", "wheel_heat", "1"));
        check("Bluelink active driver & wheel preserved, passenger still warmed",
                "seat_heat passenger high | seat_heat rear_left medium | seat_heat rear_right high",
                actions(pBlue.actions));

        // 6. DriveTracker across cold boot, drive off, and QuickBoot STR resume
        ComfortPolicy.DriveTracker dt = new ComfortPolicy.DriveTracker();
        dt.onSuspendCheck(1000L);
        check("cold boot EV_RDY=1 triggers", true, dt.shouldTrigger("1"));
        dt.markCompleted();
        check("same drive EV_RDY=1 does not re-trigger", false, dt.shouldTrigger("1"));
        check("single EV_RDY=0 glitch does not reset", false, dt.shouldTrigger("0"));
        check("still completed after 1 glitch", false, dt.shouldTrigger("1"));
        dt.shouldTrigger("0");
        dt.shouldTrigger("0");
        check("sustained EV_RDY=0 resets for next drive", true, dt.shouldTrigger("1"));
        dt.markCompleted();
        dt.onSuspendCheck(601000L);   // 10 min QuickBoot STR sleep
        check("QuickBoot STR sleep gap re-arms drive trigger", true, dt.shouldTrigger("1"));

        // 7. WelcomeTracker across cold boot, SCREEN_OFF/SCREEN_ON, and QuickBoot STR resume
        ComfortPolicy.WelcomeTracker wt = new ComfortPolicy.WelcomeTracker(1000L);
        wt.onSuspendCheck(500L, 1000L);
        check("before delay -> false", false, wt.readyToDismiss(1500L, 800));
        check("after 800 ms delay -> true", true, wt.readyToDismiss(1800L, 800));
        wt.markDone();
        check("done -> false", false, wt.readyToDismiss(3000L, 800));
        wt.onScreenOff();
        check("while screen off -> false", false, wt.readyToDismiss(5000L, 800));
        wt.onScreenOn(6000L);
        check("screen on before delay -> false", false, wt.readyToDismiss(6500L, 800));
        check("screen on after delay -> true", true, wt.readyToDismiss(6800L, 800));
        wt.markDone();
        wt.onSuspendCheck(300500L, 10000L);   // QuickBoot resume without broadcast
        check("QuickBoot STR resume re-arms welcome dismiss", true, wt.readyToDismiss(10800L, 800));

        // 8. Plugged-in / Utility cabin sessions vs unoccupied charging, ACC-only, and remote Bluelink
        ComfortPolicy.DriveTracker pdt = new ComfortPolicy.DriveTracker();
        check("unoccupied overnight charging (IG2 off, screen off) ignored", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "0"), false));
        check("ACC-only / IG2 off (seat_state=0) with screen on ignored", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "0", "gear.park", "1", "seat_state.driver", "0"), true));
        check("cold-boot uninitialized BCM (seat_state=15) ignored", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "15"), true));
        check("remote Bluelink while locked/dark screen ignored until owner wakes screen", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "8"), false));
        check("plugged-in active charging cabin session triggers once screen wakes", true,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "2"), true));
        pdt.markCompleted();
        check("pressing Display Off mid-session does not reset completed cabin session", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "8"), false));
        check("waking display in same plugged-in session does not double-fire", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "8"), true));
        check("unplugging directly into EV_RDY=1 in same session does not double-fire", false,
                pdt.shouldTrigger(state("hv.ready", "1", "charging", "0", "gear.park", "1", "seat_state.driver", "8"), true));
        pdt.onPowerOff();
        check("plugged-in session after target SOC reached (charging=0, IG2 on) triggers", true,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "0", "gear.park", "1", "seat_state.driver", "2"), true));

        // 9. Closed-loop command verification rejects cold-boot "-", "0", "15", and "2"
        ComfortPolicy.Action wheelOn = new ComfortPolicy.Action("wheel_heat", "on", "");
        check("wheel_heat unconfirmed on cold-boot '-'", false, ComfortPolicy.isActionConfirmed(wheelOn, state("wheel_heat", "-")));
        check("wheel_heat unconfirmed on '0'", false, ComfortPolicy.isActionConfirmed(wheelOn, state("wheel_heat", "0")));
        check("wheel_heat confirmed on '1'", true, ComfortPolicy.isActionConfirmed(wheelOn, state("wheel_heat", "1")));

        ComfortPolicy.Action driverHigh = new ComfortPolicy.Action("seat_heat", "driver", "high");
        check("seat_heat unconfirmed on cold-boot '-'", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "-")));
        check("seat_heat unconfirmed on cold-boot '0'", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "0")));
        check("seat_heat unconfirmed on cold-boot '15'", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "15")));
        check("seat_heat unconfirmed when still off ('2')", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "2")));
        check("seat_heat unconfirmed when ventilating ('4')", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "4")));
        check("seat_heat confirmed on BCM heat state ('8')", true, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "8")));

        ComfortPolicy.Action driverVent = new ComfortPolicy.Action("seat_vent", "driver", "medium");
        check("seat_vent unconfirmed on '2'", false, ComfortPolicy.isActionConfirmed(driverVent, state("seat_state.driver", "2")));
        check("seat_vent confirmed on BCM vent state ('4')", true, ComfortPolicy.isActionConfirmed(driverVent, state("seat_state.driver", "4")));

        // 10. Cold-boot transient vs permanent refusals & confirmed out-of-Park guard
        check("transient: vehicle service isn't running", false,
                ComfortPolicy.isPermanentCommandRefusal("the vehicle service isn't running (yet)"));
        check("transient: car's service failed on boot", false,
                ComfortPolicy.isPermanentCommandRefusal("refused: the car's service failed (java.lang.IllegalStateException: no vrm service)"));
        check("transient: hmgapid busy code 75", false,
                ComfortPolicy.isPermanentCommandRefusal("hmgapid: code 75"));
        check("permanent: owner denied command", true,
                ComfortPolicy.isPermanentCommandRefusal("refused: the owner didn't allow wheel_heat for hmgapi-comfort"));
        check("permanent: rear seat lacks ventilation", true,
                ComfortPolicy.isPermanentCommandRefusal("refused: the rear seats have no ventilation"));

        check("cold-boot uninitialized gear/hv is NOT confirmed out of Park", false,
                ComfortPolicy.isConfirmedOutOfPark(state("hv.ready", "-", "gear.park", "-")));
        check("hv.ready=0 with gear.park=0 is NOT confirmed out of Park", false,
                ComfortPolicy.isConfirmedOutOfPark(state("hv.ready", "0", "gear.park", "0")));
        check("hv.ready=1 in Park is NOT out of Park", false,
                ComfortPolicy.isConfirmedOutOfPark(state("hv.ready", "1", "gear.park", "1")));
        check("hv.ready=1 with gear.park=0 IS confirmed out of Park", true,
                ComfortPolicy.isConfirmedOutOfPark(state("hv.ready", "1", "gear.park", "0")));

        // 11. OccupantSignalProbe word-aware privacy filter & read-only getter safety
        check("allows passenger / PassDoor", true, OccupantSignalProbe.isSafeSignalName("BCM.getPassDoorOpen"));
        check("allows passenger seat", true, OccupantSignalProbe.isSafeSignalName("BCM.getPassengerSeatBelt"));
        check("allows telltale (not tel)", true, OccupantSignalProbe.isSafeSignalName("CLU.getSeatBeltTelltale"));
        check("allows IsCarMoving / driving (not vin)", true, OccupantSignalProbe.isSafeSignalName("HSCAN.IsCarMoving"));
        check("allows latchState (not lat)", true, OccupantSignalProbe.isSafeSignalName("BCM.getRearLatchState"));
        check("blocks getVIN", false, OccupantSignalProbe.isSafeSignalName("SYS.getVIN"));
        check("blocks getVIN2", false, OccupantSignalProbe.isSafeSignalName("HSCAN.getVIN2"));
        check("blocks serialNo", false, OccupantSignalProbe.isSafeSignalName("SYS.getSerialNo"));
        check("blocks phoneNumber", false, OccupantSignalProbe.isSafeSignalName("BT.getPhoneNumber"));
        check("blocks telNumber", false, OccupantSignalProbe.isSafeSignalName("MODEM.getTelNumber"));
        check("blocks password / passcode / pin", false,
                OccupantSignalProbe.isSafeSignalName("USM.getPassword")
                        || OccupantSignalProbe.isSafeSignalName("USM.getPasscode")
                        || OccupantSignalProbe.isSafeSignalName("USM.getPinCode"));
        check("blocks GPS / latitude / MAC", false,
                OccupantSignalProbe.isSafeSignalName("NAVI.getGpsLat")
                        || OccupantSignalProbe.isSafeSignalName("NAVI.getLatitude")
                        || OccupantSignalProbe.isSafeSignalName("WIFI.getMacAddr"));

        check("safe zero-arg int getter allowed", true,
                OccupantSignalProbe.isSafeGetterMethod(SampleCarIface.class.getMethod("getPassSeatBelt")));
        check("safe PascalCase Is* boolean getter allowed", true,
                OccupantSignalProbe.isSafeGetterMethod(SampleCarIface.class.getMethod("IsCarMoving")));
        check("safe int[] getter allowed", true,
                OccupantSignalProbe.isSafeGetterMethod(SampleCarIface.class.getMethod("getSeatStates")));
        check("String VIN getter rejected", false,
                OccupantSignalProbe.isSafeGetterMethod(SampleCarIface.class.getMethod("getVIN")));
        check("parameterized getter rejected from direct invocation", false,
                OccupantSignalProbe.isSafeGetterMethod(SampleCarIface.class.getMethod("getSeatByIndex", int.class)));
        check("mutator set* rejected", false,
                OccupantSignalProbe.isSafeGetterMethod(SampleCarIface.class.getMethod("setSeatHeat", int.class)));

        // 12. OccupantSignalProbe baseline diffing + walk-around [TOGGLED] retention + noisy counter filter
        final Map<String, String> liveSignals = new LinkedHashMap<String, String>();
        liveSignals.put("BCM.getPassOccupant", "0");
        liveSignals.put("HSCAN.getPassSeatBelt", "0");
        liveSignals.put("HSCAN.getOdometer", "12340");
        liveSignals.put("SYS.getVIN", "SHOULD_BE_BLOCKED");

        final int[] wakeCount = new int[] { 0 };
        OccupantSignalProbe.SignalSource mockSrc = new OccupantSignalProbe.SignalSource() {
            @Override
            public String serviceStatusSummary() {
                return "BCM=YES(1) HSCAN=YES(2)";
            }

            @Override
            public String deviceBuildSummary() {
                return "Ioniq5 / NEEV23SE / 260903";
            }

            @Override
            public List<String> signatureInventory() {
                return Collections.singletonList("BCMListener.onPassSeatBeltChanged(int) -> void");
            }

            @Override
            public Map<String, String> readSignals(boolean fullScan) {
                return new LinkedHashMap<String, String>(liveSignals);
            }

            @Override
            public OccupantSignalProbe.PassengerPresence readPassengerPresence() {
                return OccupantSignalProbe.PassengerPresence.fromSignalMap(liveSignals);
            }

            @Override
            public OccupantSignalProbe.CabinState readCabinState() {
                return OccupantSignalProbe.CabinState.fromSignalMap(liveSignals);
            }

            @Override
            public int readMobisSleepingFast() {
                return OccupantSignalProbe.CabinState.fromSignalMap(liveSignals).mobisSleeping;
            }

            @Override
            public void onWake() {
                wakeCount[0]++;
            }
        };

        OccupantSignalProbe probe = new OccupantSignalProbe(mockSrc, "UID 1000 test");
        probe.markBaseline("Solo driver");
        check("initial baseline has 0 diffs", 0, probe.getChangedCount());
        check("auto-capture enabled after markBaseline", true, probe.isAutoCaptureActive());

        // Simulate solo user walking to passenger seat, buckling & unbuckling belt, and leaving bag on seat
        liveSignals.put("BCM.getPassOccupant", "1");
        liveSignals.put("HSCAN.getPassSeatBelt", "1");
        liveSignals.put("HSCAN.getOdometer", "12341");
        probe.poll(true);

        liveSignals.put("HSCAN.getPassSeatBelt", "0");
        liveSignals.put("HSCAN.getOdometer", "12342");
        probe.poll(true);

        check("counts both [ACTIVE] occupant diff and [TOGGLED] belt (ignores odometer & blocked VIN)",
                2, probe.getChangedCount());
        String fullReport = probe.buildFullReport(false, "seat_state[drv=2, psg=2]");
        check("report marks active occupant diff", true,
                fullReport.contains("BCM.getPassOccupant : 0 -> 1  [ACTIVE]"));
        check("report retains walk-around toggled belt history 0 -> 1 -> 0", true,
                fullReport.contains("HSCAN.getPassSeatBelt : 0 -> 1 -> 0  [TOGGLED]"));
        check("report includes listener signature inventory", true,
                fullReport.contains("BCMListener.onPassSeatBeltChanged(int) -> void"));
        check("report never leaks blocked VIN signal", false,
                fullReport.contains("SHOULD_BE_BLOCKED") || fullReport.contains("SYS.getVIN"));

        // 13. LoopbackServer + LoopbackClient round-trip & OCCUPANCY / CABIN_STATE / WAKE fast queries
        liveSignals.put("HSCAN.getOccupancy", "2");
        liveSignals.put("SYS.getClassStatus", "2");
        liveSignals.put("HSCAN.getAsstStBltSwSta", "1");
        liveSignals.put("BCM.getIGNStatus", "true");
        liveSignals.put("DATC.isIGN3Status", "true");
        liveSignals.put("BLACKSCREEN.isBlackScreenShown", "false");
        liveSignals.put("POWER.isAvPowerOff", "false");
        liveSignals.put("POWER.isDisplayOffMode", "false");
        liveSignals.put("PUSH.outside_temp_f", "33.5");
        liveSignals.put("PUSH.outside_temp", "0.8");
        liveSignals.put("PUSH.wheel_heat", "0");
        liveSignals.put("PUSH.datc_sentinel", "0");
        OccupantSignalProbe.LoopbackServer srv = new OccupantSignalProbe.LoopbackServer(probe, 0);
        srv.start();
        try {
            int port = srv.getLocalPort();
            OccupantSignalProbe.LoopbackClient cli = new OccupantSignalProbe.LoopbackClient(
                    port, new OccupantSignalProbe(mockSrc, "fallback"));
            check("loopback pingDaemon succeeds", true, cli.pingDaemon());
            OccupantSignalProbe.PassengerPresence loopOcc = cli.queryPassengerPresence();
            check("loopback OCCUPANCY query reports available & occupied",
                    "true/true/2/2/1",
                    loopOcc.available + "/" + loopOcc.occupied + "/"
                            + loopOcc.hscanOccupancy + "/" + loopOcc.sysClassStatus + "/" + loopOcc.asstBelt);
            OccupantSignalProbe.CabinState loopCabin = cli.queryCabinState();
            check("loopback CABIN_STATE query reports push temp, wheel, ign, avAwake & mobisSleeping",
                    "33.5/0.8/0/1/1/1/0/false",
                    loopCabin.outsideTempF + "/" + loopCabin.outsideTempC + "/" + loopCabin.wheelHeat
                            + "/" + loopCabin.ignOn + "/" + loopCabin.ign3On
                            + "/" + loopCabin.avAwake + "/" + loopCabin.mobisSleeping
                            + "/" + loopCabin.datcSentinel);
            cli.notifyWake();
            check("loopback WAKE triggers source.onWake()", 1, wakeCount[0]);
            OccupantSignalProbe.UiState uiState = cli.pollUi(false, "hv.ready=1");
            check("loopback pollUi returns UID 1000 banner", true,
                    uiState.sourceBanner.startsWith("UID 1000 test"));
            check("loopback pollUi preserves 2 changed signals", 2, uiState.changedCount);

            File tmpRoot = Files.createTempDirectory("comfort-usb-test").toFile();
            try {
                File usb0 = new File(tmpRoot, "usb0");
                usb0.mkdirs();
                ComfortUsbExporter.ExportResult exp = cli.exportToUsb(
                        Collections.singletonList(new File(usb0, "ComfortProbe").getAbsolutePath()),
                        "hv.ready=1");
                check("loopback exportToUsb succeeds to USB folder", true, exp.ok);
                check("exported file exists under ComfortProbe/", true,
                        exp.reportPath != null
                                && exp.reportPath.contains("/ComfortProbe/occupant-probe-")
                                && new File(exp.reportPath).isFile());
            } finally {
                deleteTree(tmpRoot);
            }
        } finally {
            srv.stop();
        }

        // 14. ComfortUsbExporter /proc/mounts parser rejects internal flash & accepts vfat/exfat USB
        String sampleMounts =
                "rootfs / rootfs ro 0 0\n"
                + "/dev/block/mmcblk0p25 /system ext4 ro 0 0\n"
                + "/dev/block/mmcblk0p28 /data ext4 rw 0 0\n"
                + "tmpfs /mnt/media_rw tmpfs rw 0 0\n"
                + "/dev/fuse /storage/emulated/0 fuse rw 0 0\n"
                + "/dev/block/vold/8:1 /mnt/media_rw/usb0 exfat rw 0 0\n"
                + "/dev/fuse /storage/usb0 fuse rw 0 0\n";
        List<String> mounts = ComfortUsbExporter.parseUsbMountPoints(sampleMounts);
        check("parseUsbMountPoints orders /storage/usb0 then /mnt/media_rw/usb0 and blocks internal partitions",
                Arrays.asList("/storage/usb0", "/mnt/media_rw/usb0"), mounts);

        // 15. hmgapid MAX_ARGS (16) vehicle batch chunking invariant
        String[] sixteenKeys = {
            "hv.ready", "gear.park", "charging", "outside_temp_f", "outside_temp", "wheel_heat",
            "seat_heat.driver", "seat_heat.passenger", "seat_heat.rear_left", "seat_heat.rear_right",
            "seat_vent.driver", "seat_vent.passenger",
            "seat_state.driver", "seat_state.passenger", "seat_state.rear_left", "seat_state.rear_right"
        };
        List<String[]> batches = ComfortPolicy.chunkVehicleKeys(sixteenKeys, ComfortPolicy.MAX_VEHICLE_BATCH_KEYS);
        check("16 vehicle keys split into 2 batches of 8", 2, batches.size());
        check("batch 0 has 8 keys (argc=9 <= 16)", 8, batches.get(0).length);
        check("batch 1 has 8 keys (argc=9 <= 16)", 8, batches.get(1).length);
        boolean allUnderMaxArgs = true;
        int totalChunked = 0;
        for (int i = 0; i < batches.size(); i++) {
            totalChunked += batches.get(i).length;
            if (1 + batches.get(i).length > 16) allUnderMaxArgs = false;
        }
        check("all vehicle IPC batches satisfy argc <= 16", true, allUnderMaxArgs && totalChunked == 16);

        // 16. PassengerPresence classification across Ioniq 5 empirical states & wire codec
        OccupantSignalProbe.PassengerPresence preCan = OccupantSignalProbe.PassengerPresence.fromRaw(0, 0, 0, 0);
        check("pre-CAN (0,0,0,0) unavailable & unoccupied", "false/false", preCan.available + "/" + preCan.occupied);
        OccupantSignalProbe.PassengerPresence invalidCan = OccupantSignalProbe.PassengerPresence.fromRaw(15, 255, -1, 0);
        check("invalid CAN (15,255,-1,0) unavailable & unoccupied", "false/false",
                invalidCan.available + "/" + invalidCan.occupied);
        OccupantSignalProbe.PassengerPresence soloEmpty = OccupantSignalProbe.PassengerPresence.fromRaw(1, 1, 0, 0);
        check("solo empty (1,1,0,0) available & unoccupied", "true/false",
                soloEmpty.available + "/" + soloEmpty.occupied);
        OccupantSignalProbe.PassengerPresence childCrs = OccupantSignalProbe.PassengerPresence.fromRaw(1, 3, 0, 0);
        check("ODS child CRS (1,3,0,0) available & unoccupied (never heats CRS)", "true/false",
                childCrs.available + "/" + childCrs.occupied);
        OccupantSignalProbe.PassengerPresence seatedUnbuckled = OccupantSignalProbe.PassengerPresence.fromRaw(2, 2, 0, 0);
        check("adult seated unbuckled (2,2,0,0) available & occupied", "true/true",
                seatedUnbuckled.available + "/" + seatedUnbuckled.occupied);
        OccupantSignalProbe.PassengerPresence seatedBuckled = OccupantSignalProbe.PassengerPresence.fromRaw(2, 2, 1, 0);
        check("adult seated buckled (2,2,1,0) available & occupied", "true/true",
                seatedBuckled.available + "/" + seatedBuckled.occupied);
        OccupantSignalProbe.PassengerPresence beltOnly = OccupantSignalProbe.PassengerPresence.fromRaw(1, 1, 1, 0);
        check("passenger belt buckled (1,1,1,0) available & occupied", "true/true",
                beltOnly.available + "/" + beltOnly.occupied);
        OccupantSignalProbe.PassengerPresence decoded =
                OccupantSignalProbe.PassengerPresence.decode(seatedBuckled.encode());
        check("PassengerPresence encode/decode round-trip", seatedBuckled.encode(), decoded.encode());

        // 17. Automatic passenger occupancy gating on startup climate (heat & vent)
        ComfortConfig autoPass = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_MATCH, true, ComfortConfig.POLICY_OFF, ComfortConfig.POLICY_OFF,
                true, 75, 85, 95, false);
        ComfortConfig alwaysPass = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_MATCH, false, ComfortConfig.POLICY_OFF, ComfortConfig.POLICY_OFF,
                true, 75, 85, 95, false);

        ComfortPolicy.Plan soloHeat = ComfortPolicy.evaluateClimate(
                autoPass, 22.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"), soloEmpty);
        check("auto-detect skips empty passenger seat at 22 F",
                "seat_heat driver high | wheel_heat on", actions(soloHeat.actions));
        check("auto-detect summary notes passenger empty", true, soloHeat.summary.contains("passenger empty"));

        ComfortPolicy.Plan occHeat = ComfortPolicy.evaluateClimate(
                autoPass, 22.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"), seatedUnbuckled);
        check("auto-detect warms occupied passenger seat at 22 F",
                "seat_heat driver high | seat_heat passenger high | wheel_heat on", actions(occHeat.actions));

        ComfortPolicy.Plan soloVent = ComfortPolicy.evaluateClimate(
                autoPass, 90.0f, state("seat_state.driver", "2", "seat_state.passenger", "2"), soloEmpty);
        check("auto-detect skips empty passenger ventilation at 90 F",
                "seat_vent driver medium", actions(soloVent.actions));

        ComfortPolicy.Plan occVent = ComfortPolicy.evaluateClimate(
                autoPass, 90.0f, state("seat_state.driver", "2", "seat_state.passenger", "2"), seatedBuckled);
        check("auto-detect cools occupied passenger seat at 90 F",
                "seat_vent driver medium | seat_vent passenger medium", actions(occVent.actions));

        ComfortPolicy.Plan fallbackHeat = ComfortPolicy.evaluateClimate(
                autoPass, 22.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"),
                OccupantSignalProbe.PassengerPresence.UNAVAILABLE);
        check("unavailable sensor falls back gracefully to configured policy",
                "seat_heat driver high | seat_heat passenger high | wheel_heat on", actions(fallbackHeat.actions));

        ComfortPolicy.Plan forcedHeat = ComfortPolicy.evaluateClimate(
                alwaysPass, 22.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"), soloEmpty);
        check("passengerAutoDetect=false warms passenger even when empty",
                "seat_heat driver high | seat_heat passenger high | wheel_heat on", actions(forcedHeat.actions));

        // 18. Mid-session late passenger boarding tracker
        ComfortPolicy.PassengerBoardingTracker bt = new ComfortPolicy.PassengerBoardingTracker();
        Map<String, String> drvSession = state(
                "hv.ready", "1", "gear.park", "0", "seat_state.driver", "8", "seat_state.passenger", "2");
        bt.onSessionClimateEvaluated(autoPass, soloEmpty);
        check("brief 1-tick seat lean ignored", false,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedUnbuckled));
        check("sustained 2-tick seated passenger triggers late boarding", true,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedUnbuckled));
        ComfortPolicy.Plan boardPlan = ComfortPolicy.evaluatePassengerBoarding(
                autoPass, 24.0f, drvSession, seatedUnbuckled);
        check("late boarding plan warms passenger seat only",
                "seat_heat passenger high", actions(boardPlan.actions));
        bt.markBoardingHandled();
        check("does not re-fire while same passenger remains seated (respects manual Off)", false,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedBuckled));
        for (int i = 0; i < 5; i++) {
            check("short unseat <6 polls does not re-arm", false,
                    bt.shouldTriggerLateBoarding(autoPass, drvSession, true, soloEmpty));
        }
        check("6th empty poll re-arms tracker for next passenger", false,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, soloEmpty));
        check("buckling belt triggers immediately on 1st tick after re-arm", true,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedBuckled));

        ComfortPolicy.PassengerBoardingTracker btSeatedStart = new ComfortPolicy.PassengerBoardingTracker();
        btSeatedStart.onSessionClimateEvaluated(autoPass, seatedUnbuckled);
        check("passenger already seated at startup does not double-fire mid-session", false,
                btSeatedStart.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedBuckled));

        // 19. Refined Signal Inspector candidate & noisy-counter filters from Ioniq 5 capture
        check("candidate matches HSCAN.getAsstStBltSwSta", true,
                OccupantSignalProbe.isOccupantCandidate("HSCAN.getAsstStBltSwSta"));
        check("candidate matches SYS.getClassStatus", true,
                OccupantSignalProbe.isOccupantCandidate("SYS.getClassStatus"));
        check("candidate matches BCM.getAstSeatBelt", true,
                OccupantSignalProbe.isOccupantCandidate("BCM.getAstSeatBelt"));
        check("candidate ignores RADIO.getLastAMFrequency (not 'ast')", false,
                OccupantSignalProbe.isOccupantCandidate("RADIO.getLastAMFrequency"));
        check("candidate ignores HEV.getLastDepartTime (not 'ast')", false,
                OccupantSignalProbe.isOccupantCandidate("HEV.getLastDepartTime"));
        check("noisy filter catches SXM65H.getTime", true,
                OccupantSignalProbe.isNoisyCounter("SXM65H.getTime"));
        check("noisy filter catches HSCAN.getSpeedDistKM", true,
                OccupantSignalProbe.isNoisyCounter("HSCAN.getSpeedDistKM"));
        check("noisy filter catches HEVFlag.DATC_CLMTPWRCNSMPTN_W", true,
                OccupantSignalProbe.isNoisyCounter("HEVFlag.DATC_CLMTPWRCNSMPTN_W"));

        // 20. QuickBoot plugged-in charging: CabinState push fallback, DisplayOff vs BlackScreen, and ACC -> Ignition ON
        check("DisplayOff sets avAwake=0 without mobisSleeping=1", "0/0",
                OccupantSignalProbe.CabinState.computeAvAwake(0, 0, 1)
                        + "/" + OccupantSignalProbe.CabinState.computeMobisSleeping(0, 0));
        check("BlackScreen sets both avAwake=0 and mobisSleeping=1", "0/1",
                OccupantSignalProbe.CabinState.computeAvAwake(1, 0, 0)
                        + "/" + OccupantSignalProbe.CabinState.computeMobisSleeping(1, 0));
        check("AV awake sets avAwake=1 and mobisSleeping=0", "1/0",
                OccupantSignalProbe.CabinState.computeAvAwake(0, 0, 0)
                        + "/" + OccupantSignalProbe.CabinState.computeMobisSleeping(0, 0));

        OccupantSignalProbe.CabinState pushOn = new OccupantSignalProbe.CabinState(
                soloEmpty, "31.5", "-0.3", "1", 1, 1, 1, 0, false);
        OccupantSignalProbe.CabinState pushDecoded =
                OccupantSignalProbe.CabinState.decode(pushOn.encode());
        check("CabinState encode/decode round-trip", pushOn.encode(), pushDecoded.encode());

        Map<String, String> staleWakeHub = state(
                "hv.ready", "0", "charging", "1", "gear.park", "1",
                "outside_temp_f", "-", "outside_temp", "-", "wheel_heat", "-",
                "seat_state.driver", "2");
        Map<String, String> mergedWake = ComfortPolicy.mergeCabinFallback(staleWakeHub, pushOn);
        check("mergeCabinFallback fills missing outside_temp_f from CabinPushBridge",
                "31.5", mergedWake.get("outside_temp_f"));
        check("mergeCabinFallback fills missing outside_temp C from CabinPushBridge",
                "-0.3", mergedWake.get("outside_temp"));
        check("mergeCabinFallback fills missing wheel_heat from CabinPushBridge",
                "1", mergedWake.get("wheel_heat"));

        Map<String, String> liveHub = state(
                "outside_temp_f", "29.0", "outside_temp", "-1.7", "wheel_heat", "0");
        Map<String, String> preservedHub = ComfortPolicy.mergeCabinFallback(liveHub, pushOn);
        check("mergeCabinFallback preserves non-empty HMG-API hub readings",
                "29.0/-1.7/0",
                preservedHub.get("outside_temp_f") + "/"
                        + preservedHub.get("outside_temp") + "/"
                        + preservedHub.get("wheel_heat"));

        OccupantSignalProbe.CabinState accSentinelCabin = new OccupantSignalProbe.CabinState(
                soloEmpty, "-40.0", "-40.0", "0", 0, 1, 1, 0, true);
        Map<String, String> mergedAcc = ComfortPolicy.mergeCabinFallback(staleWakeHub, accSentinelCabin);
        check("mergeCabinFallback rejects -40.0 F DATC sentinel while still filling wheel_heat",
                "-/0", mergedAcc.get("outside_temp_f") + "/" + mergedAcc.get("wheel_heat"));

        ComfortPolicy.DriveTracker qbChargeDt = new ComfortPolicy.DriveTracker();
        Map<String, String> pluggedAcc = state(
                "hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "0");
        check("plugged-in ACC standby detected when charging in Park with ignOn=0", true,
                ComfortPolicy.isPluggedInAccStandby(pluggedAcc, true, 0));
        check("plugged-in ACC standby does NOT trigger heaters while IG2 off", false,
                qbChargeDt.shouldTrigger(pluggedAcc, true, 0));
        check("pressing Start -> Ignition ON (ignOn=1) while charging immediately triggers climate", true,
                qbChargeDt.shouldTrigger(pluggedAcc, true, 1));
        check("isPluggedInAccStandby clears as soon as ignOn=1", false,
                ComfortPolicy.isPluggedInAccStandby(pluggedAcc, true, 1));
        qbChargeDt.markCompleted();
        check("same plugged-in Ignition ON session does not re-fire", false,
                qbChargeDt.shouldTrigger(state(
                        "hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "8"), true, 1));
        qbChargeDt.shouldTrigger(pluggedAcc, false, 0);
        qbChargeDt.shouldTrigger(pluggedAcc, false, 0);
        check("turning off Ignition while remaining plugged in re-arms for next wake", true,
                qbChargeDt.shouldTrigger(pluggedAcc, true, 1));

        System.exit(fail);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                deleteTree(kids[i]);
            }
        }
        f.delete();
    }
}
