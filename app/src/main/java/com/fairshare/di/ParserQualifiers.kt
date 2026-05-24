package com.fairshare.di

import javax.inject.Qualifier

/** Tags the ML Kit on-device OCR parser implementation. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MlKit

/** Tags the Gemini AI fallback parser implementation. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Gemini
