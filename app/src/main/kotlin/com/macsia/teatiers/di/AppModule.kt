package com.macsia.teatiers.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.macsia.teatiers.data.db.CatalogDao
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaDatabase
import com.macsia.teatiers.data.photos.AndroidImageReader
import com.macsia.teatiers.data.photos.AndroidPhotoStore
import com.macsia.teatiers.data.photos.ImageReader
import com.macsia.teatiers.data.photos.PhotoStore
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
            // v1 -> v2 (shared-teas, decisions.md #42) and v2 -> v3 (photos, decisions.md #43)
            // are destructive while we are pre-launch; first launch on the new schema drops
            // the older data and the sample provider reseeds. Real Migration(N, N+1) instances
            // become mandatory before we ship to a real user.
            .fallbackToDestructiveMigration(dropAllTables = true)
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
}
