package com.termux.setup;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

import com.vscode.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BackgroundJob {
    final Process mProcess;

    public BackgroundJob(String cwd, String fileToExecute, final String[] args, String proot_fs, String prefix, final TerminalService service, final PendingIntent pendingIntent) {
		String[] env = buildEnvironment(false, cwd, proot_fs, prefix);

        final String[] proArray = setupProcessArgs(fileToExecute, args, prefix);

        Process process;
        try {
            process = Runtime.getRuntime().exec(proArray, env, new File(cwd));
        } catch (IOException e) {
            mProcess = null;
            return;
        }

        mProcess = process;
        final Bundle result = new Bundle();
        final StringBuilder outResult = new StringBuilder();
        final StringBuilder errResult = new StringBuilder();

        final Thread errThread = new Thread() {
            @Override
            public void run() {
                InputStream stderr = mProcess.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8));
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        errResult.append(line).append('\n');
                    }
                } catch (IOException e) {}
            }
        };
        errThread.start();

        new Thread() {
            @Override
            public void run() {
                InputStream stdout = mProcess.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));

                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        outResult.append(line).append('\n');
                    }
                } catch (IOException e) {}

                try {
                    int exitCode = mProcess.waitFor();
                    service.onBackgroundJobExited(BackgroundJob.this);

                    result.putString("stdout", outResult.toString());
                    result.putInt("exitCode", exitCode);

                    errThread.join();
                    result.putString("stderr", errResult.toString());

                    Intent data = new Intent();
                    data.putExtra("result", result);

                    if(pendingIntent != null) {
                        try {
                            pendingIntent.send(service.getApplicationContext(), Activity.RESULT_OK, data);
                        } catch (PendingIntent.CanceledException e) {}
                    }
                } catch (InterruptedException e) {}
            }
        }.start();
    }
	
	public static String[] buildEnvironment(boolean failSafe, String cwd, String proot_fs, String prefix) {

        final String termEnv = "TERM=xterm-256color";
        final String homeEnv = "HOME=" + proot_fs + "/root";
        final String prefixEnv = "PREFIX=" + prefix;
        final String androidRootEnv = "ANDROID_ROOT=" + System.getenv("ANDROID_ROOT");
        final String androidDataEnv = "ANDROID_DATA=" + System.getenv("ANDROID_DATA");
		
        final String externalStorageEnv = "EXTERNAL_STORAGE=" + System.getenv("EXTERNAL_STORAGE");
        if (failSafe) {
            final String pathEnv = "PATH=" + System.getenv("PATH");
            return new String[]{termEnv, homeEnv, prefixEnv, androidRootEnv, androidDataEnv, pathEnv, externalStorageEnv};
        } else {
            final String ldEnv = "LD_LIBRARY_PATH=" + MainActivity.nativeLibraryDirLD;
            final String langEnv = "LANG=en_US.UTF-8";
            final String pathEnv = "PATH=" + prefix + "/sbin:" + prefix + "/sbin/applets";
            final String pwdEnv = "PWD=" + cwd;
            final String tmpdirEnv = "TMPDIR=" + prefix + "/tmp";
			final String prootTmpDir = "PROOT_TMP_DIR=" + prefix;
            return new String[]{termEnv, prootTmpDir, homeEnv, prefixEnv, ldEnv, langEnv, pathEnv, pwdEnv, androidRootEnv, androidDataEnv, externalStorageEnv, tmpdirEnv};
        }
    }

	static String[] setupProcessArgs(String fileToExecute, String[] args, String prefix) {
        String interpreter = null;
        try {
            File file = new File(fileToExecute);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // Elf file, do nothing.
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    String executable = builder.toString();
                                    if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                        String[] parts = executable.split("/");
                                        String binary = parts[parts.length - 1];
                                        interpreter = prefix + "/bin/" + binary;
                                    }
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                    } else {
                        interpreter = prefix + "/bin/sh";
                    }
                }
            }
        } catch (IOException e) {}

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        result.add(fileToExecute);
        if (args != null) Collections.addAll(result, args);
        return result.toArray(new String[0]);
    }
	

}
