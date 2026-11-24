package dev.lemoyne.comfort;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
import hmgapi.client.HmgApi;
import org.json.JSONObject;

/** Narrow versioned threshold bridge for the head unit's projection process. */
public final class ThresholdProvider extends ContentProvider {
    private static final Object LOCK = new Object();
    private static final String[] IDS = {"heat_high", "heat_med", "heat_low", "vent_low", "vent_med", "vent_high"};
    private static final int[] INDEX = {0, 1, 2, 4, 5, 6};
    @Override public boolean onCreate() { return true; }
    private void authorize() {
        String caller = getCallingPackage();
        if (!"com.daudio.app.dipo".equals(caller))
            throw new SecurityException("Threshold bridge requires the projection application");
        try {
            int uid = getContext().getPackageManager().getApplicationInfo(caller, 0).uid;
            if (uid != Binder.getCallingUid()) throw new SecurityException("Caller UID mismatch");
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new SecurityException("Projection package unavailable");
        }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        authorize();
        if (!"get".equals(method) && !"adjust".equals(method)) throw new IllegalArgumentException("Unknown operation");
        int index = -1, delta = 0;
        if ("adjust".equals(method)) {
            for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(arg)) index = INDEX[i];
            delta = extras == null ? 0 : extras.getInt("delta", 0);
            if (index < 0 || (delta != -1 && delta != 1)) throw new IllegalArgumentException("Invalid threshold adjustment");
        }
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (LOCK) {
                HmgApi api = HmgApi.connect().timeout(3000);
                if (ComfortEngine.isModExplicitlyDisabled(api)) throw new IllegalStateException("Comfort disabled");
                JSONObject stored = api.settings();
                if (stored == null) throw new IllegalStateException("Settings unavailable");
                ComfortEngine engine = ComfortEngine.getInstance(getContext());
                ComfortEngine.Snapshot snapshot = engine.snapshot();
                if (!snapshot.connected) throw new IllegalStateException("Comfort telemetry unavailable");
                boolean metric = snapshot.metricUnits;
                ComfortConfig config = ComfortEngine.parseConfig(stored);
                if (index >= 0) {
                    if (extras == null || !extras.containsKey("metric") || extras.getBoolean("metric") != metric)
                        throw new IllegalArgumentException("Temperature unit changed; refresh required");
                    ComfortConfig next = config.withAdjustedThreshold(index, delta, metric);
                    JSONObject normalized = ComfortEngine.toJson(next);
                    for (String id : IDS) for (String suffix : new String[]{"_f","_c"}) {
                        String key = id + suffix;
                        stored.put(key, normalized.get(key));
                    }
                    api.saveSettings(stored);
                    engine.requestSettingsReload();
                    config = ComfortEngine.parseConfig(api.settings());
                    Log.i("HmgComfort", "[hmgapi-comfort] threshold bridge adjusted " + arg + " by " + delta);
                }
                Bundle out = new Bundle();
                out.putInt("version", 1);
                out.putBoolean("metric", metric);
                int[] values = metric ? new int[]{config.heatHighC,config.heatMedC,config.heatLowC,config.ventLowC,config.ventMedC,config.ventHighC}
                        : new int[]{config.heatHighF,config.heatMedF,config.heatLowF,config.ventLowF,config.ventMedF,config.ventHighF};
                for (int i = 0; i < IDS.length; i++) out.putInt(IDS[i], values[i]);
                return out;
            }
        } catch (Exception e) {
            Log.w("HmgComfort", "[hmgapi-comfort] threshold bridge request failed", e);
            throw new IllegalStateException("Comfort thresholds unavailable");
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
