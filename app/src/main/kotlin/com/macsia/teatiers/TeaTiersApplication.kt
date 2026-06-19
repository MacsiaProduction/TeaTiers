package com.macsia.teatiers

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.macsia.teatiers.data.diagnostics.DiagnosticsPreferences
import com.macsia.teatiers.data.diagnostics.MigrationSentinel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import org.acra.ReportField
import org.acra.config.limiter
import org.acra.data.StringFormat
import org.acra.ktx.initAcra

/**
 * Routes Coil's process-wide singleton (used by every `AsyncImage`) through the Hilt-configured
 * [ImageLoader] so user photos and the M3 catalog reference images share one loader: the Retrofit
 * OkHttp client (one connection pool) and the crossfade policy. Pulled via an entry point because
 * the [Application] itself is the Hilt component owner and cannot be member-injected.
 *
 * It also owns the opt-in diagnostics lifecycle (decision #111): ACRA's crash hook is installed in
 * [attachBaseContext] (which runs before Hilt) **only when the user opted in**, and the out-of-Room
 * migration sentinel runs once per launch from [onCreate].
 */
@HiltAndroidApp
class TeaTiersApplication : Application(), SingletonImageLoader.Factory {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Opt-in only (off by default). Pre-Hilt, so the flag is read synchronously from prefs.
        if (!DiagnosticsPreferences.isEnabled(base)) return
        initAcra {
            reportFormat = StringFormat.JSON
            // Minimal capture: only the stack trace. Every other field in the report is re-read from
            // stable Build/BuildConfig by our sender, so nothing ACRA collects by default can leak.
            reportContent = listOf(ReportField.STACK_TRACE)
            // Our sender (DiagnosticsReportSenderFactory) is registered via ServiceLoader
            // (META-INF/services) and maps to the allowlisted DTO. We deliberately do NOT configure
            // ACRA's built-in httpSender, so it stays disabled and only our sender posts.
            // acra-limiter throttles repeated/looping crashes so one bad build can't flood the endpoint.
            limiter {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Hilt has injected by now; pull the sentinel via an entry point (the Application can't be
        // member-injected) and run the wipe check off the main thread. No-op unless the user opted in.
        EntryPointAccessors.fromApplication(this, DiagnosticsEntryPoint::class.java)
            .migrationSentinel()
            .scheduleCheck()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        EntryPointAccessors.fromApplication(this, ImageLoaderEntryPoint::class.java).imageLoader()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ImageLoaderEntryPoint {
        fun imageLoader(): ImageLoader
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DiagnosticsEntryPoint {
        fun migrationSentinel(): MigrationSentinel
    }
}
