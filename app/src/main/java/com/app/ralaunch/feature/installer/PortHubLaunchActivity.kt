package com.app.ralaunch.feature.installer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * PortHub's entry point: PortHub starts this with "porthub_game_path", the ZIP of a game it copied to
 * Internal storage/PortHub/<Game>. Reading it there needs All files access (asked once); then the game is
 * remembered as pending and the launcher starts normally, and [PortHubGameLaunch] adds it (first time) and
 * launches it from the main screen.
 */
class PortHubLaunchActivity : Activity() {
    private var askedForAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askedForAccess = savedInstanceState?.getBoolean("asked") ?: false
        if (!askedForAccess) decide()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("asked", askedForAccess)
    }

    override fun onResume() {
        super.onResume()
        if (askedForAccess) {
            askedForAccess = false
            if (hasAccess()) openLauncher() else finish()
        }
    }

    private fun hasAccess() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun decide() {
        val path = intent.getStringExtra(EXTRA_GAME_PATH)
        // Before All files access, Android hides other apps' files but not the folder: check that instead.
        if (path == null || File(path).parentFile?.isDirectory != true) {
            finish()
            return
        }
        if (hasAccess()) {
            openLauncher()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow access to the game")
            .setMessage("PortHub copied the game to ${path.replace(Environment.getExternalStorageDirectory().path, "Internal storage")}. " +
                "To add it, switch on \"All files access\" for this app on the next screen, then come back.")
            .setCancelable(false)
            .setPositiveButton("Open settings") { _, _ ->
                askedForAccess = true
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun openLauncher() {
        getSharedPreferences(PortHubGameLaunch.PREFS, Context.MODE_PRIVATE).edit()
            .putString(PortHubGameLaunch.KEY_PENDING, intent.getStringExtra(EXTRA_GAME_PATH)).apply()
        val start = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(this, "com.app.ralaunch.feature.init.ui.InitializationActivity")
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(start)
        finish()
    }

    companion object {
        const val EXTRA_GAME_PATH = "porthub_game_path"
    }
}
