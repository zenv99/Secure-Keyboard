// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard;

import android.os.SystemClock;

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;

public class TouchpadHandler {
    private KeyboardActionListener mListener;
    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static boolean sTouchpadModeActive = false;
    private boolean mInTouchpadMode = false;
    private boolean mHasVibrated = false;
    private boolean mIsScrolling = false;

    private static final float EDGE_THRESHOLD_PERCENTAGE = 0.1f;        // Screen edge threshold percentage
    private static final float TOUCHPAD_ACCELERATION_FACTOR = 90.0f;    // Lower = more acceleration
    private static final float EDGE_ACCELERATION_FACTOR = 0.95f;
    private static final int MIN_EDGE_ACCELERATION_DELAY = 20;

    private long mTouchpadActivationTime;
    private int mTouchpadLastX, mTouchpadLastY;
    private long mCurrentScrollDelay;

    // Accumulators for fractional movement
    private int mTouchpadAccX = 0;
    private int mTouchpadAccY = 0;

    private static final int DIRECTION_UP = 1;
    private static final int DIRECTION_DOWN = 2;
    private static final int DIRECTION_LEFT = 3;
    private static final int DIRECTION_RIGHT = 4;
    private int mCurrentScrollDirection = 0;

    public static void setTouchpadModeActive(boolean active) {
        sTouchpadModeActive = active;
    }

    public void disableTouchpadMode() {
        if (!mInTouchpadMode) return;
        stopEdgeScrolling();
        stopHapticRunnable();
        mInTouchpadMode = false;
        sTouchpadModeActive = false;
        mListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_OFF);
        mListener = null;
    }

    public void enableTouchpadMove(int x, int y, KeyboardActionListener listener) {
        if (!sTouchpadModeActive) return;

        // Initialize
        if (!mInTouchpadMode) {
            mListener = listener;
            mInTouchpadMode = true;
            mHasVibrated = false;
            mTouchpadLastX = x;
            mTouchpadLastY = y;
            mTouchpadActivationTime = SystemClock.elapsedRealtime();
            mListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_ON);
            SettingsValues sv = Settings.getValues();
            mHandler.postDelayed(mHapticRunnable, sv.mKeyLongpressTimeout);
            return;
        }

        onMove(x, y);
    }

    private void onMove(int x, int y) {
        SettingsValues sv = Settings.getValues();

        // Debounce
        if (SystemClock.elapsedRealtime() - mTouchpadActivationTime < sv.mKeyLongpressTimeout) {
            mTouchpadLastX = x;
            mTouchpadLastY = y;
            return;
        }

        // Edge Scrolling
        if (sv.mTouchpadEdgeScroll && handleEdgeScrolling(x, y)) {
            return;
        }

        // In touchpad mode - track both horizontal and vertical movement for 2D cursor control
        int deltaX = x - mTouchpadLastX;
        int deltaY = y - mTouchpadLastY;

        mTouchpadLastX = x;
        mTouchpadLastY = y;

        // iOS-like: both axes follow the finger (diagonals work), but a clearly dominant direction
        // suppresses accidental drift on the other axis.
        final int ax = Math.abs(deltaX), ay = Math.abs(deltaY);
        if (ay * 5 >= ax * 2) { // vertical component is at least 40% of horizontal
            float accFactorY = 1.0f + (ay / TOUCHPAD_ACCELERATION_FACTOR);
            mTouchpadAccY += (int) (deltaY * accFactorY);
        }
        if (ax * 5 >= ay * 2) {
            float accFactorX = 1.0f + (ax / TOUCHPAD_ACCELERATION_FACTOR);
            mTouchpadAccX += (int) (deltaX * accFactorX);
        }

        // Threshold from the sensitivity setting (0-100), then 30% faster than before.
        // A text line is taller than a character is wide, so vertical movement needs more finger travel.
        int sensitivity = Settings.getInstance().getCurrent().mTouchpadSensitivity;
        // iOS trackpad feel: about one character per 7.5 dp of finger travel and one line per 17 dp
        // (before speed-up), independent of screen density. Sensitivity 50 (default) = iOS; 0..100 = 1.45x..0.55x.
        final float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        final float scale = 1.45f - sensitivity * 0.009f;
        int moveThresholdX = Math.max(6, (int) (7.5f * density * scale));
        int moveThresholdY = Math.max(10, (int) (17f * density * scale));

        // Convert finger travel into whole cursor steps and apply them right away, in ONE call per touch
        // event (a single selection change for the horizontal part), so the caret follows the finger smoothly.
        int stepsX = 0, stepsY = 0;
        while (Math.abs(mTouchpadAccX) >= moveThresholdX) {
            boolean positive = mTouchpadAccX > 0;
            stepsX += positive ? 1 : -1;
            mTouchpadAccX -= (positive ? moveThresholdX : -moveThresholdX);
        }
        while (Math.abs(mTouchpadAccY) >= moveThresholdY) {
            boolean positive = mTouchpadAccY > 0;
            stepsY += positive ? 1 : -1;
            mTouchpadAccY -= (positive ? moveThresholdY : -moveThresholdY);
        }
        if (stepsX != 0 || stepsY != 0) mListener.onTouchpadMove(stepsX, stepsY);
    }

    private final Runnable mHapticRunnable = () -> {
        if (!mHasVibrated) {
            mListener.onCustomRequest(KeyboardActionListener.CustomAction.PERFORM_HAPTIC);
            mHasVibrated = true;
        }
    };

    private void stopHapticRunnable() {
        mHasVibrated = false;
        mHandler.removeCallbacks(mHapticRunnable);
    }

    private final Runnable mScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsScrolling && mListener != null) {
                int keyCode = getKeyCodeForDirection(mCurrentScrollDirection);

                mListener.onCodeInput(keyCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);

                mCurrentScrollDelay = Math.max(MIN_EDGE_ACCELERATION_DELAY, (long) (mCurrentScrollDelay * EDGE_ACCELERATION_FACTOR));

                mHandler.postDelayed(this, mCurrentScrollDelay);
            }
        }
    };

    private boolean handleEdgeScrolling(int x, int y) {
        Keyboard currentKeyboard = KeyboardSwitcher.getInstance().getKeyboard();
        if (currentKeyboard == null) return false;

        int keyboardHeight = currentKeyboard.mBaseHeight;
        int keyboardWidth = currentKeyboard.mBaseWidth;
        int thresholdX = (int) (keyboardWidth * EDGE_THRESHOLD_PERCENTAGE);
        int thresholdY = (int) (keyboardHeight * EDGE_THRESHOLD_PERCENTAGE);

        if (y <= thresholdY) {
            mCurrentScrollDirection = DIRECTION_UP;
            startEdgeScrolling();
            return true;
        } else if (y >= (keyboardHeight - thresholdY)) {
            mCurrentScrollDirection = DIRECTION_DOWN;
            startEdgeScrolling();
            return true;
        } else if (x <= thresholdX) {
            mCurrentScrollDirection = DIRECTION_LEFT;
            startEdgeScrolling();
            return true;
        } else if (x >= (keyboardWidth - thresholdX)) {
            mCurrentScrollDirection = DIRECTION_RIGHT;
            startEdgeScrolling();
            return true;
        } else {
            stopEdgeScrolling();
            return false;
        }
    }

    private void startEdgeScrolling() {
        if (!mIsScrolling) {
            mIsScrolling = true;
            mCurrentScrollDelay = getBaseScrollDelay();
            mHandler.removeCallbacks(mScrollRunnable);
            mHandler.post(mScrollRunnable);
        }
    }

    private void stopEdgeScrolling() {
        mIsScrolling = false;
        mHandler.removeCallbacks(mScrollRunnable);
    }

    private long getBaseScrollDelay() {
        int sensitivity = Settings.getInstance().getCurrent().mTouchpadSensitivity;

        // Calculates the base scroll delay based on user sensitivity (range: 0-100).
        // Maps sensitivity 0 to 300ms (slowest) and 100 to 50ms (fastest).
        return 300 - (long) (sensitivity * 2.5f);
    }

    private int getKeyCodeForDirection(int direction) {
        return switch (direction) {
            case DIRECTION_UP -> KeyCode.ARROW_UP;
            case DIRECTION_DOWN -> KeyCode.ARROW_DOWN;
            case DIRECTION_LEFT -> KeyCode.ARROW_LEFT;
            case DIRECTION_RIGHT -> KeyCode.ARROW_RIGHT;
            default -> KeyCode.UNSPECIFIED;
        };
    }
}
