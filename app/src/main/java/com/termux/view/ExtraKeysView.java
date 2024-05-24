package com.termux.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ToggleButton;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.function.IntConsumer;
/**
 * A view showing extra keys (such as Escape, Ctrl, Alt) not normally available on an Android soft
 * keyboard.
 */
public final class ExtraKeysView extends GridLayout {

    public static int TEXT_COLOR = 0xFFFFFFFF;
    public static int BUTTON_COLOR = 0x00000000;
    public static int BUTTON_PRESSED_COLOR = 0x7FFFFFFF;

    private ToggleButton mControlButton;
    private ToggleButton mAltButton;
    private ScheduledExecutorService mScheduledExecutor;
    private PopupWindow mPopupWindow;
    private int mLongPressCount;

    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);
		reload();
    }

    public boolean readAltButton() {
        if (mAltButton.isPressed()) {
            return true;
        }

        boolean result = mAltButton.isChecked();

        if (result) {
            mAltButton.setChecked(false);
            mAltButton.setTextColor(TEXT_COLOR);
        }

        return result;
    }

    public boolean readControlButton() {
        if (mControlButton.isPressed()) {
            return true;
        }

        boolean result = mControlButton.isChecked();

        if (result) {
            mControlButton.setChecked(false);
            mControlButton.setTextColor(TEXT_COLOR);
        }

        return result;
    }

    private void popup(View view, String text) {
        int width = view.getMeasuredWidth();
        int height = view.getMeasuredHeight();

        Button button = new Button(getContext(), null, android.R.attr.buttonBarButtonStyle);
        button.setText(text);
        button.setTextColor(TEXT_COLOR);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setWidth(width);
        button.setHeight(height);
        button.setBackgroundColor(BUTTON_PRESSED_COLOR);

        mPopupWindow = new PopupWindow(this);
        mPopupWindow.setWidth(LayoutParams.WRAP_CONTENT);
        mPopupWindow.setHeight(LayoutParams.WRAP_CONTENT);
        mPopupWindow.setContentView(button);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setFocusable(false);
        mPopupWindow.showAsDropDown(view, 0, -2 * height);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void reload() {
        mAltButton = mControlButton = null;
        removeAllViews();

        String[][] buttons = {
            {"ESC", "VS Code", ":", "HOME", "↑", "END"},
            {"TAB", "CTRL", "ALT", "←",    "↓", "→"}
        };

        final int rows = buttons.length;
        final int[] cols = {buttons[0].length, buttons[1].length};

        setRowCount(rows);
        setColumnCount(cols[0]);

        LinearLayout tab_ctr_alt = new LinearLayout(getContext());
        LinearLayout.LayoutParams tab_ctr_alt_params = new LinearLayout.LayoutParams(-2, -2);
        tab_ctr_alt.setWeightSum(3);
        tab_ctr_alt.setLayoutParams(tab_ctr_alt_params);

        for (int row = 0; row < rows; row++) {
            final String text = buttons[row][0];

            for (int col = 0; col < cols[row]; col++) {
                final String buttonText = buttons[row][col];
                Button button;

                switch (buttonText) {
                    case "CTRL":
                        button = mControlButton = new ToggleButton(getContext(), null,
                            android.R.attr.buttonBarButtonStyle);
                        button.setClickable(true);
                        break;
                    case "ALT":
                        button = mAltButton = new ToggleButton(getContext(), null,
                            android.R.attr.buttonBarButtonStyle);
                        button.setClickable(true);
                        break;
                    default:
                        button = new Button(getContext(), null,
                            android.R.attr.buttonBarButtonStyle);
                        break;
                }

                button.setText(buttonText);
                if(buttonText == "VS Code"){
                    button.setTextColor(Color.CYAN);
                } else {
                    button.setTextColor(TEXT_COLOR);

                }

                button.setBackgroundColor(BUTTON_COLOR);
                button.setAllCaps(false);
                button.setPadding(0, 0, 0, 0);

                if ("↑←↓→".contains(buttonText)) {
                    button.setTypeface(button.getTypeface(), Typeface.BOLD);
                }

                final Button finalButton = button;
                button.setOnClickListener(new View.OnClickListener(){
						@Override
						public void onClick(View v) {
							// Use haptic feedback if possible.
							if (Settings.System.getInt(getContext().getContentResolver(),
													   Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {
								finalButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
							}

							View root = getRootView();

							switch (buttonText) {
								case "CTRL":
								case "ALT":
									ToggleButton self = (ToggleButton) finalButton;
									self.setChecked(self.isChecked());
									self.setTextColor(self.isChecked() ? 0xFF00CC66 : TEXT_COLOR);
									break;
								default:
									sendKey(root, buttonText);
									break;
							}
						}
					});

                button.setOnTouchListener(new View.OnTouchListener(){
						@Override
						public boolean onTouch(View v,MotionEvent event) {

							final View root = getRootView();
							switch (event.getAction()) {
								case MotionEvent.ACTION_DOWN:
									mLongPressCount = 0;
									v.setBackgroundColor(BUTTON_PRESSED_COLOR);
									if ("↑↓←→".contains(buttonText)) {
										mScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
										mScheduledExecutor.scheduleWithFixedDelay( new Runnable(){
												@Override
												public void run(){
													mLongPressCount++;
													sendKey(root, buttonText);
												}
											}, 400, 80, TimeUnit.MILLISECONDS);
									}
									return true;
								case MotionEvent.ACTION_MOVE:
									if ("―/|>".contains(buttonText)) {
										if (mPopupWindow == null && event.getY() < 0) {
											v.setBackgroundColor(BUTTON_COLOR);

											switch (buttonText) {
												case "―":
													popup(v, "_");
													break;
												case "/":
													popup(v, "\\");
													break;
												case "|":
													popup(v, "&");
													break;
												case ">":
													popup(v, "<");
													break;
											}
										}
										if (mPopupWindow != null && event.getY() > 0) {
											v.setBackgroundColor(BUTTON_PRESSED_COLOR);
											mPopupWindow.dismiss();
											mPopupWindow = null;
										}
									}
									return true;
								case MotionEvent.ACTION_CANCEL:
									v.setBackgroundColor(BUTTON_COLOR);
									if (mScheduledExecutor != null) {
										mScheduledExecutor.shutdownNow();
										mScheduledExecutor = null;
									}
									return true;
								case MotionEvent.ACTION_UP:
									v.setBackgroundColor(BUTTON_COLOR);
									if (mScheduledExecutor != null) {
										mScheduledExecutor.shutdownNow();
										mScheduledExecutor = null;
									}

									if (mLongPressCount == 0) {
										if (mPopupWindow != null && "―/|>".contains(buttonText)) {
											mPopupWindow.setContentView(null);
											mPopupWindow.dismiss();
											mPopupWindow = null;

											switch (buttonText) {
												case "―":
													sendKey(root, "_");
													break;
												case "/":
													sendKey(root, "\\");
													break;
												case "|":
													sendKey(root, "&");
													break;
												case ">":
													sendKey(root, "<");
													break;
											}
										} else {
											v.performClick();
										}
									}
									return true;
								default:
									return true;
							}
						}
					});

                LayoutParams param = new GridLayout.LayoutParams();
                param.width = 0;
                param.height = 0;

                param.setMargins(0, 0, 0, 0);
                param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1.f);
                param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1.f);
                button.setLayoutParams(param);

                addView(button);
            }
        }
    }

    private static void sendKey(View view, String keyName) {
        int keyCode = 0;
        switch (keyName) {
            case "ESC":
                keyCode = KeyEvent.KEYCODE_ESCAPE;
                break;
            case "TAB":
                keyCode = KeyEvent.KEYCODE_TAB;
                break;
            case "DEL":
                keyCode = KeyEvent.KEYCODE_FORWARD_DEL;
                break;
            case "INS":
                keyCode = KeyEvent.KEYCODE_INSERT;
                break;
            case "HOME":
                keyCode = KeyEvent.KEYCODE_MOVE_HOME;
                break;
            case "END":
                keyCode = KeyEvent.KEYCODE_MOVE_END;
                break;
            case "PGUP":
                keyCode = KeyEvent.KEYCODE_PAGE_UP;
                break;
            case "PGDN":
                keyCode = KeyEvent.KEYCODE_PAGE_DOWN;
                break;
            case "↑":
                keyCode = KeyEvent.KEYCODE_DPAD_UP;
                break;
            case "←":
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                break;
            case "→":
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                break;
            case "↓":
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                break;
            case ":":
                keyName = ":";
                break;
            case "VS Code":
                keyName = "vscode\n";
                //TermuxActivity.activity.startActivity(new Intent(TermuxActivity.activity, VSCodeActivity.class));
                break;
            default:
                break;
        }

        final TerminalView terminalView = view.findViewById(com.vscode.R.id.terminal_view);
        if (keyCode > 0) {
            terminalView.onKeyDown(keyCode, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                keyName.codePoints().forEach(new IntConsumer(){
                        @Override
                        public void accept(int codePoint){
                            terminalView.inputCodePoint(codePoint,false, false);
                        }
                    });
            }
        }
    }
}
