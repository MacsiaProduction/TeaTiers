package com.macsia.teatiers.di

import android.content.Context
import androidx.room.Room
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        Room.databaseBuilder(context, TeaDatabase::class.java, "teatiers.db").build()

    @Provides
    fun provideTeaDao(database: TeaDatabase): TeaDao = database.teaDao()

    // Outlives any ViewModel so first-run seeding completes even if the opening screen leaves.
    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
