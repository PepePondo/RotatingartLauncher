package com.app.ralaunch.feature.installer

import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.app.ralaunch.core.common.GameLaunchManager
import com.app.ralaunch.core.di.contract.IGameRepositoryServiceV3
import com.app.ralaunch.core.model.GameItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * PortHub: starts the game PortHub asked for ([PortHubLaunchActivity] leaves its ZIP path as pending). The first
 * time, the ZIP is added through the launcher's own installer (PortHub's FNA plugin for games it doesn't know);
 * then, and on every later launch from PortHub, the game is started straight away.
 */
object PortHubGameLaunch {
    const val PREFS = "porthub"
    const val KEY_PENDING = "pending_game"
    private const val KEY_ADDED_PREFIX = "added:"

    @Volatile private var running = false

    /** True once PortHub has opened the launcher in this process: its own pop-ups (announcements, updates) wait. */
    @Volatile var openedByPortHub = false
        private set

    /** PortHub has a game waiting (set before the first-run screens). */
    fun hasPending(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, null) != null

    fun maybeRun(activity: ComponentActivity) {
        if (running) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PENDING, null) ?: return
        prefs.edit().remove(KEY_PENDING).apply()
        openedByPortHub = true
        val zip = File(path)
        if (!zip.isFile) return
        running = true
        val repo = GlobalContext.get().get<IGameRepositoryServiceV3>()
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            try {
                // The game list loads in the background; give it a moment on a cold start.
                repeat(20) { if (repo.games.value.isEmpty()) delay(250) }
                val storageId = prefs.getString(KEY_ADDED_PREFIX + path, null)
                val existing = repo.games.value.firstOrNull { it.id == storageId }
                if (existing != null) {
                    start(activity, existing)
                } else {
                    install(activity, repo, zip) { game ->
                        prefs.edit().putString(KEY_ADDED_PREFIX + path, game.id).apply()
                        start(activity, game)
                    }
                }
            } finally {
                running = false
            }
        }
    }

    private fun start(activity: ComponentActivity, game: GameItem) {
        // Games added before the stand-in Steam API was set up get it now.
        game.gameExePathFull?.let { File(it).parentFile }?.let { runCatching { PortHubSteamStub.ensure(activity, it) } }
        if (!GameLaunchManager(activity).launchGame(game)) {
            Toast.makeText(activity, "Couldn't start ${game.displayedName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun install(activity: ComponentActivity, repo: IGameRepositoryServiceV3, zip: File, then: (GameItem) -> Unit) {
        val app = activity.applicationContext
        Toast.makeText(app, "Adding ${zip.nameWithoutExtension} from PortHub…", Toast.LENGTH_LONG).show()
        GameInstaller(repo).install(zip.absolutePath, null, object : InstallCallback {
            override fun onProgress(message: String, progress: Int) {}

            override fun onComplete(gameItem: GameItem) {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    repo.upsert(gameItem, 0)
                    withContext(Dispatchers.Main) { then(gameItem) }
                }
            }

            override fun onError(error: String) {
                Toast.makeText(app, "Couldn't add ${zip.nameWithoutExtension} from PortHub: $error", Toast.LENGTH_LONG).show()
            }

            override fun onCancelled() {}
        })
    }
}
