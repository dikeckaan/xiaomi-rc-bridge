package com.kaan.xrc;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/** Retries for up to ~3 minutes after boot until the local adbd accepts us and the daemon is started. */
public class StartJob extends JobService {
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean restart = params.getExtras().getBoolean("restart", false);
        new Thread(() -> {
            boolean ok = false;
            for (int i = 0; i < 36 && !stopped; i++) {
                try {
                    String out = Bridge.run(this, Bridge.startCmd(restart && i == 0));
                    Log.i("XRC", "start: " + out.trim());
                    ok = true;
                    break;
                } catch (AdbClient.AuthPendingException e) {
                    Log.w("XRC", "adb authorization missing; open the app once and allow debugging");
                    break;
                } catch (Exception e) {
                    Log.w("XRC", "adb not ready (" + e + "), retry " + i);
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                }
            }
            jobFinished(params, false);
            Log.i("XRC", "start job finished ok=" + ok);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return false;
    }
}
