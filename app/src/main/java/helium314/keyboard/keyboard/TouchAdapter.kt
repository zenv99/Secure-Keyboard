// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * On-device learning of how the user taps (similar to how iOS adapts to your typing).
 * It only stores three numbers (average tap offset in x and y, and a tap count) in a private
 * file on this phone. Nothing is ever sent anywhere and no typed text is stored here.
 * Turning the setting off wipes what was learned.
 */
object TouchAdapter {
    private const val FILE = "touch_adapt"
    private const val SAVE_EVERY = 25

    private val learner = TouchOffsetLearner()
    private var store: SharedPreferences? = null
    private var settings: SharedPreferences? = null
    private var sinceSave = 0

    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !context.isDeviceProtectedStorage)
            context.createDeviceProtectedStorageContext() ?: context
        else context
        val s = storageContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        store = s
        settings = context.prefs()
        learner.meanX = s.getFloat("x", 0f)
        learner.meanY = s.getFloat("y", 0f)
        learner.samples = s.getInt("n", 0)
    }

    private fun enabled(): Boolean {
        val enabled = settings?.getBoolean(Settings.PREF_TOUCH_ADAPTATION, Defaults.PREF_TOUCH_ADAPTATION) ?: false
        if (!enabled && learner.samples != 0) {
            learner.reset()
            save()
        }
        return enabled
    }

    /** Called for every tap on a letter key, with the raw touch position. */
    @JvmStatic
    @Synchronized
    fun learn(key: Key, rawX: Int, rawY: Int) {
        if (store == null || personalizationBlocked() || !enabled()) return
        val width = key.width
        val height = key.height
        if (width <= 0 || height <= 0) return
        val fx = (rawX - (key.x + width / 2f)) / width
        val fy = (rawY - (key.y + height / 2f)) / height
        learner.add(fx, fy)
        if (++sinceSave >= SAVE_EVERY) save()
    }

    /** Horizontal shift in pixels to add to touches. */
    @JvmStatic
    @Synchronized
    fun offsetX(keyWidth: Int): Int =
        if (store == null || personalizationBlocked() || !enabled()) 0 else Math.round(learner.correctionX() * keyWidth)

    /** Vertical shift in pixels to add to touches. */
    @JvmStatic
    @Synchronized
    fun offsetY(keyHeight: Int): Int =
        if (store == null || personalizationBlocked() || !enabled()) 0 else Math.round(learner.correctionY() * keyHeight)

    /** Password and no-learning fields must not update the stored tap offset. */
    private fun personalizationBlocked(): Boolean {
        val values = Settings.getInstance()?.current ?: return true
        return values.mIncognitoModeEnabled
    }

    /** Drops the three stored tap-offset numbers. Used by "wipe everything learned". */
    @JvmStatic
    @Synchronized
    fun wipe(context: Context) {
        init(context)
        learner.reset()
        sinceSave = 0
        store?.edit()?.clear()?.commit()
    }

    private fun save() {
        sinceSave = 0
        store?.edit()?.putFloat("x", learner.meanX)?.putFloat("y", learner.meanY)?.putInt("n", learner.samples)?.apply()
    }
}
