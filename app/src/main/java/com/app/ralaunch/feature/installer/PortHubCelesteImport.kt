package com.app.ralaunch.feature.installer

import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.app.ralaunch.core.di.contract.IGameRepositoryServiceV3
import com.app.ralaunch.core.model.GameItem
import com.app.ralaunch.core.platform.runtime.RuntimeSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * PortHub copies Celeste (the Linux build) to Internal storage/PortHub/Celeste (Linux) and packs it as
 * Celeste.zip there. On start, when no Celeste is in the game list yet, this adds it through the normal
 * Celeste installer, so the player doesn't have to tap + and pick the file.
 */
object PortHubCelesteImport {
    private const val PREFS = "porthub"
    private const val KEY_DONE = "celeste_imported"
    private const val KEY_MONO = "celeste_mono_set"

    /** One attempt per app process: an error isn't retried on every resume. */
    @Volatile private var attempted = false

    private fun zipFile(): File? {
        val dir = File(Environment.getExternalStorageDirectory(), "PortHub/Celeste (Linux)")
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.equals("Celeste.zip", ignoreCase = true) }
    }

    /**
     * The original Celeste (not Everest) is a .NET Framework game that runs on the launcher's Mono engine;
     * the global default, CoreCLR, can't start it ("libhostpolicy.so ... was not found").
     */
    private fun useMono(game: GameItem) {
        if (game.gameId.equals("Celeste", ignoreCase = true) && game.dotNetRuntimeVersionOverride.isNullOrBlank()) {
            game.dotNetRuntimeVersionOverride = RuntimeSpec.encodeMono()
        }
    }

    /** Builds before this fix added Celeste without the Mono override: set it once. */
    private fun fixRuntimeOfEarlierImport(activity: ComponentActivity, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_MONO, false)) return
        val repo = GlobalContext.get().get<IGameRepositoryServiceV3>()
        val games = repo.games.value
        if (games.isEmpty()) return // not loaded yet; try on the next resume
        prefs.edit().putBoolean(KEY_MONO, true).apply()
        val index = games.indexOfFirst { it.gameId.equals("Celeste", true) && it.dotNetRuntimeVersionOverride.isNullOrBlank() }
        if (index < 0) return
        val game = games[index]
        useMono(game)
        activity.lifecycleScope.launch(Dispatchers.IO) { repo.upsert(game, index) }
    }

    fun maybeRun(activity: ComponentActivity) {
        if (attempted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) {
            fixRuntimeOfEarlierImport(activity, prefs)
            return
        }
        val zip = zipFile() ?: return
        val repo = GlobalContext.get().get<IGameRepositoryServiceV3>()
        if (repo.games.value.any { it.gameId.equals("Celeste", true) || it.gameId.equals("Everest", true) }) {
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            return
        }
        attempted = true
        val app = activity.applicationContext
        Toast.makeText(app, "Adding Celeste from PortHub…", Toast.LENGTH_LONG).show()
        GameInstaller(repo).install(zip.absolutePath, null, object : InstallCallback {
            override fun onProgress(message: String, progress: Int) {}

            override fun onComplete(gameItem: GameItem) {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    useMono(gameItem)
                    repo.upsert(gameItem, 0)
                    prefs.edit().putBoolean(KEY_DONE, true).putBoolean(KEY_MONO, true).apply()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, "Celeste added from PortHub.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onError(error: String) {
                Toast.makeText(app, "Couldn't add Celeste from PortHub: $error", Toast.LENGTH_LONG).show()
            }

            override fun onCancelled() {}
        })
    }
}
