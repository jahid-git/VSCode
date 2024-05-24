package com.termux.setup;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;

import com.vscode.R;
import com.termux.terminal.EmulatorDebug;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSession.SessionChangedCallback;

import java.util.ArrayList;
import java.util.List;

import com.vscode.MainActivity;

public final class TerminalService extends Service implements SessionChangedCallback {
    private static final String NOTIFICATION_CHANNEL_ID = "vscode_terminal_notification_channel";
    private static final int NOTIFICATION_ID = 13457;
    private static final String ACTION_STOP_SERVICE = "com.vscode.terminal.service_stop";
    private static final String ACTION_LOCK_WAKE = "com.vscode.terminal.service_wake_lock";
    private static final String ACTION_UNLOCK_WAKE = "com.vscode.terminal.service_wake_unlock";
    public class LocalBinder extends Binder {
        public final TerminalService service = TerminalService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler();

    final List<TerminalSession> mTerminalSessions = new ArrayList<>();

    final List<BackgroundJob> mBackgroundTasks = new ArrayList<>();

    public SessionChangedCallback mSessionChangeCallback;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (ACTION_STOP_SERVICE.equals(action)) {
            stopSelf();
        } else if (ACTION_LOCK_WAKE.equals(action)) {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, EmulatorDebug.LOG_TAG + ":service-wakelock");
                mWakeLock.acquire(10*60*60000L);

                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, EmulatorDebug.LOG_TAG);
                mWifiLock.acquire();

                String packageName = getPackageName();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        Intent whitelist = new Intent();
                        whitelist.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        whitelist.setData(Uri.parse("package:" + packageName));
                        whitelist.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                        try {
                            startActivity(whitelist);
                        } catch (ActivityNotFoundException e) {}
                    }
                }
                updateNotification();
            }
        } else if (ACTION_UNLOCK_WAKE.equals(action)) {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;

                mWifiLock.release();
                mWifiLock = null;

                updateNotification();
            }
        }
        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    public void updateNotification() {
        if (mWakeLock == null && mTerminalSessions.isEmpty() && mBackgroundTasks.isEmpty()) {
            stopSelf();
        } else
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this);
        builder.setContentTitle(getText(R.string.app_name));

        String contentText = "VS Code is running...";

        builder.setSmallIcon(R.drawable.ic_launcher);

        Resources res = getResources();
        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) contentText += " (wake lock held)";

        builder.setContentText(contentText);

        Intent exitIntent = new Intent(this, TerminalService.class).setAction(ACTION_STOP_SERVICE);
        builder.addAction(android.R.drawable.ic_delete, res.getString(R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, PendingIntent.FLAG_IMMUTABLE));

        String newWakeAction = wakeLockHeld ? ACTION_UNLOCK_WAKE : ACTION_LOCK_WAKE;
        Intent toggleWakeLockIntent = new Intent(this, MainActivity.class).setAction(newWakeAction);
        String actionTitle = res.getString(wakeLockHeld ?
                R.string.notification_action_wake_unlock :
                R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, PendingIntent.FLAG_IMMUTABLE));

        builder.setContentIntent(pendingIntent);
        builder.setOngoing(true);
        builder.setPriority((wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW);
        builder.setShowWhen(false);
        builder.setColor(0xFF607D8B);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL_ID);
        }
        return builder.build();
    }

    @Override
    public void onDestroy() {
        if (mWakeLock != null) mWakeLock.release();
        if (mWifiLock != null) mWifiLock.release();

        stopForeground(true);

        for (int i = 0; i < mTerminalSessions.size(); i++)
            mTerminalSessions.get(i).finishIfRunning();
    }

    public List<TerminalSession> getSessions() {
        return mTerminalSessions;
    }

    boolean isLoginShell = false;
    public TerminalSession createTermSession(boolean failSafe,String executablePath, String cwd, String prefix, String proot_fs, String[] prootArgs) {

        String[] env = BackgroundJob.buildEnvironment(failSafe, cwd == null ? proot_fs : cwd, proot_fs, prefix == null ? proot_fs + "/usr" : prefix);

        String[] processArgs = BackgroundJob.setupProcessArgs(executablePath, prootArgs, prefix);

        executablePath = processArgs[0];

        int lastSlashIndex = executablePath.lastIndexOf('/');

        String processName = (isLoginShell ? "-" : "") +
                (lastSlashIndex == -1 ? executablePath : executablePath.substring(lastSlashIndex + 1));

        String[] args = new String[processArgs.length];
        args[0] = processName;
        if (processArgs.length > 1) System.arraycopy(processArgs, 1, args, 1, processArgs.length - 1);

        TerminalSession session = new TerminalSession(executablePath, cwd == null ? proot_fs : cwd, args, env, this);
        mTerminalSessions.add(session);
        updateNotification();
        return session;
    }

    public int removeTermSession(TerminalSession sessionToRemove) {
        int indexOfRemoved = mTerminalSessions.indexOf(sessionToRemove);
        mTerminalSessions.remove(indexOfRemoved);
        if (mTerminalSessions.isEmpty() && mWakeLock == null) {
            stopSelf();
        } else {
            updateNotification();
        }
        return indexOfRemoved;
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onTitleChanged(changedSession);
    }

    @Override
    public void onSessionFinished(final TerminalSession finishedSession) {
        if (mSessionChangeCallback != null)
            mSessionChangeCallback.onSessionFinished(finishedSession);
    }

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onTextChanged(changedSession);
    }

    @Override
    public void onClipboardText(TerminalSession session, String text) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onClipboardText(session, text);
    }

    @Override
    public void onBell(TerminalSession session) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onBell(session);
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        if (mSessionChangeCallback != null) mSessionChangeCallback.onColorsChanged(session);
    }

    public void onBackgroundJobExited(final BackgroundJob task) {
        mHandler.post(new Runnable(){
            @Override
            public void run(){
                mBackgroundTasks.remove(task);
                updateNotification();
            }
        });
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        String channelName = "VS Code";
        String channelDescription = "Notifications from VSCode";
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName,importance);
        channel.setDescription(channelDescription);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }
}
