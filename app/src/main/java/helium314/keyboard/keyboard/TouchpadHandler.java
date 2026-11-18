// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard;

/**
 * Space-bar trackpad. The finger's pixel deltas are forwarded as they arrive.
 * {@link KeyboardActionListenerImpl} turns them into a caret position once per frame.
 *
 * This replaces the older step-counter trackpad. Edge-scroll while the finger sits on the
 * keyboard rim is not part of this mode: the caret follows the finger in both directions.
 */
public class TouchpadHandler {
    private KeyboardActionListener mListener;
    private static boolean sTouchpadModeActive = false;
    private boolean mInTouchpadMode = false;
    private int mLastX;
    private int mLastY;

    public static void setTouchpadModeActive(boolean active) {
        sTouchpadModeActive = active;
    }

    public boolean isInTouchpadMode() {
        return mInTouchpadMode;
    }

    public void disableTouchpadMode() {
        if (!mInTouchpadMode) return;
        mInTouchpadMode = false;
        sTouchpadModeActive = false;
        if (mListener != null) mListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_OFF);
        mListener = null;
    }

    /** First call while the mode is armed starts it. Later calls report movement. */
    public void enableTouchpadMove(int x, int y, KeyboardActionListener listener) {
        if (!sTouchpadModeActive) return;
        if (!mInTouchpadMode) {
            mListener = listener;
            mInTouchpadMode = true;
            mLastX = x;
            mLastY = y;
            mListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_ON);
            return;
        }
        onPointerMove(x, y);
    }

    public void onPointerMove(int x, int y) {
        if (!mInTouchpadMode || mListener == null) return;
        final float dx = x - mLastX;
        final float dy = y - mLastY;
        mLastX = x;
        mLastY = y;
        if (dx == 0f && dy == 0f) return;
        // nanoTime, not the motion-event clock: the listener compares this with the frame time.
        mListener.onTouchpadDelta(dx, dy, System.nanoTime());
    }
}
