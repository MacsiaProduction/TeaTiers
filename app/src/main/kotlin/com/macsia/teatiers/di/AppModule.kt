package com.macsia.teatiers.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * App-wide bindings. Empty for Phase 0 — SampleBoardProvider and the ViewModels rely on
 * constructor injection. Room, DataStore, and Retrofit @Provides land here in M1–M3.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
