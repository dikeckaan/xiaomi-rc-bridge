package com.kaan.xrc;

import android.content.Context;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

/** Starts/stops/queries the shell-uid daemon through the TV's own adbd. */
public class Bridge {
    static final String HOST = "127.0.0.1";
    static final int PORT = 5555;
    static final String SCRIPT = "/data/local/tmp/xrcd.sh";
    static final String LOG = "/data/local/tmp/xrcd.log";
    static final String PAIR_LOG = "/data/local/tmp/xrc-pair.log";

    /** Supervisor: runs the daemon straight out of the installed APK and restarts it if it dies. */
    static final String SUPERVISOR =
            "#!/system/bin/sh\n" +
            "# written by com.kaan.xrc - keeps the Xiaomi RC bridge daemon alive\n" +
            "LOG=" + LOG + "\n" +
            "while true; do\n" +
            "  [ -f \"$LOG\" ] && [ \"$(stat -c %s \"$LOG\")\" -gt 1000000 ] && mv \"$LOG\" \"$LOG.old\"\n" +
            "  APK=$(pm path com.kaan.xrc | head -1 | cut -d: -f2)\n" +
            "  if [ -z \"$APK\" ]; then echo \"app uninstalled, supervisor exiting\" >> \"$LOG\"; exit 0; fi\n" +
            "  CLASSPATH=\"$APK\" app_process / com.kaan.xrc.Daemon >> \"$LOG\" 2>&1\n" +
            "  code=$?\n" +
            "  tail -1 \"$LOG\" | grep -q 'already running' && exit 0\n" +
            "  echo \"$(date '+%m-%d %H:%M:%S') daemon exited ($code), restarting in 3s\" >> \"$LOG\"\n" +
            "  sleep 3\n" +
            "done\n";

    static String stopCmd() {
        // kill supervisor first so it does not respawn the daemon
        return "for p in $(ps -A -o PID,ARGS | grep -E 'xrcd.sh|com.kaan.xrc.Daemon' | grep -v grep | awk '{print $1}'); do kill $p; done";
    }

    static String writeCmd() {
        String b64 = Base64.encodeToString(SUPERVISOR.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "echo " + b64 + " | base64 -d > " + SCRIPT + " && chmod 755 " + SCRIPT;
    }

    /** Stop the bridge, scan + pair the remote (Pairer), then start the bridge again — all detached on the TV. */
    static String pairCmd() {
        String job = "APK=$(pm path com.kaan.xrc | head -1 | cut -d: -f2); "
                + "CLASSPATH=$APK app_process / com.kaan.xrc.Pairer 60 > " + PAIR_LOG + " 2>&1; "
                + "echo DONE >> " + PAIR_LOG + "; exec sh " + SCRIPT;
        return stopCmd() + "; " + writeCmd() + "; rm -f " + PAIR_LOG + "; setsid nohup sh -c '" + job
                + "' > /dev/null 2>&1 < /dev/null & sleep 1; echo pairing";
    }

    static final String KEYMAP = "/data/local/tmp/xrc-keymap.txt";

    static String readKeymapCmd() {
        return "cat " + KEYMAP + " 2>/dev/null";
    }

    /** Replace the line for one button bit; target null = back to the default. The daemon hot-reloads the file. */
    static String setAppCmd(int bit, String target) {
        if (target != null && !target.matches("[A-Za-z0-9_.$/]+")) throw new IllegalArgumentException(target);
        String f = KEYMAP;
        return "touch " + f + "; grep -v '^" + bit + "=' " + f + " > " + f + ".tmp; "
                + (target != null ? "echo '" + bit + "=app:" + target + "' >> " + f + ".tmp; " : "")
                + "mv " + f + ".tmp " + f + "; echo ok";
    }

    static String pairLogCmd() {
        return "cat " + PAIR_LOG + " 2>/dev/null";
    }

    static String startCmd(boolean restart) {
        String write = writeCmd();
        String launch = "setsid nohup sh " + SCRIPT + " > /dev/null 2>&1 < /dev/null &";
        if (restart) return stopCmd() + "; sleep 1; " + write + "; " + launch + " sleep 1; echo started";
        return write + "; if ps -A -o ARGS | grep -v grep | grep -q com.kaan.xrc.Daemon; then echo already-running; else "
                + launch + " sleep 1; echo started; fi";
    }

    static String statusCmd() {
        return "if ps -A -o ARGS | grep -v grep | grep -q com.kaan.xrc.Daemon; then echo RUNNING; else echo STOPPED; fi; tail -n 12 " + LOG + " 2>/dev/null";
    }

    static String run(Context ctx, String cmd) throws Exception {
        return new AdbClient(ctx).shell(HOST, PORT, cmd, 15000);
    }
}
