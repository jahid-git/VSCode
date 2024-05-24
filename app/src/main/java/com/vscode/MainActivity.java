package com.vscode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.widget.Toast;

import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.ExtraKeysView;
import com.termux.view.TerminalView;
import com.vscode.handlers.StoragePermissionsHandler;
import com.termux.setup.TerminalService;
import com.termux.setup.TerminalViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

public class MainActivity extends Activity implements ServiceConnection {
    private static final String RELOAD_STYLE_ACTION = "com.vscode.terminal.reload_style";
    private static final String CURRENT_SESSION_KEY = "current_session";
    private StoragePermissionsHandler storagePermissionsHandler;

    private TerminalView mTerminalView;

    private ExtraKeysView mExtraKeysView;

    private TerminalService mTermService;

    private SharedPreferences store;

    boolean mIsVisible;

    File files;
    String proot_fs;
    String nativeLibraryDir;
    public static String nativeLibraryDirLD;
    String busybox;
    String proot;
    String alpine;
    final SoundPool mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
            new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();
    int mBellSoundId;

    private final BroadcastReceiver mBroadcastReceiever = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mIsVisible) {
                checkForFontAndColors();
                if (mExtraKeysView != null) {
                    mExtraKeysView.reload();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storagePermissionsHandler = new StoragePermissionsHandler(this);

        if (!storagePermissionsHandler.checkPermission()) {
            storagePermissionsHandler.requestPermission();
        } else {
            checkPermission();
        }

        store = getSharedPreferences("store", Context.MODE_PRIVATE);

        files = new File(getFilesDir().getAbsolutePath());
        proot_fs = files.getAbsolutePath() + "/alpine";
        nativeLibraryDir = getApplicationInfo().nativeLibraryDir;
        nativeLibraryDirLD = getApplicationInfo().nativeLibraryDir;
        busybox = nativeLibraryDir + "/busybox.so";
        proot = nativeLibraryDir + "/proot.so";
        alpine = nativeLibraryDir + "/alpine.so";

        mTerminalView = (TerminalView) findViewById(R.id.terminal_view);
    }

    private boolean isExistFile(String path) {
        File file = new File(path);
        return file.exists();
    }

    @Override
    protected void onStart() {
        super.onStart();

        mTerminalView.setOnKeyListener(new TerminalViewClient(MainActivity.this, mTerminalView, mExtraKeysView, store));

        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        int defaultFontSize = Math.round(12 * dipInPixels);
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        mTerminalView.setTextSize(store.getInt(TerminalViewClient.FONTSIZE_KEY, defaultFontSize));

        mTerminalView.setKeepScreenOn(true);
        mTerminalView.requestFocus();

        mExtraKeysView = (ExtraKeysView) findViewById(R.id.extra_keys);
        mExtraKeysView.reload();

        registerForContextMenu(mTerminalView);

        Intent serviceIntent = new Intent(this, TerminalService.class);
        startService(serviceIntent);

        if (!bindService(serviceIntent, this, 0))
            throw new RuntimeException("bindService() failed");

        checkForFontAndColors();

        mBellSoundId = mBellSoundPool.load(this, R.raw.bell, 1);

        mIsVisible = true;

        if (mTermService != null) {
            switchToSession(getStoredCurrentSessionOrLast());
        }

        registerReceiver(mBroadcastReceiever, new IntentFilter(RELOAD_STYLE_ACTION));
        mTerminalView.onScreenUpdated();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            mIsVisible = false;
            TerminalSession currentSession = getCurrentTermSession();
            if (currentSession != null)
                store.edit().putString(CURRENT_SESSION_KEY, currentSession.mHandle).apply();
            unregisterReceiver(mBroadcastReceiever);
        } catch (Exception e) {
        }
    }

    private void checkPermission() {
        if (storagePermissionsHandler.checkPermission()) {
            Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Please allow permission for storage access!", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == StoragePermissionsHandler.PERMISSION_REQUEST_CODE) {
            checkPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == StoragePermissionsHandler.PERMISSION_REQUEST_CODE) {
            checkPermission();
        }
    }


    void noteSessionInfo() {
        if (!mIsVisible) return;
        TerminalSession session = getCurrentTermSession();
        final int indexOfSession = mTermService.getSessions().indexOf(session);
    }

    public void switchToSession(boolean forward) {
        TerminalSession currentSession = getCurrentTermSession();
        int index = mTermService.getSessions().indexOf(currentSession);
        if (forward) {
            if (++index >= mTermService.getSessions().size()) index = 0;
        } else {
            if (--index < 0) index = mTermService.getSessions().size() - 1;
        }
        switchToSession(mTermService.getSessions().get(index));
    }

    private void switchToSession(TerminalSession session) {
        if (mTerminalView.attachSession(session)) {
            noteSessionInfo();
            updateBackgroundColor();
        }
    }

    public TerminalSession getStoredCurrentSessionOrLast() {
        TerminalSession stored = getCurrentSession();
        if (stored != null) return stored;
        List<TerminalSession> sessions = mTermService.getSessions();
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    private TerminalSession getCurrentTermSession() {
        return mTerminalView.getCurrentSession();
    }

    private void storeCurrentSession(Context context, TerminalSession session) {
        store.edit().putString(CURRENT_SESSION_KEY, session.mHandle).apply();
    }

    private TerminalSession getCurrentSession() {
        String sessionHandle = store.getString(CURRENT_SESSION_KEY, "");
        for (int i = 0, len = mTermService.getSessions().size(); i < len; i++) {
            TerminalSession session = mTermService.getSessions().get(i);
            if (session.mHandle.equals(sessionHandle)) return session;
        }
        return null;
    }
    private AlertDialog dialog;
    public void addSession() {
        if (!isExistFile(proot_fs + "/etc/profile")) {
            dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Installing....")
                    .setMessage("Please wait few seconds...")
                    .create();
            dialog.show();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ProcessBuilder processBuilder = new ProcessBuilder();
                        processBuilder.command(busybox, "sh", "-c", busybox + " tar -xf " + alpine);
                        processBuilder.directory(files);
                        Process process = processBuilder.start();
                        if(process.waitFor() == 0) {
                            onRestart();
                        }
                    } catch (Exception e) {}
                    dialog.dismiss();
                }
            }).start();

        } else {
            String[] prootArgs = {
                    busybox,
                    "sh",
                    "-c",
                    proot_fs + "/bin/proot --link2symlink -0 -r " + proot_fs + " -b /dev/ -b /sys/ -b /proc/ -b /sdcard -b /storage -b $HOME -w /root /usr/bin/env TMPDIR=/tmp HOME=/root PREFIX=/usr SHELL=/bin/ash TERM=\"$TERM\" LANG=$LANG PATH=/bin:/usr/bin:/sbin:/usr/sbin /bin/ash --login"
            };

            TerminalSession currentSession = getCurrentTermSession();
            String cwd = (currentSession == null) ? null : currentSession.getCwd();
            TerminalSession newSession = mTermService.createTermSession(false, busybox, cwd, null, proot_fs, prootArgs);
            switchToSession(newSession);
            mTermService.updateNotification();
        }
    }

    private void removeFinishedSession(TerminalSession finishedSession) {
        TerminalService service = mTermService;
        int index = service.removeTermSession(finishedSession);
        if (!mTermService.getSessions().isEmpty()) {
            if (index >= service.getSessions().size()) {
                index = service.getSessions().size() - 1;
            }
            switchToSession(service.getSessions().get(index));
        }
    }
    private void checkForFontAndColors() {
        try {
            File fontFile = new File(getFilesDir().getAbsolutePath() + "/root/.configs/font.ttf");
            File colorsFile = new File(getFilesDir().getAbsolutePath() + "/root/.configs/colors.properties");

            final Properties props = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props);
            TerminalSession session = getCurrentTermSession();
            if (session != null && session.getEmulator() != null) {
                session.getEmulator().mColors.reset();
            }
            updateBackgroundColor();

            final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0) ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            mTerminalView.setTypeface(newTypeface);
        } catch (Exception e) {}
    }
    private void updateBackgroundColor() {
        TerminalSession session = getCurrentTermSession();
        if (session != null && session.getEmulator() != null) {
            mTerminalView.setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
            mExtraKeysView.BUTTON_COLOR = session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
            mExtraKeysView.TEXT_COLOR = session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND];
            mExtraKeysView.setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
            mExtraKeysView.reload();
        }
    }


    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TerminalService.LocalBinder) service).service;

        mTermService.mSessionChangeCallback = new TerminalSession.SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                if (!mIsVisible) return;
                if (getCurrentTermSession() == changedSession) mTerminalView.onScreenUpdated();
            }

            @Override
            public void onTitleChanged(TerminalSession updatedSession) {
                if (!mIsVisible) return;
                if (updatedSession != getCurrentTermSession()) {
                }
            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
                if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    if (mTermService.getSessions().size() > 1) {
                        removeFinishedSession(finishedSession);
                    }
                } else {
                    if (finishedSession.getExitStatus() == 0 || finishedSession.getExitStatus() == 130) {
                        removeFinishedSession(finishedSession);
                    }
                }
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                if (!mIsVisible) return;
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(text)));
            }

            @Override
            public void onBell(TerminalSession session) {
                if (!mIsVisible) return;
                mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
            }

            @Override
            public void onColorsChanged(TerminalSession changedSession) {
                if (getCurrentTermSession() == changedSession) updateBackgroundColor();
            }
        };

        if (mTermService.getSessions().isEmpty()) {
            if (mIsVisible) {
                if (mTermService != null){
                    addSession();
                } else {
                    finish();
                }
            }
        } else {
            switchToSession(getStoredCurrentSessionOrLast());
        }

    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        //finish();

    }

}