package com.termux.setup;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.ExtraKeysView;
import com.termux.view.TerminalView;

import java.util.Objects;

public final class TerminalViewClient implements com.termux.view.TerminalViewClient {

    public static final String FONTSIZE_KEY = "fontsize";
    private final int MIN_FONTSIZE;
    private static final int MAX_FONTSIZE = 256;

    private Activity mActivity;
    private TerminalView mTerminalView;
    private ExtraKeysView mExtraKeysView;

    private SharedPreferences store;

    private int mFontSize;

    /** Keeping track of the special keys acting as Ctrl and Fn for the soft keyboard and other hardware keys. */
    private boolean mVirtualControlKeyDown, mVirtualFnKeyDown;


    public TerminalViewClient(Activity activity, TerminalView mTerminalView, ExtraKeysView mExtraKeysView, SharedPreferences store) {
        this.mActivity = activity;
        this.mTerminalView = mTerminalView;
        this.mExtraKeysView = mExtraKeysView;
        this.store = store;

        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, mActivity.getResources().getDisplayMetrics());
        MIN_FONTSIZE = (int) (4f * dipInPixels);
        int defaultFontSize = Math.round(12 * dipInPixels);
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        try {
            mFontSize = Integer.parseInt(store.getString(FONTSIZE_KEY, Integer.toString(defaultFontSize)));
        } catch (NumberFormatException | ClassCastException e) {
            mFontSize = defaultFontSize;
        }
        mFontSize = clamp(mFontSize, MIN_FONTSIZE, MAX_FONTSIZE);
    }

    int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            boolean increase = scale > 1.f;
            mFontSize += (increase ? 1 : -1) * 2;
            mFontSize = Math.max(MIN_FONTSIZE, Math.min(mFontSize, MAX_FONTSIZE));
            mTerminalView.setTextSize(mFontSize);
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        InputMethodManager mgr = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (mgr != null) mgr.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false; //mActivity.mSettings.isBackEscape();
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
        // Disable drawer while copying.
        //mActivity.getDrawer().setDrawerLockMode(copyMode ? DrawerLayout.LOCK_MODE_LOCKED_CLOSED : DrawerLayout.LOCK_MODE_UNLOCKED);
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession) {

        if (handleVirtualKeys(keyCode, e, true)) return true;


        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            //mActivity.removeFinishedSession(currentSession);
            return true;
        } else if (e.isCtrlPressed() && e.isAltPressed()) {
            // Get the unmodified code point:
            int unicodeChar = e.getUnicodeChar(0);

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n') {
                //mActivity.switchToSession(true);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p') {
                //mActivity.switchToSession(false);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                //mActivity.getDrawer().openDrawer(Gravity.LEFT);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                //mActivity.getDrawer().closeDrawers();
            } else if (unicodeChar == 'k') {
                InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            } else if (unicodeChar == 'm') {
                //mActivity.mTerminalView.showContextMenu();
            } else if (unicodeChar == 'r') {
                //mActivity.renameSession(-1, currentSession.mSessionName);
            } else if (unicodeChar == 'u') {
                //mActivity.showUrlSelection();
            } else if (unicodeChar == 'v') {
                //mActivity.doPaste();
            } else if (unicodeChar == '+' || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+') {
                //mActivity.changeFontSize(true);
            } else if (unicodeChar == '-') {
                //mActivity.changeFontSize(false);
            } else if (unicodeChar >= '1' && unicodeChar <= '5') {
                int num = unicodeChar - '1';
                //if (mActivity.mTermService.getSessions().size() > num)
                //    mActivity.switchToSession(mActivity.mTermService.getSessions().get(num));
            }

            return true;
        }


        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {

        return handleVirtualKeys(keyCode, e, false);
    }

    @Override
    public boolean readControlKey() {
        return (mExtraKeysView != null && mExtraKeysView.readControlButton()) || mVirtualControlKeyDown;
    }

    @Override
    public boolean readAltKey() {
        return (mExtraKeysView != null && mExtraKeysView.readAltButton());
    }

    @Override
    public boolean onCodePoint(final int codePoint, boolean ctrlDown, TerminalSession session) {

        if (mVirtualFnKeyDown) {
            int resultingKeyCode = -1;
            int resultingCodePoint = -1;
            boolean altDown = false;

            int lowerCase = Character.toLowerCase(codePoint);

            switch (lowerCase) {
                // Arrow keys.
                case 'w':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP;
                    break;
                case 'a':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                    break;
                case 's':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                    break;
                case 'd':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                    break;

                // Page up and down.
                case 'p':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP;
                    break;
                case 'n':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN;
                    break;

                // Some special keys:
                case 't':
                    resultingKeyCode = KeyEvent.KEYCODE_TAB;
                    break;
                case 'i':
                    resultingKeyCode = KeyEvent.KEYCODE_INSERT;
                    break;
                case 'h':
                    resultingCodePoint = '~';
                    break;

                // Special characters to input.
                case 'u':
                    resultingCodePoint = '_';
                    break;
                case 'l':
                    resultingCodePoint = '|';
                    break;

                // Function keys.
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    resultingKeyCode = (codePoint - '1') + KeyEvent.KEYCODE_F1;
                    break;
                case '0':
                    resultingKeyCode = KeyEvent.KEYCODE_F10;
                    break;

                // Other special keys.
                case 'e':
                    resultingCodePoint = /*Escape*/ 27;
                    break;
                case '.':
                    resultingCodePoint = /*^.*/ 28;
                    break;

                case 'b': // alt+b, jumping backward in readline.
                case 'f': // alf+f, jumping forward in readline.
                case 'x': // alt+x, common in emacs.
                    resultingCodePoint = lowerCase;
                    altDown = true;
                    break;

                // Volume control.
                case 'v':
                    resultingCodePoint = -1;
                    AudioManager audio = (AudioManager) mActivity.getSystemService(Context.AUDIO_SERVICE);
                    if (audio != null) audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                    break;

                // Writing mode:
                case 'k':
                case 'q':
                    //mActivity.toggleShowExtraKeys();
                    break;
            }

            if (resultingKeyCode != -1) {
                TerminalEmulator term = session.getEmulator();
                session.write(Objects.requireNonNull(KeyHandler.getCode(resultingKeyCode, 0,
                        term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode())));
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint);
            }
            return true;
        } else if (ctrlDown) {
            if (codePoint == 106 /* Ctrl+j or \n */ && !session.isRunning()) {
                //mActivity.removeFinishedSession(session);
                return true;
            }
        }

        return true;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    /** Handle dedicated volume buttons as virtual keys if applicable. */
    private boolean handleVirtualKeys(int keyCode, KeyEvent event, boolean down) {
        InputDevice inputDevice = event.getDevice();

        if (inputDevice != null && inputDevice.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            // Do not steal dedicated buttons from a full external keyboard.
            return false;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down;
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down;
            return true;
        }

        return false;
    }
}
