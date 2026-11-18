package dev.lemoyne.comfort;

/**
 * Immutable configuration for Comfort & Startup, using integer thresholds so
 * HMG-API's SettingsForm (Long.parseLong) and JSON round-tripping always agree.
 */
public final class ComfortConfig {
    public static final String POLICY_OFF = "off";
    public static final String POLICY_MATCH = "match";
    public static final String POLICY_LOWER = "lower";

    public final boolean welcomeDismiss;
    public final int welcomeDelayMs;
    public final boolean climateEnabled;
    public final int heatHighF;
    public final int heatMedF;
    public final int heatLowF;
    public final boolean wheelEnabled;
    public final int wheelF;
    public final String passengerPolicy;
    public final boolean passengerAutoDetect;
    public final String rearLeftPolicy;
    public final String rearRightPolicy;
    public final boolean ventEnabled;
    public final int ventLowF;
    public final int ventMedF;
    public final int ventHighF;
    public final boolean toastOnApply;
    public final int heatHighC;
    public final int heatMedC;
    public final int heatLowC;
    public final int wheelC;
    public final int ventLowC;
    public final int ventMedC;
    public final int ventHighC;

    public ComfortConfig(
            boolean welcomeDismiss,
            int welcomeDelayMs,
            boolean climateEnabled,
            int heatHighF,
            int heatMedF,
            int heatLowF,
            boolean wheelEnabled,
            int wheelF,
            String passengerPolicy,
            String rearLeftPolicy,
            String rearRightPolicy,
            boolean ventEnabled,
            int ventLowF,
            int ventMedF,
            int ventHighF,
            boolean toastOnApply) {
        this(welcomeDismiss, welcomeDelayMs, climateEnabled, heatHighF, heatMedF, heatLowF,
                wheelEnabled, wheelF, passengerPolicy, true, rearLeftPolicy, rearRightPolicy,
                ventEnabled, ventLowF, ventMedF, ventHighF, toastOnApply);
    }

    public ComfortConfig(
            boolean welcomeDismiss,
            int welcomeDelayMs,
            boolean climateEnabled,
            int heatHighF,
            int heatMedF,
            int heatLowF,
            boolean wheelEnabled,
            int wheelF,
            String passengerPolicy,
            boolean passengerAutoDetect,
            String rearLeftPolicy,
            String rearRightPolicy,
            boolean ventEnabled,
            int ventLowF,
            int ventMedF,
            int ventHighF,
            boolean toastOnApply) {
        this(welcomeDismiss, welcomeDelayMs, climateEnabled,
                heatHighF, heatMedF, heatLowF,
                wheelEnabled, wheelF,
                passengerPolicy, passengerAutoDetect,
                rearLeftPolicy, rearRightPolicy,
                ventEnabled, ventLowF, ventMedF, ventHighF,
                toastOnApply,
                fToC(clamp(heatHighF, -30, 80)),
                fToC(Math.max(clamp(heatHighF, -30, 80), clamp(heatMedF, -30, 85))),
                fToC(Math.max(Math.max(clamp(heatHighF, -30, 80), clamp(heatMedF, -30, 85)), clamp(heatLowF, -30, 90))),
                fToC(clamp(wheelF, -30, 90)),
                fToC(Math.max(Math.max(Math.max(clamp(heatHighF, -30, 80), clamp(heatMedF, -30, 85)), clamp(heatLowF, -30, 90)) + 5, clamp(ventLowF, 60, 120))),
                fToC(Math.max(Math.max(Math.max(Math.max(clamp(heatHighF, -30, 80), clamp(heatMedF, -30, 85)), clamp(heatLowF, -30, 90)) + 5, clamp(ventLowF, 60, 120)), clamp(ventMedF, 60, 125))),
                fToC(Math.max(Math.max(Math.max(Math.max(Math.max(clamp(heatHighF, -30, 80), clamp(heatMedF, -30, 85)), clamp(heatLowF, -30, 90)) + 5, clamp(ventLowF, 60, 120)), clamp(ventMedF, 60, 125)), clamp(ventHighF, 60, 130))));
    }

    public ComfortConfig(
            boolean welcomeDismiss,
            int welcomeDelayMs,
            boolean climateEnabled,
            int heatHighF,
            int heatMedF,
            int heatLowF,
            boolean wheelEnabled,
            int wheelF,
            String passengerPolicy,
            boolean passengerAutoDetect,
            String rearLeftPolicy,
            String rearRightPolicy,
            boolean ventEnabled,
            int ventLowF,
            int ventMedF,
            int ventHighF,
            boolean toastOnApply,
            int heatHighC,
            int heatMedC,
            int heatLowC,
            int wheelC,
            int ventLowC,
            int ventMedC,
            int ventHighC) {
        this.welcomeDismiss = welcomeDismiss;
        this.welcomeDelayMs = clamp(welcomeDelayMs, 0, 10000);
        this.climateEnabled = climateEnabled;
        int hh = clamp(heatHighF, -30, 80);
        int hm = Math.max(hh, clamp(heatMedF, -30, 85));
        int hl = Math.max(hm, clamp(heatLowF, -30, 90));
        this.heatHighF = hh;
        this.heatMedF = hm;
        this.heatLowF = hl;
        this.wheelEnabled = wheelEnabled;
        this.wheelF = clamp(wheelF, -30, 90);
        this.passengerPolicy = normPolicy(passengerPolicy);
        this.passengerAutoDetect = passengerAutoDetect;
        this.rearLeftPolicy = normPolicy(rearLeftPolicy);
        this.rearRightPolicy = normPolicy(rearRightPolicy);
        this.ventEnabled = ventEnabled;
        int vl = Math.max(hl + 5, clamp(ventLowF, 60, 120));
        int vm = Math.max(vl, clamp(ventMedF, 60, 125));
        int vh = Math.max(vm, clamp(ventHighF, 60, 130));
        this.ventLowF = vl;
        this.ventMedF = vm;
        this.ventHighF = vh;
        this.toastOnApply = toastOnApply;

        int hhC = clamp(heatHighC, -35, 27);
        int hmC = Math.max(hhC, clamp(heatMedC, -35, 29));
        int hlC = Math.max(hmC, clamp(heatLowC, -35, 32));
        this.heatHighC = hhC;
        this.heatMedC = hmC;
        this.heatLowC = hlC;
        this.wheelC = clamp(wheelC, -35, 32);
        int vlC = Math.max(hlC + 2, clamp(ventLowC, 15, 49));
        int vmC = Math.max(vlC, clamp(ventMedC, 15, 52));
        int vhC = Math.max(vmC, clamp(ventHighC, 15, 55));
        this.ventLowC = vlC;
        this.ventMedC = vmC;
        this.ventHighC = vhC;
    }

    public static ComfortConfig defaults() {
        return new ComfortConfig(
                true, 800,
                true, 25, 38, 50,
                true, 45,
                POLICY_OFF, true, POLICY_OFF, POLICY_OFF,
                true, 75, 85, 95,
                false);
    }

    public static int fToC(int f) {
        return Math.round((f - 32f) * 5f / 9f);
    }

    public static int cToF(int c) {
        return Math.round(c * 9f / 5f + 32f);
    }

    public ComfortConfig withWelcomeDismiss(boolean v) {
        return copyWith(v, welcomeDelayMs, climateEnabled, wheelEnabled, ventEnabled, toastOnApply,
                passengerPolicy, passengerAutoDetect, rearLeftPolicy, rearRightPolicy);
    }

    public ComfortConfig withWelcomeDelayMs(int ms) {
        return copyWith(welcomeDismiss, ms, climateEnabled, wheelEnabled, ventEnabled, toastOnApply,
                passengerPolicy, passengerAutoDetect, rearLeftPolicy, rearRightPolicy);
    }

    public ComfortConfig withClimateEnabled(boolean v) {
        return copyWith(welcomeDismiss, welcomeDelayMs, v, wheelEnabled, ventEnabled, toastOnApply,
                passengerPolicy, passengerAutoDetect, rearLeftPolicy, rearRightPolicy);
    }

    public ComfortConfig withWheelEnabled(boolean v) {
        return copyWith(welcomeDismiss, welcomeDelayMs, climateEnabled, v, ventEnabled, toastOnApply,
                passengerPolicy, passengerAutoDetect, rearLeftPolicy, rearRightPolicy);
    }

    public ComfortConfig withVentEnabled(boolean v) {
        return copyWith(welcomeDismiss, welcomeDelayMs, climateEnabled, wheelEnabled, v, toastOnApply,
                passengerPolicy, passengerAutoDetect, rearLeftPolicy, rearRightPolicy);
    }

    public ComfortConfig withToastOnApply(boolean v) {
        return copyWith(welcomeDismiss, welcomeDelayMs, climateEnabled, wheelEnabled, ventEnabled, v,
                passengerPolicy, passengerAutoDetect, rearLeftPolicy, rearRightPolicy);
    }

    public ComfortConfig withPassengerPolicy(String p) {
        return copyWith(welcomeDismiss, welcomeDelayMs, climateEnabled, wheelEnabled, ventEnabled, toastOnApply,
                p, passengerAutoDetect, rearLeftPolicy, rearRightPolicy);
    }

    public ComfortConfig withPassengerAutoDetect(boolean v) {
        return copyWith(welcomeDismiss, welcomeDelayMs, climateEnabled, wheelEnabled, ventEnabled, toastOnApply,
                passengerPolicy, v, rearLeftPolicy, rearRightPolicy);
    }

    public ComfortConfig withRearLeftPolicy(String p) {
        return copyWith(welcomeDismiss, welcomeDelayMs, climateEnabled, wheelEnabled, ventEnabled, toastOnApply,
                passengerPolicy, passengerAutoDetect, p, rearRightPolicy);
    }

    public ComfortConfig withRearRightPolicy(String p) {
        return copyWith(welcomeDismiss, welcomeDelayMs, climateEnabled, wheelEnabled, ventEnabled, toastOnApply,
                passengerPolicy, passengerAutoDetect, rearLeftPolicy, p);
    }

    public ComfortConfig withDefaultThresholds() {
        ComfortConfig d = defaults();
        return new ComfortConfig(
                welcomeDismiss, welcomeDelayMs,
                climateEnabled, d.heatHighF, d.heatMedF, d.heatLowF,
                wheelEnabled, d.wheelF,
                passengerPolicy, passengerAutoDetect,
                rearLeftPolicy, rearRightPolicy,
                ventEnabled, d.ventLowF, d.ventMedF, d.ventHighF,
                toastOnApply,
                d.heatHighC, d.heatMedC, d.heatLowC,
                d.wheelC, d.ventLowC, d.ventMedC, d.ventHighC);
    }

    private ComfortConfig copyWith(
            boolean nextWelcomeDismiss,
            int nextWelcomeDelayMs,
            boolean nextClimateEnabled,
            boolean nextWheelEnabled,
            boolean nextVentEnabled,
            boolean nextToastOnApply,
            String nextPassengerPolicy,
            boolean nextPassengerAutoDetect,
            String nextRearLeftPolicy,
            String nextRearRightPolicy) {
        return new ComfortConfig(
                nextWelcomeDismiss, nextWelcomeDelayMs,
                nextClimateEnabled, heatHighF, heatMedF, heatLowF,
                nextWheelEnabled, wheelF,
                nextPassengerPolicy, nextPassengerAutoDetect,
                nextRearLeftPolicy, nextRearRightPolicy,
                nextVentEnabled, ventLowF, ventMedF, ventHighF,
                nextToastOnApply,
                heatHighC, heatMedC, heatLowC,
                wheelC, ventLowC, ventMedC, ventHighC);
    }

    public ComfortConfig withAdjustedThreshold(int which, int delta) {
        return withAdjustedThreshold(which, delta, false);
    }

    public ComfortConfig withAdjustedThreshold(int which, int delta, boolean metric) {
        if (metric) {
            int hhC = heatHighC, hmC = heatMedC, hlC = heatLowC, wC = wheelC;
            int vlC = ventLowC, vmC = ventMedC, vhC = ventHighC;
            if (which == 0) {
                hhC = clamp(hhC + delta, -35, 27);
                if (hmC < hhC) hmC = hhC;
                if (hlC < hmC) hlC = hmC;
                if (vlC < hlC + 3) vlC = hlC + 3;
                if (vmC < vlC) vmC = vlC;
                if (vhC < vmC) vhC = vmC;
            } else if (which == 1) {
                hmC = clamp(hmC + delta, -35, 29);
                if (hhC > hmC) hhC = hmC;
                if (hlC < hmC) hlC = hmC;
                if (vlC < hlC + 3) vlC = hlC + 3;
                if (vmC < vlC) vmC = vlC;
                if (vhC < vmC) vhC = vmC;
            } else if (which == 2) {
                hlC = clamp(hlC + delta, -35, 32);
                if (hmC > hlC) hmC = hlC;
                if (hhC > hmC) hhC = hmC;
                if (vlC < hlC + 3) vlC = hlC + 3;
                if (vmC < vlC) vmC = vlC;
                if (vhC < vmC) vhC = vmC;
            } else if (which == 3) {
                wC = clamp(wC + delta, -35, 32);
            } else if (which == 4) {
                vlC = clamp(vlC + delta, 15, 49);
                if (hlC > vlC - 3) hlC = vlC - 3;
                if (hmC > hlC) hmC = hlC;
                if (hhC > hmC) hhC = hmC;
                if (vmC < vlC) vmC = vlC;
                if (vhC < vmC) vhC = vmC;
            } else if (which == 5) {
                vmC = clamp(vmC + delta, 15, 52);
                if (vlC > vmC) vlC = vmC;
                if (hlC > vlC - 3) hlC = vlC - 3;
                if (hmC > hlC) hmC = hlC;
                if (hhC > hmC) hhC = hmC;
                if (vhC < vmC) vhC = vmC;
            } else if (which == 6) {
                vhC = clamp(vhC + delta, 15, 55);
                if (vmC > vhC) vmC = vhC;
                if (vlC > vmC) vlC = vmC;
                if (hlC > vlC - 3) hlC = vlC - 3;
                if (hmC > hlC) hmC = hlC;
                if (hhC > hmC) hhC = hmC;
            }
            return new ComfortConfig(
                    welcomeDismiss, welcomeDelayMs,
                    climateEnabled, cToF(hhC), cToF(hmC), cToF(hlC),
                    wheelEnabled, cToF(wC),
                    passengerPolicy, passengerAutoDetect,
                    rearLeftPolicy, rearRightPolicy,
                    ventEnabled, cToF(vlC), cToF(vmC), cToF(vhC),
                    toastOnApply,
                    hhC, hmC, hlC, wC, vlC, vmC, vhC);
        }

        int hh = heatHighF, hm = heatMedF, hl = heatLowF, wf = wheelF;
        int vl = ventLowF, vm = ventMedF, vh = ventHighF;
        if (which == 0) {
            hh = clamp(hh + delta, -30, 80);
            if (hm < hh) hm = hh;
            if (hl < hm) hl = hm;
            if (vl < hl + 5) vl = hl + 5;
            if (vm < vl) vm = vl;
            if (vh < vm) vh = vm;
        } else if (which == 1) {
            hm = clamp(hm + delta, -30, 85);
            if (hh > hm) hh = hm;
            if (hl < hm) hl = hm;
            if (vl < hl + 5) vl = hl + 5;
            if (vm < vl) vm = vl;
            if (vh < vm) vh = vm;
        } else if (which == 2) {
            hl = clamp(hl + delta, -30, 90);
            if (hm > hl) hm = hl;
            if (hh > hm) hh = hm;
            if (vl < hl + 5) vl = hl + 5;
            if (vm < vl) vm = vl;
            if (vh < vm) vh = vm;
        } else if (which == 3) {
            wf = clamp(wf + delta, -30, 90);
        } else if (which == 4) {
            vl = clamp(vl + delta, 60, 120);
            if (hl > vl - 5) hl = vl - 5;
            if (hm > hl) hm = hl;
            if (hh > hm) hh = hm;
            if (vm < vl) vm = vl;
            if (vh < vm) vh = vm;
        } else if (which == 5) {
            vm = clamp(vm + delta, 60, 125);
            if (vl > vm) vl = vm;
            if (hl > vl - 5) hl = vl - 5;
            if (hm > hl) hm = hl;
            if (hh > hm) hh = hm;
            if (vh < vm) vh = vm;
        } else if (which == 6) {
            vh = clamp(vh + delta, 60, 130);
            if (vm > vh) vm = vh;
            if (vl > vm) vl = vm;
            if (hl > vl - 5) hl = vl - 5;
            if (hm > hl) hm = hl;
            if (hh > hm) hh = hm;
        }
        return new ComfortConfig(
                welcomeDismiss, welcomeDelayMs,
                climateEnabled, hh, hm, hl,
                wheelEnabled, wf,
                passengerPolicy, passengerAutoDetect,
                rearLeftPolicy, rearRightPolicy,
                ventEnabled, vl, vm, vh,
                toastOnApply);
    }

    public static String normPolicy(String p) {
        if (POLICY_MATCH.equals(p) || POLICY_LOWER.equals(p)) return p;
        return POLICY_OFF;
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
