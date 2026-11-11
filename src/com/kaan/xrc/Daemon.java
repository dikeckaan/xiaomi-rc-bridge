package com.kaan.xrc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;
import android.net.LocalServerSocket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Xiaomi RC bridge daemon. Runs as the shell uid via app_process:
 *   CLASSPATH=/data/local/tmp/xrcd.jar app_process / com.kaan.xrc.Daemon [MAC]
 *
 * Why: the remote exposes 28 HID report characteristics; Android's HOGP host only tracks the first 20
 * (BTA_HH_LE_RPT_MAX), so the Consumer Control input report (Report ID 1, the last one) is never
 * subscribed and every key is lost. We subscribe to it over GATT ourselves and inject the keys.
 */
public class Daemon {
    static final UUID HID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb");
    static final UUID REPORT = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final UUID RPT_REF = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb");

    static final String KEYMAP_FILE = "/data/local/tmp/xrc-keymap.txt";
    static final int REPEAT_DELAY_MS = 400;
    static final int REPEAT_INTERVAL_MS = 80;

    /**
     * Report ID 1 is a 24-bit bitmap; bit order follows the remote's report map:
     * 0 MenuPick 1 Up 2 Down 3 Left 4 Right 5 Power 6 Voice 7 Vol+ 8 Vol- 9 AL_SelectTask 10 AL_0x1B8
     * 11 0x6A 12 0x6B 13 0x6C 14 AL_0x18A 15 AL_0x192 16 AL_0x194 17 Voice 18 AC_Home 19 AC_Back
     * 20 AC_Forward 21 AC_Stop 22 AC_Refresh 23 AC_Bookmarks
     */
    static final int[] keymap = new int[24];
    static final int[] DEFAULT_KEYMAP = {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_UNKNOWN,
            KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_REFRESH, KeyEvent.KEYCODE_BOOKMARK,
    };
    /** bits that launch an app instead of sending a key: "package" or "package/activity" */
    static final String[] apps = new String[24];

    static void resetKeymap() {
        System.arraycopy(DEFAULT_KEYMAP, 0, keymap, 0, 24);
        java.util.Arrays.fill(apps, null);
        apps[10] = "com.netflix.ninja";                  // Netflix button
        apps[13] = "com.amazon.amazonvideo.livingroom";  // Prime Video button
    }

    /** bits that auto-repeat while held */
    static final int REPEATING = (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) | (1 << 7) | (1 << 8);

    static Handler h;
    static Method inject;
    static Object injector;
    static int pressed;          // currently held bitmap
    static final long[] downTime = new long[24];
    static final int[] repeatCount = new int[24];
    static final Deque<Runnable> ops = new ArrayDeque<>();
    static boolean busy;
    static BluetoothGatt gatt;

    static void log(String s) {
        String t = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        System.out.println(t + " " + s);
        System.out.flush();
    }

    static String hex(byte[] v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : v) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------- key injection ----------

    static void initInjector() throws Exception {
        try {
            Class<?> c = Class.forName("android.hardware.input.InputManagerGlobal");
            injector = c.getMethod("getInstance").invoke(null);
            inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ClassNotFoundException e) {
            Class<?> c = Class.forName("android.hardware.input.InputManager");
            injector = c.getMethod("getInstance").invoke(null);
            inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
    }

    static void sendKey(int code, int action, long down, int repeat) {
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(down, now, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD);
        try {
            inject.invoke(injector, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
        } catch (Exception e) {
            log("inject failed: " + e);
        }
    }

    static void onReport(byte[] v) {
        if (v == null || v.length < 3) return;
        reloadKeymapIfChanged();
        int now = (v[0] & 0xff) | (v[1] & 0xff) << 8 | (v[2] & 0xff) << 16;
        int changed = now ^ pressed;
        for (int bit = 0; bit < 24; bit++) {
            if ((changed & (1 << bit)) == 0) continue;
            int code = keymap[bit];
            boolean down = (now & (1 << bit)) != 0;
            log((down ? "DOWN" : "UP  ") + " bit" + bit + " -> " + (apps[bit] != null ? "app " + apps[bit] : KeyEvent.keyCodeToString(code)));
            if (apps[bit] != null) {
                if (down) launch(apps[bit]);
                continue;
            }
            if (code == KeyEvent.KEYCODE_UNKNOWN) continue;
            if (down) {
                downTime[bit] = SystemClock.uptimeMillis();
                repeatCount[bit] = 0;
                sendKey(code, KeyEvent.ACTION_DOWN, downTime[bit], 0);
                if ((REPEATING & (1 << bit)) != 0) scheduleRepeat(bit, REPEAT_DELAY_MS);
            } else {
                h.removeCallbacksAndMessages(repeatToken(bit));
                sendKey(code, KeyEvent.ACTION_UP, downTime[bit], 0);
            }
        }
        pressed = now;
    }

    static void launch(String target) {
        new Thread(() -> {
            try {
                String[] cmd = target.contains("/")
                        ? new String[]{"am", "start", "-a", "android.intent.action.MAIN", "-f", "0x10200000", "-n", target}
                        : new String[]{"am", "start", "-a", "android.intent.action.MAIN",
                                "-c", "android.intent.category.LEANBACK_LAUNCHER", "-f", "0x10200000", "-p", target};
                java.lang.Process p = Runtime.getRuntime().exec(cmd);
                p.waitFor();
                log("launched " + target + " exit=" + p.exitValue());
            } catch (Exception e) {
                log("launch failed " + target + ": " + e);
            }
        }).start();
    }

    static final Object[] tokens = new Object[24];
    static Object repeatToken(int bit) {
        if (tokens[bit] == null) tokens[bit] = new Object();
        return tokens[bit];
    }

    static void scheduleRepeat(int bit, int delay) {
        h.postAtTime(() -> {
            if ((pressed & (1 << bit)) == 0) return;
            sendKey(keymap[bit], KeyEvent.ACTION_DOWN, downTime[bit], ++repeatCount[bit]);
            scheduleRepeat(bit, REPEAT_INTERVAL_MS);
        }, repeatToken(bit), SystemClock.uptimeMillis() + delay);
    }

    static void releaseAll() {
        if (pressed != 0) {
            log("link lost, releasing held keys");
            onReport(new byte[]{0, 0, 0});
        }
    }

    static long keymapStamp = -1;

    /** cheap stat on every report: picks up changes made from the app UI without a restart */
    static void reloadKeymapIfChanged() {
        File f = new File(KEYMAP_FILE);
        long stamp = f.exists() ? f.lastModified() ^ f.length() : 0;
        if (stamp != keymapStamp) loadKeymap();
    }

    static void loadKeymap() {
        resetKeymap();
        File f = new File(KEYMAP_FILE);
        keymapStamp = f.exists() ? f.lastModified() ^ f.length() : 0;
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("[=\\s]+");
                if (p.length < 2) continue;
                int bit = Integer.parseInt(p[0]);
                if (p[1].startsWith("app:")) {
                    apps[bit] = p[1].substring(4);
                    log("keymap: bit" + bit + " -> app " + apps[bit]);
                    continue;
                }
                apps[bit] = null;
                String name = p[1].startsWith("KEYCODE_") ? p[1] : "KEYCODE_" + p[1];
                int code = KeyEvent.keyCodeFromString(name);
                if (bit >= 0 && bit < 24) {
                    keymap[bit] = code;
                    log("keymap: bit" + bit + " -> " + KeyEvent.keyCodeToString(code));
                }
            }
        } catch (Exception e) {
            log("keymap file error: " + e);
        }
    }

    // ---------- GATT ----------

    static void enqueue(Runnable r) { ops.add(r); }

    static void next() {
        if (busy || ops.isEmpty()) return;
        busy = true;
        ops.poll().run();
    }

    static void done() { busy = false; next(); }

    /** stack refused the call (busy) — try the same op again shortly */
    static void retry(Runnable r) {
        h.postDelayed(() -> { ops.addFirst(r); busy = false; next(); }, 500);
    }

    static final List<BluetoothGattCharacteristic> inputReports = new ArrayList<>();

    static final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            h.post(() -> {
                log("connection state=" + state + " status=" + status);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    ops.clear();
                    busy = false;
                    g.discoverServices();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    releaseAll();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> {
                BluetoothGattService s = g.getService(HID);
                if (s == null) { log("no HID service (status " + status + ")"); return; }
                inputReports.clear();
                int cached = readCachedHandle();
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (!REPORT.equals(c.getUuid())) continue;
                    if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) continue;
                    if (c.getInstanceId() == cached) {
                        // fast path: the remote keeps our CCCD (bonded), so only the local registration is needed
                        g.setCharacteristicNotification(c, true);
                        inputReports.add(c);
                        log("ready (cached handle 0x" + Integer.toHexString(cached) + "), verifying in background");
                    }
                    BluetoothGattDescriptor ref = c.getDescriptor(RPT_REF);
                    if (ref == null) continue;
                    enqueue(new Runnable() { public void run() { if (!g.readDescriptor(ref)) retry(this); } });
                }
                enqueue(() -> { log("ready, listening for keys"); done(); });
                next();
            });
        }

        @Override
        public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status, byte[] v) {
            h.post(() -> {
                BluetoothGattCharacteristic c = d.getCharacteristic();
                // Report Reference = [report id, type]; we want Report ID 1, Input (1)
                if (status == 0 && v != null && v.length >= 2 && v[0] == 1 && v[1] == 1) {
                    log("consumer report at handle 0x" + Integer.toHexString(c.getInstanceId()));
                    if (!inputReports.contains(c)) inputReports.add(c);
                    writeCachedHandle(c.getInstanceId());
                    BluetoothGattDescriptor cccd = c.getDescriptor(CCCD);
                    ops.addFirst(new Runnable() { public void run() {
                        g.setCharacteristicNotification(c, true);
                        if (cccd == null) { done(); return; }
                        if (g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != 0) retry(this);
                    } });
                }
                done();
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            h.post(() -> {
                log("notifications on handle 0x" + Integer.toHexString(d.getCharacteristic().getInstanceId()) + " status=" + status);
                done();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            if (!inputReports.contains(c)) return;
            h.post(() -> onReport(v));
        }
    };

    static final String HANDLE_FILE = "/data/local/tmp/xrc-handle";

    static int readCachedHandle() {
        try (BufferedReader r = new BufferedReader(new FileReader(HANDLE_FILE))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    static void writeCachedHandle(int handle) {
        if (readCachedHandle() == handle) return;
        try (java.io.FileWriter w = new java.io.FileWriter(HANDLE_FILE)) {
            w.write(Integer.toString(handle));
        } catch (Exception e) {
            log("cannot cache handle: " + e);
        }
    }

    static BluetoothAdapter adapter() throws Exception {
        try {
            Class<?> bsm = Class.forName("android.os.BluetoothServiceManager");
            Object inst = bsm.getDeclaredConstructor().newInstance();
            Class<?> init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
            Method set = init.getDeclaredMethod("setBluetoothServiceManager", bsm);
            set.setAccessible(true);
            set.invoke(null, inst);
        } catch (Throwable t) {
            log("bt init warn: " + t);
        }
        AttributionSource src = new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
        Method m = BluetoothAdapter.class.getDeclaredMethod("createAdapter", AttributionSource.class);
        m.setAccessible(true);
        return (BluetoothAdapter) m.invoke(null, src);
    }

    static BluetoothDevice findRemote(BluetoothAdapter ad, String mac) {
        if (mac != null) return ad.getRemoteDevice(mac);
        for (BluetoothDevice d : ad.getBondedDevices()) {
            String n = d.getName();
            if (n != null && n.toLowerCase(Locale.ROOT).contains("xiaomi")) return d;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        // single instance guard (abstract socket, released automatically when the process dies)
        LocalServerSocket lock;
        try {
            lock = new LocalServerSocket("xiaomi-rc-bridge");
        } catch (Exception e) {
            log("already running, exiting");
            return;
        }

        Looper.prepareMainLooper();
        HandlerThread ht = new HandlerThread("xrc");
        ht.start();
        h = new Handler(ht.getLooper());

        initInjector();
        loadKeymap();
        BluetoothAdapter ad = adapter();
        String mac = args.length > 0 ? args[0] : null;

        h.post(new Runnable() {
            public void run() {
                BluetoothDevice dev = null;
                try {
                    if (ad.isEnabled()) dev = findRemote(ad, mac);
                } catch (Exception e) {
                    log("lookup error: " + e);
                }
                if (dev == null) {
                    log("remote not found / bluetooth off, retrying in 10s");
                    h.postDelayed(this, 10000);
                    return;
                }
                log("using remote " + dev.getAddress() + " (" + dev.getName() + ")");
                gatt = dev.connectGatt(null, true, cb, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h);
            }
        });
        // watchdog: if Bluetooth is switched off our GATT client is dead; exit and let the supervisor restart us
        h.postDelayed(new Runnable() {
            public void run() {
                boolean on;
                try { on = ad.isEnabled(); } catch (Exception e) { on = false; }
                if (gatt != null && !on) {
                    log("bluetooth turned off, exiting for restart");
                    System.exit(3);
                }
                h.postDelayed(this, 15000);
            }
        }, 15000);
        log("xiaomi-rc-bridge started, pid " + Process.myPid() + ", lock " + lock);
        Looper.loop();
    }
}
