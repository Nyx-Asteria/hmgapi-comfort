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

        // 13. LoopbackServer + LoopbackClient round-trip & graceful fallback
        OccupantSignalProbe.LoopbackServer srv = new OccupantSignalProbe.LoopbackServer(probe, 0);
        srv.start();
        try {
            int port = srv.getLocalPort();
            OccupantSignalProbe.LoopbackClient cli = new OccupantSignalProbe.LoopbackClient(
                    port, new OccupantSignalProbe(mockSrc, "fallback"));
            check("loopback pingDaemon succeeds", true, cli.pingDaemon());
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
