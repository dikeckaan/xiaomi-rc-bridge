package com.kaan.xrc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Diagnostic (shell uid): subscribe to every notifiable characteristic in the HID and Xiaomi vendor services,
 * print each notification with its report id / handle and a relative timestamp. Raw payloads also go to
 * /data/local/tmp/voice.bin as [u16 handle][u16 len][bytes] records for offline codec analysis.
 *   CLASSPATH=$APK app_process / com.kaan.xrc.VoiceProbe [seconds]
 */
public class VoiceProbe {
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final UUID RPT_REF = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb");

    static Handler h;
    static final Deque<Runnable> ops = new ArrayDeque<>();
    static boolean busy;
    static long t0;
    static FileOutputStream raw;
    static final java.util.Map<Integer, String> names = new java.util.HashMap<>();

    static void log(String s) { System.out.println(s); System.out.flush(); }

    static void next() { if (busy || ops.isEmpty()) return; busy = true; ops.poll().run(); }
    static void done() { busy = false; next(); }
    static void retry(Runnable r) { h.postDelayed(() -> { ops.addFirst(r); busy = false; next(); }, 400); }

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        Looper.prepareMainLooper();
        HandlerThread ht = new HandlerThread("probe");
        ht.start();
        h = new Handler(ht.getLooper());
        raw = new FileOutputStream("/data/local/tmp/voice.bin");
        BluetoothAdapter ad = Daemon.adapter();
        BluetoothDevice dev = Daemon.findRemote(ad, null);
        log("remote " + dev.getAddress());
        dev.connectGatt(null, true, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                log("conn " + state);
                if (state == BluetoothProfile.STATE_CONNECTED) h.post(g::discoverServices);
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                h.post(() -> {
                    for (BluetoothGattService s : g.getServices()) {
                        String su = s.getUuid().toString();
                        if (!su.startsWith("00001812") && !su.contains("3c17-d293")) continue;
                        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                            int hd = c.getInstanceId();
                            names.put(hd, c.getUuid().toString().substring(4, 8) + "@" + Integer.toHexString(hd));
                            BluetoothGattDescriptor ref = c.getDescriptor(RPT_REF);
                            if (ref != null) ops.add(new Runnable() { public void run() { if (!g.readDescriptor(ref)) retry(this); } });
                            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                                BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                                ops.add(new Runnable() { public void run() {
                                    g.setCharacteristicNotification(c, true);
                                    if (d == null) { done(); return; }
                                    if (g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != 0) retry(this);
                                } });
                            }
                        }
                    }
                    ops.add(() -> { t0 = SystemClock.uptimeMillis(); log("=== READY: mikrofona bas, konus ==="); done(); });
                    next();
                });
            }

            @Override
            public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int st, byte[] v) {
                h.post(() -> {
                    int hd = d.getCharacteristic().getInstanceId();
                    if (v != null && v.length >= 2) names.put(hd, names.get(hd) + "/id" + (v[0] & 0xff) + "t" + v[1]);
                    done();
                });
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
                h.post(VoiceProbe::done);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
                long t = SystemClock.uptimeMillis() - t0;
                int hd = c.getInstanceId();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(v.length, 24); i++) sb.append(String.format("%02x", v[i]));
                log(String.format("%6d %-22s len=%3d %s%s", t, names.get(hd), v.length, sb, v.length > 24 ? "…" : ""));
                try {
                    raw.write(new byte[]{(byte) hd, (byte) (hd >> 8), (byte) v.length, (byte) (v.length >> 8)});
                    raw.write(v);
                    raw.flush();
                } catch (Exception ignored) { }
            }
        }, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h);
        h.postDelayed(() -> { log("=== done ==="); System.exit(0); }, seconds * 1000L);
        Looper.loop();
    }
}
