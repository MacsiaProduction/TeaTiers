package com.macsia.teatiers.data.repository

/** In-memory [OnboardingState] for tests (review §5 sample-reseed gate). */
class FakeOnboardingState(var seeded: Boolean = false, var reseedPending: Boolean = false) : OnboardingState {
    override suspend fun isSeeded(): Boolean = seeded

    override suspend fun consumeReseedPending(): Boolean {
        val pending = reseedPending
        reseedPending = false
        return pending
    }

    override suspend fun markSeeded() {
        seeded = true
    }
}
