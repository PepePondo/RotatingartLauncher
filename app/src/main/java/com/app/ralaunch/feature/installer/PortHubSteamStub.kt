package com.app.ralaunch.feature.installer

import android.content.Context
import java.io.File

/**
 * PortHub: Steamworks.NET 20.x games stop when Steam doesn't start. This gives them PortHub's stand-in Steam API
 * (porthub/steam-stub, which reports "Steam running, nothing to report").
 *
 * Android won't load native code from shared storage, where the launcher keeps games, so the library goes to
 * the app's private files and a Steamworks.NET.dll.config next to the game maps "steam_api" to it (Mono reads
 * per-assembly dllmaps). Only games that ship Steamworks.NET 20.x get the config; others keep their behaviour.
 */
object PortHubSteamStub {
    /** Sets the stand-in up for the game in [gameDir] when it needs it. Safe to call on every launch. */
    fun ensure(context: Context, gameDir: File) {
        dropStartupSection(gameDir)
        val steamworks = File(gameDir, "Steamworks.NET.dll")
        if (!steamworks.isFile || !isVersion20(steamworks)) return
        val lib = File(File(context.filesDir, "porthub"), "libsteam_api.so")
        if (!lib.isFile) {
            lib.parentFile?.mkdirs()
            context.assets.open("porthub/libsteam_api.so").use { input -> lib.outputStream().use { input.copyTo(it) } }
            lib.setReadable(true, false)
        }
        val config = File(gameDir, "Steamworks.NET.dll.config")
        val wanted = """<?xml version="1.0" encoding="utf-8"?>
<!-- PortHub: Steam isn't on Android; map Steamworks.NET to PortHub's stand-in Steam API. -->
<configuration>
  <dllmap dll="steam_api" target="${lib.absolutePath}" />
  <dllmap dll="steam_api64" target="${lib.absolutePath}" />
</configuration>
"""
        if (!config.isFile || config.readText() != wanted) config.writeText(wanted)
        // A copy next to the game can't be loaded from shared storage and only confuses things.
        File(gameDir, "libsteam_api.so").delete()
    }

    /**
     * Windows .exe.config files carry a <startup\b> block (which .NET Framework to use). The launcher's Mono has no
     * machine.config declaring it, so System.Configuration rejects the whole file and anything reading settings
     * (log4net, XmlReader's resolver) fails. The block means nothing on Mono: drop it.
     */
    private fun dropStartupSection(gameDir: File) {
        gameDir.listFiles { f -> f.name.endsWith(".exe.config", ignoreCase = true) }?.forEach { config ->
            runCatching {
                val text = config.readText()
                val fixed = text.replace(Regex("""<startup\b.*?</startup>|<startup\b[^>]*/>""",RegexOption.DOT_MATCHES_ALL), "")
                if (fixed != text) config.writeText(fixed)
            }
        }
    }

    /** AssemblyFileVersion is stored as UTF-16 text: look for "20." (the API the stand-in was generated from). */
    private fun isVersion20(dll: File): Boolean = runCatching {
        Regex("2\u00000\u0000\\.\u0000[0-9]").containsMatchIn(String(dll.readBytes(), Charsets.ISO_8859_1))
    }.getOrDefault(false)
}
