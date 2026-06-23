package com.macsia.teatiers.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.macsia.teatiers.data.db.CatalogDao
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaDatabase
import com.macsia.teatiers.data.diagnostics.DiagnosticsPreferences
import com.macsia.teatiers.data.photos.AndroidImageReader
import com.macsia.teatiers.data.photos.AndroidPhotoStore
import com.macsia.teatiers.data.photos.ImageReader
import com.macsia.teatiers.data.photos.PhotoStore
import com.macsia.teatiers.data.repository.DataStoreOnboardingState
import com.macsia.teatiers.data.repository.OnboardingState
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * App-wide bindings. Room lands here in M1; DataStore and Retrofit @Provides follow in later
 * milestones. The ViewModels and SampleBoardProvider rely on constructor injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TeaDatabase =
        Room.databaseBuilder(context, TeaDatabase::class.java, "teatiers.db")
            .apply {
                // v6→v7 (tea/sample split, #132) is a ONE-TIME DESTRUCTIVE reset in every build,
                // including release: per owner decision (2026-06-23) the shipped v0.1.0 collection is
                // mock data, so there is no `Migration(6,7)` — Room drops everything and the
                // SampleBoardProvider reseeds. Once a real collection exists, the next bump goes back
                // to an explicit `Migration(7, N)` + a MigrationTestHelper test (no destructive
                // fallback) so a real user's data is never silently wiped.
                fallbackToDestructiveMigration(dropAllTables = true)
                // "tables were dropped" mark for the out-of-Room wipe sentinel (#111). Stored outside
                // Room so the wiping migration can't erase its own evidence.
                addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        DiagnosticsPreferences.markDestructiveMigration(context)
                    }
                })
            }
            .build()

    @Provides
    fun provideTeaDao(database: TeaDatabase): TeaDao = database.teaDao()

    @Provides
    fun provideCatalogDao(database: TeaDatabase): CatalogDao = database.catalogDao()

    // Outlives any ViewModel so first-run seeding completes even if the opening screen leaves.
    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Single Preferences DataStore for appearance settings (#28/#36). Reads/writes run on a private
     * IO scope so disk access never blocks the @AppScope (Default-dispatcher) seeding work.
     */
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ) { context.preferencesDataStoreFile("settings") }

    /**
     * Coil loader for user photos (local file/content URIs, #43) and the M3 catalog reference
     * images (remote https). The OkHttp network fetcher reuses the Retrofit [OkHttpClient] so image
     * traffic shares one connection pool and the (BASIC, debug-only) logging policy. Crossfade
     * smooths the swap from the type-swatch placeholder to the loaded image.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader =
        // On Android, Coil 3's PlatformContext is a typealias for android.content.Context, so the
        // Hilt-injected app context flows through directly.
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            .crossfade(true)
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PhotoStoreModule {
    @Binds
    @Singleton
    abstract fun bindPhotoStore(impl: AndroidPhotoStore): PhotoStore

    @Binds
    @Singleton
    abstract fun bindImageReader(impl: AndroidImageReader): ImageReader

    @Binds
    @Singleton
    abstract fun bindOnboardingState(impl: DataStoreOnboardingState): OnboardingState
}
