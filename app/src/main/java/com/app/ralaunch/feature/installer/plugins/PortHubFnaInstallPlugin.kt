package com.app.ralaunch.feature.installer.plugins

import com.app.ralaunch.RaLaunchApp
import com.app.ralaunch.core.platform.runtime.RuntimeSpec
import com.app.ralaunch.feature.installer.BaseInstallPlugin
import com.app.ralaunch.feature.installer.GameDefinition
import com.app.ralaunch.feature.installer.GameDetectResult
import com.app.ralaunch.feature.installer.GameExtractorUtils
import com.app.ralaunch.feature.installer.InstallCallback
import com.app.ralaunch.feature.installer.ModLoaderDetectResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * PortHub: installs any FNA game from a ZIP of its PC files (FNA.dll next to the game's .exe), not just the
 * games the launcher knows by name, and runs it on the Mono engine (.NET Framework games). Steamworks.NET 20.x
 * games get a stand-in libsteam_api.so in their folder, so they start without Steam (see porthub/steam-stub).
 * Registered last: the dedicated plugins (Celeste, Terraria, Stardew) still handle their games.
 */
class PortHubFnaInstallPlugin : BaseInstallPlugin() {
    override val pluginId = "porthub-fna"
    override val displayName = "FNA game (PortHub)"
    override val supportedGames: List<GameDefinition> = emptyList()

    /** .exe files in FNA games that aren't the game itself. */
    private val helperExes = setOf("crashreporter.exe", "unitycrashhandler64.exe", "vc_redist.x64.exe", "dxsetup.exe")

    override fun detectGame(gameFile: File): GameDetectResult? {
        if (!gameFile.name.endsWith(".zip", ignoreCase = true)) return null
        return runCatching {
            ZipFile(gameFile).use { zip ->
                val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name.replace('\\', '/') }.toList()
                val root = names.firstOrNull { it.substringAfterLast('/').equals("FNA.dll", true) }
                    ?.substringBeforeLast('/', "") ?: return@use null
                val prefix = if (root.isEmpty()) "" else "$root/"
                // The game: an .exe next to FNA.dll; with several, the one with an .exe.config, else the biggest.
                val exes = names.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') &&
                    it.endsWith(".exe", true) && it.substringAfterLast('/').lowercase() !in helperExes }
                if (exes.isEmpty()) return@use null
                val exe = exes.firstOrNull { "$it.config" in names }
                    ?: exes.maxBy { zip.getEntry(it)?.size ?: 0L }
                val base = exe.substringAfterLast('/').removeSuffix(".exe").removeSuffix(".EXE")
                GameDetectResult(GameDefinition(
                    displayName = gameFile.nameWithoutExtension,
                    launchTarget = exe.removePrefix(prefix),
                    iconPatterns = listOf(base),
                    gameId = "PortHub_$base",
                ))
            }
        }.getOrNull()
    }

    override fun detectModLoader(modLoaderFile: File): ModLoaderDetectResult? = null

    override fun install(gameFile: File, modLoaderFile: File?, gameStorageRoot: File, callback: InstallCallback) {
        isCancelled = false
        installJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val definition = detectGame(gameFile)?.definition ?: error("${gameFile.name} isn't an FNA game")
                withContext(Dispatchers.Main) { callback.onProgress("Unpacking ${definition.displayName}…", 0) }
                if (!gameStorageRoot.exists()) gameStorageRoot.mkdirs()
                val result = GameExtractorUtils.extractZip(
                    zipFile = gameFile,
                    outputDir = gameStorageRoot,
                    progressCallback = { msg, progress ->
                        if (!isCancelled) CoroutineScope(Dispatchers.Main).launch {
                            callback.onProgress(msg, (progress * 90).toInt().coerceIn(0, 90))
                        }
                    },
                )
                if (result is GameExtractorUtils.ExtractResult.Error) {
                    withContext(Dispatchers.Main) { callback.onError(result.message) }
                    return@launch
                }
                if (isCancelled) {
                    withContext(Dispatchers.Main) { callback.onCancelled() }
                    return@launch
                }
                // The ZIP may hold the game in a subfolder: find the folder with the .exe.
                val exeFile = gameStorageRoot.walkTopDown().maxDepth(3)
                    .firstOrNull { it.isFile && it.name.equals(definition.launchTarget, true) }
                    ?: error("${definition.launchTarget} not found after unpacking")
                val gameDir = exeFile.parentFile!!
                com.app.ralaunch.feature.installer.PortHubSteamStub.ensure(RaLaunchApp.getInstance(), gameDir)
                val iconPath = extractIcon(gameDir, definition)
                createGameInfo(gameDir, definition, iconPath)
                val item = createGameItem(
                    definition = definition,
                    storageRootDir = gameStorageRoot,
                    actualGameDir = gameDir,
                    iconPath = iconPath,
                    runtimeOverride = RuntimeSpec.encodeMono(),
                )
                withContext(Dispatchers.Main) {
                    callback.onProgress("Done", 100)
                    callback.onComplete(item)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.message ?: "Couldn't install the game") }
            }
        }
    }

}
