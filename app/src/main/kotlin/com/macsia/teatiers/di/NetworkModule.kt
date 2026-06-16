package com.macsia.teatiers.di

import com.macsia.teatiers.BuildConfig
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: DefaultCatalogRepository): CatalogRepository
}
