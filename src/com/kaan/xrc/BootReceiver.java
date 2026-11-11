package com.kaan.xrc;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * LOCKED_BOOT_COMPLETED (earliest, ~25 s after power-on) / BOOT_COMPLETED / app update:
 * start the daemon right here via local adb; fall back to a retrying job if adbd is not up yet.
 * The start command is idempotent (no-op when the daemon already runs), so multiple triggers are harmless.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        boolean restart = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        Log.i("XRC", "receiver: " + action + " uptime=" + SystemClock.uptimeMillis());
        Context app = ctx.getApplicationContext();
        PendingResult pr = goAsync();
        new Thread(() -> {
            long deadline = SystemClock.uptimeMillis() + 40000; // stay well inside the receiver timeout
            boolean ok = false;
            while (!ok && SystemClock.uptimeMillis() < deadline) {
                try {
                    String out = Bridge.run(app, Bridge.startCmd(restart));
                    Log.i("XRC", "start (" + action + "): " + out.trim() + " uptime=" + SystemClock.uptimeMillis());
                    ok = true;
                } catch (AdbClient.AuthPendingException e) {
                    Log.w("XRC", "adb authorization missing; open the app once and allow debugging");
                    break;
                } catch (Exception e) {
                    Log.w("XRC", "adb not ready: " + e);
                    SystemClock.sleep(1500);
                }
            }
            if (!ok) schedule(app, restart);
            pr.finish();
        }).start();
    }

    static void schedule(Context ctx, boolean restart) {
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("restart", restart);
        JobInfo job = new JobInfo.Builder(1, new ComponentName(ctx, StartJob.class))
                .setOverrideDeadline(0)
                .setExtras(extras)
                .build();
        ctx.getSystemService(JobScheduler.class).schedule(job);
    }
}
