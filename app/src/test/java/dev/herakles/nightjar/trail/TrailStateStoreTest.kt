package dev.herakles.nightjar.trail

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.modules.fireflyjar.FireflyDao
import dev.herakles.nightjar.modules.fireflyjar.FireflyDatabase
import dev.herakles.nightjar.modules.fireflyjar.FireflyMediaStore
import dev.herakles.nightjar.modules.fireflyjar.FireflyRecord
import dev.herakles.nightjar.modules.fireflyjar.FireflyRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * gate-36: "progress survives process death; skip and replay work; practice fireflies are
 * labelled and can be released like any other; Room stays at version 2." Uses the same
 * Robolectric + in-memory Room setup as [FireflyRepositoryTest] (real DAO, real
 * [FireflyMediaStore] against `filesDir`) so [TrailStateStore.releasePractice] exercises the
 * real [FireflyRepository] delete path, not a mock of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrailStateStoreTest {

    private lateinit var context: Context
    private lateinit var database: FireflyDatabase
    private lateinit var dao: FireflyDao
    private lateinit var repository: FireflyRepository
    private lateinit var practiceDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FireflyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.fireflyDao()
        repository = FireflyRepository(dao, FireflyMediaStore(context))
        practiceDir = File(context.filesDir, "practice")
        practiceDir.deleteRecursively()
        clearPrefs()
    }

    @After
    fun tearDown() {
        database.close()
        practiceDir.deleteRecursively()
        clearPrefs()
    }

    private fun clearPrefs() {
        context.getSharedPreferences("nightjar_trail", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun newStore(): TrailStateStore = TrailStateStore(context, repository)

    // ==============================================================================
    // Initial state / advance
    // ==============================================================================

    @Test
    fun initialStateStartsAtTheFirstStepWithNothingCompleted() {
        val state = newStore().state.value
        assertEquals(TrailStep.ART, state.currentStep)
        assertTrue(state.completedSteps.isEmpty())
        assertFalse(state.skipped)
        assertTrue(state.practiceRecordIds.isEmpty())
    }

    @Test
    fun advanceWalksEveryStepInOrderAndFinishesWithEverythingCompleted() {
        val store = newStore()

        store.advance(TrailStep.ART)
        assertEquals(TrailStep.HUMMING, store.state.value.currentStep)
        assertEquals(setOf(TrailStep.ART), store.state.value.completedSteps)

        store.advance(TrailStep.HUMMING)
        store.advance(TrailStep.SINGING)
        store.advance(TrailStep.MEADOW)
        store.advance(TrailStep.SEND)
        assertEquals(TrailStep.WORKSHOP, store.state.value.currentStep)

        store.advance(TrailStep.WORKSHOP)
        val finalState = store.state.value
        assertNull("trail is complete once the last step (workshop) advances", finalState.currentStep)
        assertEquals(TrailStep.ORDER.toSet(), finalState.completedSteps)
        assertFalse("completing the trail is not the same as skipping it", finalState.skipped)
    }

    // ==============================================================================
    // Skip
    // ==============================================================================

    @Test
    fun skipEndsTheTrailWithoutClearingCompletedStepsOrPracticeIds() {
        val store = newStore()
        store.advance(TrailStep.ART)
        store.markPractice(42L)

        store.skip()

        val state = store.state.value
        assertNull(state.currentStep)
        assertTrue(state.skipped)
        assertEquals(setOf(TrailStep.ART), state.completedSteps)
        assertEquals(setOf(42L), state.practiceRecordIds)
    }

    // ==============================================================================
    // markPractice / isPractice
    // ==============================================================================

    @Test
    fun markPracticeAndIsPracticeAgree() {
        val store = newStore()
        assertFalse(store.isPractice(7L))

        store.markPractice(7L)

        assertTrue(store.isPractice(7L))
        assertFalse("a different id must not read as practice", store.isPractice(8L))
    }

    // ==============================================================================
    // releasePractice
    // ==============================================================================

    @Test
    fun releasePracticeDeletesTheRecordThroughFireflyRepositoryAndStopsTrackingIt() = runBlocking {
        val store = newStore()
        dao.insert(
            FireflyRecord(
                moduleId = "IMAGE_LSB_CODEC",
                direction = "DECODE",
                timestampMillis = 1_000L,
                payloadSizeBytes = 10,
                technique = "EXACT",
                payloadPreview = "preview",
            ),
        )
        val inserted = dao.observeAll().first().single()
        store.markPractice(inserted.id)
        assertTrue(store.isPractice(inserted.id))

        store.releasePractice(inserted.id)

        assertFalse("released id must no longer read as practice", store.isPractice(inserted.id))
        assertNull("the underlying row must actually be gone", dao.getById(inserted.id))
    }

    // ==============================================================================
    // restart (regenerates the practice carrier files)
    // ==============================================================================

    @Test
    fun restartRegeneratesPracticeCarriersAndResetsProgressButKeepsPracticeIds() = runBlocking {
        val store = newStore()
        store.advance(TrailStep.ART)
        store.advance(TrailStep.HUMMING)
        store.markPractice(99L)
        assertTrue("no practice files before restart", practiceDir.listFiles().isNullOrEmpty())

        store.restart()

        val state = store.state.value
        assertEquals(TrailStep.ART, state.currentStep)
        assertTrue(state.completedSteps.isEmpty())
        assertFalse(state.skipped)
        assertEquals(
            "an un-released practice id survives restart (design/riddle-trail.md: left as-is, not deduplicated)",
            setOf(99L),
            state.practiceRecordIds,
        )

        for (jar in PracticeFireflies.Jar.entries) {
            assertTrue(
                "${jar.fileName} should exist after restart",
                PracticeFireflies.practiceFile(context, jar).exists(),
            )
        }
    }

    // ==============================================================================
    // Survival of a fresh store instance (simulates process death: same Context/prefs file,
    // new in-memory object).
    // ==============================================================================

    @Test
    fun aFreshStoreInstanceAgainstTheSameContextReloadsPersistedState() {
        val first = newStore()
        first.advance(TrailStep.ART)
        first.advance(TrailStep.HUMMING)
        first.markPractice(5L)
        first.markPractice(6L)

        val second = TrailStateStore(context, repository)
        val state = second.state.value

        assertEquals(TrailStep.SINGING, state.currentStep)
        assertEquals(setOf(TrailStep.ART, TrailStep.HUMMING), state.completedSteps)
        assertEquals(setOf(5L, 6L), state.practiceRecordIds)
        assertFalse(state.skipped)
    }

    @Test
    fun aFreshStoreInstanceAfterSkipReloadsTheSkippedState() {
        val first = newStore()
        first.advance(TrailStep.ART)
        first.skip()

        val second = TrailStateStore(context, repository)
        val state = second.state.value

        assertNull(state.currentStep)
        assertTrue(state.skipped)
        assertEquals(setOf(TrailStep.ART), state.completedSteps)
    }

    // ==============================================================================
    // W2-5 (design/riddle-trail.md § "Welcome + game layer"): welcomeSeen / finaleDismissed /
    // lastCompletedStep
    // ==============================================================================

    @Test
    fun freshStateHasNotSeenTheWelcomeCardAndHasNoFinaleOrPulsePending() {
        val state = newStore().state.value
        assertFalse(state.welcomeSeen)
        assertFalse(state.finaleDismissed)
        assertNull(state.lastCompletedStep)
    }

    @Test
    fun markWelcomeSeenSetsTheFlagAndPersistsIt() {
        val store = newStore()
        store.markWelcomeSeen()
        assertTrue(store.state.value.welcomeSeen)

        val reloaded = TrailStateStore(context, repository)
        assertTrue("welcomeSeen must survive a fresh store instance", reloaded.state.value.welcomeSeen)
    }

    @Test
    fun advanceRecordsLastCompletedStepAndAcknowledgeCompletionClearsIt() {
        val store = newStore()
        store.advance(TrailStep.ART)
        assertEquals(TrailStep.ART, store.state.value.lastCompletedStep)

        store.acknowledgeCompletion()
        assertNull(store.state.value.lastCompletedStep)
    }

    @Test
    fun eachAdvanceOverwritesTheHandoffToItsOwnStep() {
        val store = newStore()
        store.advance(TrailStep.ART)
        store.advance(TrailStep.HUMMING)
        assertEquals(
            "only the most recent completion should be pending a pulse",
            TrailStep.HUMMING,
            store.state.value.lastCompletedStep,
        )
    }

    @Test
    fun lastCompletedStepIsNeverPersisted() {
        val store = newStore()
        store.advance(TrailStep.ART)
        assertEquals(TrailStep.ART, store.state.value.lastCompletedStep)

        val reloaded = TrailStateStore(context, repository)
        assertNull("a pending pulse must not survive a fresh store instance", reloaded.state.value.lastCompletedStep)
    }

    @Test
    fun dismissFinaleSetsTheFlagAndPersistsIt() {
        val store = newStore()
        store.dismissFinale()
        assertTrue(store.state.value.finaleDismissed)

        val reloaded = TrailStateStore(context, repository)
        assertTrue("finaleDismissed must survive a fresh store instance", reloaded.state.value.finaleDismissed)
    }

    @Test
    fun restartClearsWelcomeSeenFinaleDismissedAndLastCompletedStep() = runBlocking {
        val store = newStore()
        store.markWelcomeSeen()
        store.advance(TrailStep.ART)
        store.dismissFinale()

        store.restart()

        val state = store.state.value
        assertFalse(
            "restart resets welcomeSeen (design doc: \"resets everything, including welcomeSeen\")",
            state.welcomeSeen,
        )
        assertFalse(state.finaleDismissed)
        assertNull(state.lastCompletedStep)
    }
}
