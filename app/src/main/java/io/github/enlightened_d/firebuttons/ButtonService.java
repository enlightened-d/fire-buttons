package io.github.enlightened_d.firebuttons;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remaps the app buttons of a Fire TV remote.
 *
 * Fire OS handles those buttons in its own key policy (OZ-DCS / FireTVKeyPolicyManager), which opens the
 * app tied to the button, or its Appstore page when that app is missing or disabled. No accessibility key
 * filter ever sees them, so they cannot be remapped the usual way. Fire OS does log each press first
 * ("KeyPressReceiver: Remote button pressed. keyCode: 293"). So: when the Appstore comes to the front,
 * read the last few seconds of the log, and if a mapped button was pressed, open its app instead.
 *
 * Fail-safe: the Appstore opens in two steps (pdi.AppLaunchActivity, then details.AppDetailsActivity up
 * to ~5 s later), and the second can land on top of the app we opened. For GUARD_S after a handled press,
 * whenever the Appstore is in front again, bring our app back; a timer re-checks too, in case no window
 * event arrives.
 *
 * Nothing leaves the device: the app has no INTERNET permission and keeps nothing it reads.
 */
public class ButtonService extends AccessibilityService {
    private static final String TAG = "FireButtons";
    private static final String STORE = "com.amazon.venezia";
    private static final Pattern PRESS =
            Pattern.compile("^\\s*(\\d+\\.\\d+).*KeyPressReceiver: Remote button pressed\\. keyCode: (\\d+)");
    private static final double WINDOW_S = 6.0;   // how old a press in the log may be
    private static final double GUARD_S = 8.0;    // how long after a press the Appstore is pushed back
    private static final long[] RECHECK_MS = {1000, 2500, 4000, 6000};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseArray<String> buttonApps = new SparseArray<>();
    private volatile String foreground = "";
    private volatile double handledPress = 0;      // log time of the press we acted on
    private volatile long handledAtMs = 0;         // wall clock when we acted on it
    private volatile String handledPkg = null;

    @Override
    protected void onServiceConnected() {
        buttonApps.clear();
        for (String entry : getResources().getStringArray(R.array.button_apps)) {
            int colon = entry.indexOf(':');
            if (colon <= 0) continue;
            try {
                buttonApps.put(Integer.parseInt(entry.substring(0, colon).trim()), entry.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                Log.w(TAG, "ignoring button_apps entry " + entry);
            }
        }
        Log.i(TAG, "mapped " + buttonApps.size() + " buttons");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) return; // overlays, not apps
        foreground = pkg;
        if (!STORE.equals(pkg)) return;
        // Read the log first even inside the guard: a new press of another button must win over the
        // fail-safe for the previous one.
        new Thread(this::check, "firebuttons").start();
    }

    private boolean inGuard() {
        return handledPkg != null && System.currentTimeMillis() - handledAtMs < GUARD_S * 1000;
    }

    private void check() {
        double nowS = System.currentTimeMillis() / 1000.0;
        double pressS = 0;
        int code = -1;
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "epoch", "-t", "400").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.contains("KeyPressReceiver")) continue;
                    Matcher m = PRESS.matcher(line);
                    if (!m.find()) continue;
                    double t = Double.parseDouble(m.group(1));
                    if (nowS - t <= WINDOW_S && t > pressS) { pressS = t; code = Integer.parseInt(m.group(2)); }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the log: was READ_LOGS granted over ADB?", e);
            return;
        } finally {
            if (p != null) p.destroy();
        }
        String pkg = buttonApps.get(code);
        if (pkg == null || pressS <= handledPress) {
            if (inGuard()) bringBack("store in front again");
            return;
        }
        handledPress = pressS;
        handledPkg = pkg;
        handledAtMs = System.currentTimeMillis();
        Log.i(TAG, "keyCode " + code + " -> " + pkg);
        main.post(() -> launch(pkg));
        for (long d : RECHECK_MS) {
            main.postDelayed(() -> { if (STORE.equals(foreground) && inGuard()) bringBack("timer"); }, d);
        }
    }

    private void bringBack(String why) {
        String pkg = handledPkg;
        if (pkg == null) return;
        Log.i(TAG, "fail-safe (" + why + ") -> " + pkg);
        main.post(() -> launch(pkg));
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) { Log.w(TAG, pkg + " is not installed or has no launch entry"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    @Override
    public void onInterrupt() { }
}
