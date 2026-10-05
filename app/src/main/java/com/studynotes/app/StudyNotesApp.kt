package com.studynotes.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch
import java.io.File

class StudyNotesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Cheap: only stores the AssetManager. PdfBox (used for export/unlock) fails without it.
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
        container.appScope.launch { cleanCache() }
    }

    /** Removes scratch files PdfBox leaves behind if the process died mid-export. */
    private fun cleanCache() {
        cacheDir.listFiles { f: File -> f.name.startsWith("PDFBox") && f.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }
}
