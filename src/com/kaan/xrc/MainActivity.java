package com.kaan.xrc;

import android.app.Activity;
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
        status.setText("Durum kontrol ediliyor…");
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        Button start = new Button(this);
        start.setText("Başlat / Yeniden başlat");
        start.setOnClickListener(v -> runAsync(Bridge.startCmd(true), "Başlatılıyor…"));
        Button stop = new Button(this);
        stop.setText("Durdur");
        stop.setOnClickListener(v -> runAsync(Bridge.stopCmd() + "; echo stopped", "Durduruluyor…"));
        Button refresh = new Button(this);
        refresh.setText("Yenile");
        refresh.setOnClickListener(v -> refresh());
        Button pair = new Button(this);
        pair.setText("Kumandayı eşle");
        pair.setOnClickListener(v -> startPairing());
        buttons.addView(pair);
        buttons.addView(start);
        buttons.addView(stop);
        buttons.addView(refresh);
        root.addView(buttons);

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
        runAsync(Bridge.startCmd(false), "adb'ye bağlanılıyor…");
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
                err = "TV ekranındaki 'Hata ayıklamaya izin verilsin mi?' penceresinde\n"
                        + "'Her zaman izin ver' kutusunu işaretleyip İzin ver'e basın, sonra Yenile.";
            } catch (Exception e) {
                err = "adb'ye bağlanılamadı (127.0.0.1:5555): " + e.getMessage()
                        + "\nGeliştirici seçeneklerinde ağ hata ayıklamanın açık olduğundan emin olun.";
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
        status.setText("Kumanda aranıyor… Kumandayı TV'ye yaklaştırıp Home + Menü (☰) tuşlarına\n"
                + "birlikte basılı tutun, ışık yanıp sönmeye başlayınca bırakın.");
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
                        status.setText("✓ Kumanda eşlendi, köprü yeniden başlatıldı. Tuşlara basabilirsiniz.");
                    } else {
                        status.setTextColor(Color.rgb(255, 90, 90));
                        status.setText("Eşleme başarısız. Kumandayı yaklaştırıp tekrar deneyin\n"
                                + "(Home + Menü olmazsa Home + Geri tuşlarını deneyin).");
                    }
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("Hata: " + e.getMessage()));
            }
            busy = false;
            ui.postDelayed(this::refresh, 4000);
        }).start();
    }

    void refresh() {
        new Thread(() -> {
            try {
                String out = Bridge.run(this, Bridge.statusCmd());
                boolean running = out.startsWith("RUNNING");
                String log = out.substring(out.indexOf('\n') + 1);
                ui.post(() -> {
                    status.setTextColor(running ? Color.rgb(90, 220, 120) : Color.rgb(255, 90, 90));
                    status.setText(running ? "● Köprü çalışıyor — kumanda tuşları aktarılıyor" : "● Köprü durdu");
                    logView.setText(log);
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("Durum alınamadı: " + e.getMessage()));
            }
        }).start();
    }
}
