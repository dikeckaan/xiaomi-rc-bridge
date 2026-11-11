package com.kaan.xrc;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** Minimal adb wire-protocol client: connect to the device's own adbd over TCP and run one shell command. */
public class AdbClient {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    static final int VERSION = 0x01000000, MAXDATA = 256 * 1024;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final byte[] SHA1_DIGEST_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    public static class AuthPendingException extends IOException {
        AuthPendingException() { super("waiting for 'Allow debugging' confirmation on the TV"); }
    }

    private final KeyPair keys;

    public AdbClient(Context ctx) throws Exception { keys = loadOrCreateKeys(ctx); }

    static KeyPair loadOrCreateKeys(Context ctx) throws Exception {
        // device-protected storage: readable at LOCKED_BOOT_COMPLETED, before the user storage is unlocked
        File dir = ctx.createDeviceProtectedStorageContext().getFilesDir();
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        if (!priv.exists()) {
            // migrate the already-authorized key from credential storage (v0.2)
            try {
                File oldPriv = new File(ctx.getFilesDir(), "adbkey"), oldPub = new File(ctx.getFilesDir(), "adbkey.pub");
                if (oldPriv.exists() && oldPub.exists()) {
                    write(priv, read(oldPriv));
                    write(pub, read(oldPub));
                }
            } catch (Exception ignored) {
                // credential storage still locked; a new key would need re-authorization, so fail instead
                throw new IOException("adb key not available yet (storage locked)");
            }
        }
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(read(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(read(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        write(priv, kp.getPrivate().getEncoded());
        write(pub, kp.getPublic().getEncoded());
        return kp;
    }

    static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    static void write(File f, byte[] d) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(d); }
    }

    /** adb's custom RSA public key blob (android_pubkey_encode), base64 + name, NUL-terminated */
    byte[] adbPublicKey() {
        RSAPublicKey pk = (RSAPublicKey) keys.getPublic();
        BigInteger n = pk.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(64);
        bb.putInt(n0inv.intValue());
        putLe(bb, n);
        putLe(bb, rr);
        bb.putInt(pk.getPublicExponent().intValue());
        String s = Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " xiaomi-rc-bridge@tv\0";
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static void putLe(ByteBuffer bb, BigInteger v) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < 64; i++) bb.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    byte[] sign(byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign((PrivateKey) keys.getPrivate());
        s.update(SHA1_DIGEST_INFO);
        s.update(token);
        return s.sign();
    }

    // ---- framing ----

    static void send(OutputStream o, int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(~cmd);
        o.write(h.array());
        if (len > 0) o.write(data);
        o.flush();
    }

    static final class Msg { int cmd, a0, a1; byte[] data; }

    static Msg recv(DataInputStream in) throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt(); m.a0 = h.getInt(); m.a1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    /**
     * Runs a shell command on the local adbd and returns its output.
     * Throws AuthPendingException when this app's key is not yet authorized (the TV shows a prompt).
     */
    public String shell(String host, int port, String command, int timeoutMs) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(timeoutMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAXDATA, "host::\0".getBytes(StandardCharsets.US_ASCII));

            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m;
                try {
                    m = recv(in);
                } catch (java.net.SocketTimeoutException e) {
                    if (sentKey) throw new AuthPendingException();
                    throw e;
                }
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.a0 != AUTH_TOKEN) throw new IOException("unexpected adb msg 0x" + Integer.toHexString(m.cmd));
                if (!sentSig) {
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    sentSig = true;
                } else if (!sentKey) {
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, adbPublicKey());
                    sentKey = true;
                    s.setSoTimeout(60000); // give the user time to accept the prompt on screen
                } else {
                    throw new AuthPendingException();
                }
            }
            s.setSoTimeout(timeoutMs);

            final int local = 1;
            send(out, OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m;
                try {
                    m = recv(in);
                } catch (java.io.EOFException e) {
                    break;
                }
                if (m.cmd == OKAY) {
                    remote = m.a0;
                } else if (m.cmd == WRTE) {
                    result.write(m.data);
                    send(out, OKAY, local, m.a0, null);
                } else if (m.cmd == CLSE) {
                    if (remote != 0) send(out, CLSE, local, remote, null);
                    break;
                }
            }
            return result.toString("UTF-8");
        }
    }
}
