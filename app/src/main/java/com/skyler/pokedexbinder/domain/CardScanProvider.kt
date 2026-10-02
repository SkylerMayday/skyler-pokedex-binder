package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.skyler.pokedexbinder.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

internal enum class ScanProvider { GEMINI, CLAUDE }

/** Gemini first, then Claude; a blank key means that provider is absent. */
internal fun selectProviders(geminiKey: String, claudeKey: String): List<ScanProvider> = buildList {
    if (geminiKey.isNotBlank()) add(ScanProvider.GEMINI)
    if (claudeKey.isNotBlank()) add(ScanProvider.CLAUDE)
}

/**
 * Tries Gemini first; on any non-cancellation failure resends the bitmap to Claude (when a Claude
 * key is set). Logs carry provider and exception class names only — never keys or messages.
 */
@Singleton
class CardScanProvider @Inject constructor(
    private val gemini: GeminiCardScanner,
    private val claude: ClaudeCardScanner,
    private val settings: SettingsRepository
) {
    /** Test seam: Log is unavailable in JVM unit tests. */
    internal var log: (String) -> Unit = { android.util.Log.i("ScannerMatch", it) }

    suspend fun scan(bitmap: Bitmap): ParsedCardInfo {
        val geminiKey = settings.getGeminiApiKey().trim()
        val claudeKey = settings.getAnthropicApiKey().trim()
        val providers = selectProviders(geminiKey, claudeKey)
        if (providers.isEmpty()) throw NoApiKeyException()

        var geminiError: Exception? = null
        if (ScanProvider.GEMINI in providers) {
            try {
                return gemini.scan(bitmap, geminiKey).also { log("scan provider=gemini") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ScanProvider.CLAUDE !in providers) throw e
                geminiError = e
            }
        }

        val fallbackReason = geminiError?.let { it::class.java.simpleName } ?: "none"
        try {
            return claude.scan(bitmap, claudeKey).also {
                log("scan provider=claude fallbackReason=$fallbackReason")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            geminiError?.let { e.addSuppressed(it) }
            log("scan provider=none geminiError=$fallbackReason claudeError=${e::class.java.simpleName}")
            throw e
        }
    }
}
