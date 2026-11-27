package com.app.ralaunch.feature.game

import android.app.Activity
import android.content.Context
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.app.ralaunch.core.logging.AppLog
import com.app.ralaunch.feature.controls.packs.ControlPackManager
import org.koin.java.KoinJavaComponent

/**
 * PortHub: on-screen controls for the games PortHub adds (game ids "PortHub_…"), which play like controller games.
 *
 * - If no control layout is chosen yet, a "PortHub controller" layout (a virtual Xbox pad: stick, d-pad, ABXY, shoulders,
 *   triggers, Start/Back) is installed and chosen, so controls appear without setting anything up. A layout the
 *   player chose or edited is left alone.
 * - The controls show for PortHub games whatever the touch mode, hide while a real controller (the handheld's own
 *   buttons, a paired pad) is used, and come back on the next touch.
 */
object PortHubTouch {
    private const val TAG = "PortHubTouch"
    private const val PREFS = "porthub"
    private const val KEY_PACK = "controller_pack_id"

    fun isPortHubGame(activity: Activity): Boolean {
        // Games are usually started by storage id (the game's folder name, "PortHub_<Game>_<hash>").
        val extras = listOf("GAME_STORAGE_ID", "GAME_ID").map { activity.intent?.getStringExtra(it).orEmpty() }
        val exe = activity.intent?.getStringExtra("GAME_EXE_PATH").orEmpty()
        return extras.any { it.startsWith("PortHub_") } || exe.contains("/PortHub_")
    }

    /** Installs and picks the PortHub controller layout when no layout is picked. */
    fun ensureLayout(context: Context) {
        runCatching {
            val packs: ControlPackManager = KoinJavaComponent.get(ControlPackManager::class.java)
            if (packs.getSelectedPackId() != null && packs.getCurrentLayout() != null) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_PACK, null)?.takeIf { packs.isPackInstalled(it) }
            if (id == null) {
                val json = context.assets.open("porthub/controller_layout.json").bufferedReader().use { it.readText() }
                id = packs.importFromJsonString(json, "PortHub controller").getOrThrow().id
                prefs.edit().putString(KEY_PACK, id).apply()
            }
            packs.setSelectedPackId(id)
        }.onFailure { AppLog.w(TAG, "Couldn't set up the PortHub controller layout", it) }
    }

    private var hiddenByController = false

    /** Feed every key, motion and touch event: hides the controls for a real controller, shows them on touch. */
    fun onInput(event: InputEvent, controls: GameVirtualControlsManager) {
        if (event is MotionEvent && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            if (hiddenByController && event.actionMasked == MotionEvent.ACTION_DOWN) {
                hiddenByController = false
                controls.setVisible(true)
            }
            return
        }
        val device = event.device ?: return
        if (device.isVirtual) return
        val pad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (!pad) return
        if (event is KeyEvent && event.keyCode == KeyEvent.KEYCODE_BACK) return
        if (!hiddenByController) {
            hiddenByController = true
            controls.setVisible(false)
        }
    }
}
