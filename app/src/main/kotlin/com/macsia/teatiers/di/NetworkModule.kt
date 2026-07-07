package com.macsia.teatiers.di

import com.macsia.teatiers.BuildConfig
import com.macsia.teatiers.data.remote.AppUpdateApi
import com.macsia.teatiers.data.remote.CatalogApi
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.DefaultCatalogRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Catalog networking (M3). One Retrofit instance against [BuildConfig.CATALOG_BASE_URL] with the
 * kotlinx.serialization converter. The catalog API is read-only and holds no user data, so there
 * is no auth here; logging is BASIC (request line only) and gated to debug builds — never BODY,
 * never in release (rule 50-secure: no verbose logging shipped; the query is user input).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // UX2-P1-6: the server's OCR sidecar call allows up to 2 attempts * (3s connect + 20s read) ~= 46s
    // worst case (OcrProperties/OcrClient) before it degrades. The app's default 15s read timeout is
    // shorter than that, so a live-but-slow sidecar looked like "you're offline" (wrong — the network
    // is fine, the OCR call was just still running). Give only the OCR call the longer budget.
    private const val OCR_READ_TIMEOUT_SECONDS = 60L

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath.endsWith("/teas/ocr")) {
                    chain.withReadTimeout(OCR_READ_TIMEOUT_SECONDS.toInt(), TimeUnit.SECONDS).proceed(request)
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.CATALOG_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideCatalogApi(retrofit: Retrofit): CatalogApi = retrofit.create(CatalogApi::class.java)

    @Provides
    @Singleton
    fun provideAppUpdateApi(retrofit: Retrofit): AppUpdateApi = retrofit.create(AppUpdateApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: DefaultCatalogRepository): CatalogRepository
}
