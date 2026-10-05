package com.inkproof.app

import android.app.Application
import com.inkproof.app.check.BackendCheckProvider
import com.inkproof.app.check.CheckProvider
import com.inkproof.app.check.CheckWorkEngine
import com.inkproof.app.check.HandwritingRecognizer
import com.inkproof.app.check.LocalDigitalInkRecognizer
import com.inkproof.app.check.MockCheckProvider
import com.inkproof.app.check.MockRecognizer
import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.data.repo.CheckRepository
import com.inkproof.app.data.repo.LibraryRepository
import com.inkproof.app.data.repo.PageRepository
import com.inkproof.app.data.settings.SettingsStore
import com.inkproof.app.pdf.ImageImporter
import com.inkproof.app.pdf.PdfExporter
import com.inkproof.app.pdf.PdfImporter
import com.inkproof.app.pdf.TextImporter

/**
 * Manual dependency wiring — small, explicit and easy to test.
 *
 * Mock isolation: the mock providers are selected ONLY through
 * [checkEngine]'s mockMode flag; they can never leak into a real check,
 * because the provider is chosen per-call from current settings.
 */
class InkProofApp : Application() {

    val database: InkProofDatabase by lazy { InkProofDatabase.get(this) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(database) }
    val pageRepository: PageRepository by lazy { PageRepository(database) }
    val checkRepository: CheckRepository by lazy { CheckRepository(database) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val pdfImporter: PdfImporter by lazy { PdfImporter(this, libraryRepository) }
    val pdfExporter: PdfExporter by lazy { PdfExporter(this, libraryRepository, pageRepository) }
    val imageImporter: ImageImporter by lazy { ImageImporter(this, libraryRepository) }
    val textImporter: TextImporter by lazy {
        TextImporter(this, libraryRepository, pageRepository)
    }

    private val mockProvider: CheckProvider by lazy { MockCheckProvider() }
    private val mockRecognizer: HandwritingRecognizer by lazy { MockRecognizer() }
    private val localRecognizer: HandwritingRecognizer by lazy { LocalDigitalInkRecognizer() }

    // Backend provider is cached per effective URL so a Settings change
    // takes effect on the very next check — no app restart needed.
    @Volatile private var cachedBackend: Pair<String, CheckProvider>? = null

    private fun backendProvider(url: String): CheckProvider {
        cachedBackend?.let { (cachedUrl, provider) ->
            if (cachedUrl == url) return provider
        }
        val provider = BackendCheckProvider(url)
        cachedBackend = url to provider
        return provider
    }

    fun checkEngine(
        mockMode: Boolean,
        confidenceThreshold: Float = 0.4f,
        backendUrl: String = ""
    ): CheckWorkEngine {
        val effectiveUrl = backendUrl.ifBlank { BuildConfig.BACKEND_BASE_URL }
        return CheckWorkEngine(
            pageRepository = pageRepository,
            checkRepository = checkRepository,
            recognizer = if (mockMode) mockRecognizer else localRecognizer,
            provider = if (mockMode) mockProvider else backendProvider(effectiveUrl),
            confidenceThreshold = confidenceThreshold
        )
    }
}
