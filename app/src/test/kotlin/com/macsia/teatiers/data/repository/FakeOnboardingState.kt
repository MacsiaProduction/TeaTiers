package com.macsia.teatiers.data.repository

/** In-memory [OnboardingState] for tests (review §5 sample-reseed gate). */
class FakeOnboardingState(var seeded: Boolean = false) : OnboardingState {
    override suspend fun isSeeded(): Boolean = seeded
    override suspend fun markSeeded() {
        seeded = true
    }
}
