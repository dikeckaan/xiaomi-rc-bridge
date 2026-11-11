package com.kaan.xrc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Pairing helper (shell uid, like "Bluetooth Scanner" but automatic): BLE-scans for a Xiaomi remote in pairing
 * mode, removes a stale bond if there is one, bonds over LE and waits until the bond is complete.
 *   CLASSPATH=$APK app_process / com.kaan.xrc.Pairer [seconds] [MAC]
 * Exit code 0 = paired, 1 = not found / failed.
 */
public class Pairer {
    static final ParcelUuid HID = ParcelUuid.fromString("00001812-0000-1000-8000-00805f9b34fb");

    static Handler h;
    static BluetoothAdapter ad;
    static BluetoothLeScanner scanner;
    static String wantMac;
    static volatile boolean found;
    static final java.util.Set<String> seen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    static void log(String s) { System.out.println(s); System.out.flush(); }

    static boolean isXiaomi(ScanResult r) {
        String mac = r.getDevice().getAddress();
        if (wantMac != null) return wantMac.equalsIgnoreCase(mac);
        String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
        if (n == null) n = r.getDevice().getName();
        if (n == null) return false;
        n = n.toLowerCase(Locale.ROOT);
        return n.contains("xiaomi") || n.contains("mi rc") || n.contains("mi remote") || n.contains("mitv");
    }

    static final ScanCallback cb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            seen.add(r.getDevice().getAddress());
            if (found || !isXiaomi(r)) return;
            found = true;
            BluetoothDevice d = r.getDevice();
            String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : d.getName();
            List<ParcelUuid> u = r.getScanRecord() != null ? r.getScanRecord().getServiceUuids() : null;
            log("FOUND " + d.getAddress() + " \"" + n + "\" rssi=" + r.getRssi() + (u != null && u.contains(HID) ? " HID" : ""));
            h.post(() -> pair(d));
        }

        @Override
        public void onScanFailed(int err) {
            log("ERROR scan failed: " + err);
            System.exit(1);
        }
    };

    static void stopScan() {
        try { scanner.stopScan(cb); } catch (Exception ignored) { }
    }

    static void pair(BluetoothDevice d) {
        stopScan();
        try {
            if (d.getBondState() != BluetoothDevice.BOND_NONE) {
                log("removing old pairing…");
                call(d, "removeBond");
                waitBond(d, BluetoothDevice.BOND_NONE, 10000);
                SystemClock.sleep(1000);
            }
            log("pairing " + d.getAddress() + "…");
            boolean ok;
            try {
                Method m = BluetoothDevice.class.getMethod("createBond", int.class);
                ok = (Boolean) m.invoke(d, BluetoothDevice.TRANSPORT_LE);
            } catch (NoSuchMethodException e) {
                ok = d.createBond();
            }
            if (!ok) { log("ERROR createBond rejected"); System.exit(1); }
            if (waitBond(d, BluetoothDevice.BOND_BONDED, 40000)) {
                log("PAIRED " + d.getAddress() + " (" + d.getName() + ")");
                System.exit(0);
            }
            log("ERROR pairing did not complete (state " + d.getBondState() + ")");
        } catch (Exception e) {
            log("ERROR " + e);
        }
        System.exit(1);
    }

    static Object call(Object o, String name) throws Exception {
        Method m = o.getClass().getMethod(name);
        return m.invoke(o);
    }

    static boolean waitBond(BluetoothDevice d, int state, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        int last = -1;
        while (SystemClock.uptimeMillis() < end) {
            int s = d.getBondState();
            if (s != last) { log("  bond state " + (s == 10 ? "none" : s == 11 ? "bonding" : s == 12 ? "bonded" : s)); last = s; }
            if (s == state) return true;
            SystemClock.sleep(250);
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        wantMac = args.length > 1 ? args[1] : null;
        Looper.prepareMainLooper();
        HandlerThread ht = new HandlerThread("pair");
        ht.start();
        h = new Handler(ht.getLooper());

        ad = Daemon.adapter();
        if (!ad.isEnabled()) {
            log("bluetooth is off, turning it on…");
            try { call(ad, "enable"); } catch (Exception e) { log("  " + e); }
            for (int i = 0; i < 40 && !ad.isEnabled(); i++) SystemClock.sleep(250);
        }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { log("ERROR no BLE scanner"); System.exit(1); }
        ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, st, cb);
        log("SCANNING " + seconds + "s — kumandayı eşleme moduna al (Home + Menü ☰ tuşlarına birlikte basılı tut)");
        h.postDelayed(() -> {
            if (!found) { stopScan(); log("ERROR kumanda bulunamadı (" + seen.size() + " başka BLE cihazı görüldü)"); System.exit(1); }
        }, seconds * 1000L);
        Looper.loop();
    }
}
