package com.kaan.xrc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Status screen: shows whether the bridge daemon runs, its recent log, and start/stop buttons. */
public class MainActivity extends Activity {
    private TextView status, logView;
    private Button netflixBtn, primeBtn;
    static final int BIT_NETFLIX = 10, BIT_PRIME = 13;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 48, 64, 48);
        root.setBackgroundColor(Color.rgb(18, 18, 22));

        TextView title = new TextView(this);
        title.setText("Xiaomi RC Bridge");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(20);
        status.setPadding(0, 16, 0, 16);
        status.setTextColor(Color.LTGRAY);
        status.setText("Checking status…");
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        Button start = new Button(this);
        start.setText("Start / Restart");
        start.setOnClickListener(v -> runAsync(Bridge.startCmd(true), "Starting…"));
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> runAsync(Bridge.stopCmd() + "; echo stopped", "Stopping…"));
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refresh());
        Button pair = new Button(this);
        pair.setText("Pair remote");
        pair.setOnClickListener(v -> startPairing());
        buttons.addView(pair);
        buttons.addView(start);
        buttons.addView(stop);
        buttons.addView(refresh);
        root.addView(buttons);

        LinearLayout appButtons = new LinearLayout(this);
        appButtons.setPadding(0, 16, 0, 0);
        netflixBtn = new Button(this);
        netflixBtn.setOnClickListener(v -> pickApp(BIT_NETFLIX, "Netflix button", "Netflix"));
        primeBtn = new Button(this);
        primeBtn.setOnClickListener(v -> pickApp(BIT_PRIME, "Prime Video button", "Prime Video"));
        appButtons.addView(netflixBtn);
        appButtons.addView(primeBtn);
        root.addView(appButtons);
        showAppButtons(null);

        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.GRAY);
        logView.setPadding(0, 24, 0, 0);
        root.addView(logView);

        setContentView(root);
        start.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ensures adb is authorized and the daemon is up (no-op if already running)
        runAsync(Bridge.startCmd(false), "Connecting to adb…");
    }

    void runAsync(String cmd, String msg) {
        if (busy) return;
        busy = true;
        status.setTextColor(Color.LTGRAY);
        status.setText(msg);
        new Thread(() -> {
            String err = null;
            try {
                Bridge.run(this, cmd);
            } catch (AdbClient.AuthPendingException e) {
                err = "On the TV's 'Allow USB debugging?' dialog, tick\n"
                        + "'Always allow' and press Allow, then Refresh.";
            } catch (Exception e) {
                err = "Cannot connect to adb (127.0.0.1:5555): " + e.getMessage()
                        + "\nMake sure network debugging is enabled in Developer options.";
            }
            busy = false;
            if (err != null) {
                String e2 = err;
                ui.post(() -> { status.setTextColor(Color.rgb(255, 170, 60)); status.setText(e2); });
            } else {
                ui.postDelayed(this::refresh, 1500);
            }
        }).start();
    }

    void startPairing() {
        if (busy) return;
        busy = true;
        status.setTextColor(Color.rgb(120, 180, 255));
        status.setText("Searching for the remote… Hold it close to the TV and press and hold\n"
                + "Home + Menu (☰) together; release when the light starts blinking.");
        new Thread(() -> {
            try {
                Bridge.run(this, Bridge.pairCmd());
                long end = System.currentTimeMillis() + 75000;
                String out = "";
                while (System.currentTimeMillis() < end && !out.contains("DONE")) {
                    Thread.sleep(1000);
                    out = Bridge.run(this, Bridge.pairLogCmd());
                    String o = out;
                    ui.post(() -> logView.setText(o));
                }
                String o = out;
                ui.post(() -> {
                    if (o.contains("PAIRED")) {
                        status.setTextColor(Color.rgb(90, 220, 120));
                        status.setText("✓ Remote paired, bridge restarted. The buttons should work now.");
                    } else {
                        status.setTextColor(Color.rgb(255, 90, 90));
                        status.setText("Pairing failed. Bring the remote closer and try again\n"
                                + "(if Home + Menu does not work, try Home + Back).");
                    }
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("Error: " + e.getMessage()));
            }
            busy = false;
            ui.postDelayed(this::refresh, 4000);
        }).start();
    }

    /** Launchable apps: label -> "package/activity" (TV launcher entry preferred). */
    Map<String, String> launchableApps() {
        PackageManager pm = getPackageManager();
        Map<String, String> byPkg = new LinkedHashMap<>();
        Map<String, String> labels = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
                String comp = pkg + "/" + r.activityInfo.name;
                byPkg.put(pkg, comp);
                String label = r.loadLabel(pm).toString();
                if (labels.containsKey(label)) label += " (" + pkg + ")";
                labels.put(label, comp);
            }
        }
        return labels;
    }

    String labelFor(String target) {
        if (target == null) return null;
        String pkg = target.contains("/") ? target.substring(0, target.indexOf('/')) : target;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** keymap file content -> button captions */
    void showAppButtons(String keymap) {
        String nf = null, pv = null;
        if (keymap != null) {
            for (String line : keymap.split("\n")) {
                line = line.trim();
                if (line.startsWith(BIT_NETFLIX + "=app:")) nf = line.substring(line.indexOf(':') + 1);
                if (line.startsWith(BIT_PRIME + "=app:")) pv = line.substring(line.indexOf(':') + 1);
            }
        }
        netflixBtn.setText("Netflix button → " + (nf != null ? labelFor(nf) : "Netflix (default)"));
        primeBtn.setText("Prime button → " + (pv != null ? labelFor(pv) : "Prime Video (default)"));
    }

    void pickApp(int bit, String title, String defaultName) {
        Map<String, String> apps = launchableApps();
        List<String> names = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        names.add("↺ Default (" + defaultName + ")");
        targets.add(null);
        for (Map.Entry<String, String> e : apps.entrySet()) {
            names.add(e.getKey());
            targets.add(e.getValue());
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title + " opens…")
                .setItems(names.toArray(new String[0]), (d, which) -> saveApp(bit, targets.get(which), names.get(which)))
                .show();
    }

    void saveApp(int bit, String target, String name) {
        status.setTextColor(Color.LTGRAY);
        status.setText("Saving…");
        new Thread(() -> {
            try {
                Bridge.run(this, Bridge.setAppCmd(bit, target));
                String km = Bridge.run(this, Bridge.readKeymapCmd());
                ui.post(() -> {
                    showAppButtons(km);
                    status.setTextColor(Color.rgb(90, 220, 120));
                    status.setText("✓ Saved: button now opens " + name.replace("↺ ", "") + " (active immediately)");
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("Cannot save: " + e.getMessage()));
            }
        }).start();
    }

    void refresh() {
        new Thread(() -> {
            try {
                String km = Bridge.run(this, Bridge.readKeymapCmd());
                ui.post(() -> showAppButtons(km));
                String out = Bridge.run(this, Bridge.statusCmd());
                boolean running = out.startsWith("RUNNING");
                String log = out.substring(out.indexOf('\n') + 1);
                ui.post(() -> {
                    status.setTextColor(running ? Color.rgb(90, 220, 120) : Color.rgb(255, 90, 90));
                    status.setText(running ? "● Bridge running — remote keys are forwarded" : "● Bridge stopped");
                    logView.setText(log);
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("Cannot get status: " + e.getMessage()));
            }
        }).start();
    }
}
