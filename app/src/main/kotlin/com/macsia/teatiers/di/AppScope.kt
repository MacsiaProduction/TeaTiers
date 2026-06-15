package com.macsia.teatiers.di

import javax.inject.Qualifier

/** Qualifies the application-lifetime [kotlinx.coroutines.CoroutineScope] used for DB seeding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope
