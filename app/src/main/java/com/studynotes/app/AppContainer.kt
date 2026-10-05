package com.studynotes.app

import android.app.Application
import android.content.Context
import com.studynotes.app.data.AnnotationStore
import com.studynotes.app.data.AppSettings
import com.studynotes.app.docx.DocxRenditionManager
import com.studynotes.app.export.AnnotatedPdfExporter
import com.studynotes.app.library.LibraryRepository
import com.studynotes.app.library.LibraryStorage
import com.studynotes.app.library.ThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled dependency container; one per process, owned by [StudyNotesApp]. */
class AppContainer(app: Application) {
    val settings = AppSettings(app)
    val annotationStore = AnnotationStore()
    val renditions = DocxRenditionManager(app)
    val libraryStorage = LibraryStorage(app, settings)
    val libraryRepository = LibraryRepository(app, libraryStorage, annotationStore, settings, renditions)
    val exporter = AnnotatedPdfExporter(app)
    val thumbnails = ThumbnailLoader(app, libraryStorage, renditions)

    /** Outlives every screen — used for work that must finish after a screen closes (final saves). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as StudyNotesApp).container
