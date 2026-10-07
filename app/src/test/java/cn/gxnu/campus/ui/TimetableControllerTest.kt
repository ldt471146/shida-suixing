package cn.gxnu.campus.ui

import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.data.CiphertextStorage
import cn.gxnu.campus.data.CredentialKeyAccess
import cn.gxnu.campus.data.TimetableStorage
import cn.gxnu.campus.data.TimetableStore
import cn.gxnu.campus.data.VisionApiKeyStore
import cn.gxnu.campus.network.TimetableImage
import cn.gxnu.campus.network.TimetableVisionClient
import cn.gxnu.campus.network.TimetableVisionException
import cn.gxnu.campus.network.TimetableVisionFailure
import java.time.LocalDate
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole pick → recognise → persist → delete flow with a faked vision client. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableControllerTest {

    private class MemoryTimetableStorage(private val failWrites: Boolean = false) : TimetableStorage {
        var value: String? = null
        var termStart: Long? = null
        override fun read(): String? = value
        override fun write(value: String) {
            if (failWrites) throw IllegalStateException("fixture disk full")
            this.value = value
        }
        override fun remove() { value = null }
        override fun readTermStart(): Long? = termStart
        override fun writeTermStart(epochDay: Long) { termStart = epochDay }
        override fun removeTermStart() { termStart = null }
    }

    private class MemoryCiphertext : CiphertextStorage {
        var value: ByteArray? = null
        override fun read() = value?.clone()
        override fun write(value: ByteArray) { this.value = value.clone() }
        override fun remove() { value = null }
    }

    private class FakeVisionClient(private val answer: () -> Timetable) : TimetableVisionClient {
        var calls = 0
        var lastKey: String? = null
        override suspend fun recognize(image: TimetableImage, apiKey: String): Timetable {
            calls++
            lastKey = apiKey
            return answer()
        }
    }

    private val vaultKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun TestScope.controller(
        client: TimetableVisionClient = FakeVisionClient { TIMETABLE },
        storage: TimetableStorage = MemoryTimetableStorage(),
        keyStorage: CiphertextStorage = MemoryCiphertext(),
        builtInKey: String = "",
        today: LocalDate = TODAY,
        timeoutMillis: Long = TimetableController.DEFAULT_RECOGNITION_TIMEOUT_MILLIS
    ) = TimetableController(
        client = client,
        store = TimetableStore(storage),
        keys = VisionApiKeyStore(keyStorage, CredentialKeyAccess { vaultKey }),
        // An own unconfined scope, because runTest's backgroundScope work is not run by
        // advanceUntilIdle and it would stall the controller's own coroutines.
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        builtInKey = builtInKey,
        today = { today },
        recognitionTimeoutMillis = timeoutMillis
    )

    private fun keyVault(apiKey: String): MemoryCiphertext = MemoryCiphertext().also {
        VisionApiKeyStore(it, CredentialKeyAccess { vaultKey }).save(apiKey)
    }

    @Test fun restoringPublishesTheSavedTimetableAndTheKeyHint() = runTest {
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(TIMETABLE) }
        val controller = controller(storage = storage, keyStorage = keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()
        val state = controller.state.value
        assertFalse(state.restoring)
        assertEquals(TIMETABLE, state.timetable)
        assertTrue(state.keyConfigured)
        assertEquals("••••cdef", state.keyHint)
    }

    @Test fun anEmptyDeviceShowsTheEmptyState() = runTest {
        val controller = controller()
        controller.restore()
        advanceUntilIdle()
        val state = controller.state.value
        assertFalse(state.restoring)
        assertNull(state.timetable)
        assertFalse(state.keyConfigured)
        assertEquals("", state.keyHint)
        assertNull(state.message)
    }

    @Test fun anImageIsRecognisedSavedAndShown() = runTest {
        val client = FakeVisionClient { TIMETABLE }
        val storage = MemoryTimetableStorage()
        val controller = controller(client, storage, keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()

        controller.useImage(IMAGE)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(1, client.calls)
        // The key is read back out of the vault for the request rather than cached in the state.
        assertEquals("sk-fixture-abcdef", client.lastKey)
        assertFalse(state.recognizing)
        assertEquals(TIMETABLE, state.timetable)
        assertNull(state.failure)
        assertEquals(TIMETABLE, TimetableStore(storage).load())
        assertTrue(state.message!!.contains("保存"))
    }

    @Test fun aRefusedRequestKeepsTheTimetableThatIsAlreadyStored() = runTest {
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(TIMETABLE) }
        val client = FakeVisionClient {
            throw TimetableVisionException(TimetableVisionFailure.RATE_LIMITED, "请求过于频繁，请稍等片刻再试。")
        }
        val controller = controller(client, storage, keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()

        controller.useImage(IMAGE)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(TIMETABLE, state.timetable)
        assertEquals(TimetableVisionFailure.RATE_LIMITED, state.failure)
        assertEquals("请求过于频繁，请稍等片刻再试。", state.message)
        assertEquals(TIMETABLE, TimetableStore(storage).load())
        assertTrue(state.canRetry)
    }

    @Test fun aNonTimetableImageIsReportedAndStoresNothing() = runTest {
        val storage = MemoryTimetableStorage()
        val client = FakeVisionClient {
            throw TimetableVisionException(TimetableVisionFailure.NOT_A_TIMETABLE, "这张图片里没有识别到课表，请选择一张课程表照片。")
        }
        val controller = controller(client, storage, keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()

        controller.useImage(IMAGE)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(TimetableVisionFailure.NOT_A_TIMETABLE, state.failure)
        assertTrue(state.message!!.contains("没有识别到课表"))
        assertNull(state.timetable)
        assertNull(storage.value)
    }

    @Test fun everyFailureKindSurvivesAsItsOwnState() = runTest {
        for (failure in TimetableVisionFailure.entries) {
            val client = FakeVisionClient { throw TimetableVisionException(failure, "fixture-$failure") }
            val controller = controller(client, keyStorage = keyVault("sk-fixture-abcdef"))
            controller.restore()
            advanceUntilIdle()
            controller.useImage(IMAGE)
            advanceUntilIdle()
            assertEquals(failure, controller.state.value.failure)
            assertFalse(controller.state.value.recognizing)
        }
    }

    @Test fun aMissingKeyIsReportedWithoutCallingTheService() = runTest {
        val client = FakeVisionClient { TIMETABLE }
        val controller = controller(client)
        controller.restore()
        advanceUntilIdle()

        controller.useImage(IMAGE)
        advanceUntilIdle()

        assertEquals(0, client.calls)
        assertEquals(TimetableVisionFailure.MISSING_KEY, controller.state.value.failure)
        assertNull(controller.state.value.timetable)
    }

    @Test fun retryingWithoutAnImageAsksForOne() = runTest {
        val controller = controller(keyStorage = keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()
        controller.retry()
        advanceUntilIdle()
        assertTrue(controller.state.value.message!!.contains("请先选择"))
    }

    @Test fun retryingReusesTheImageThatWasPicked() = runTest {
        val client = FakeVisionClient { TIMETABLE }
        val controller = controller(client, keyStorage = keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        controller.retry()
        advanceUntilIdle()
        assertEquals(2, client.calls)
    }

    @Test fun aRecognitionThatCannotBeWrittenStillReachesTheScreen() = runTest {
        val storage = MemoryTimetableStorage(failWrites = true)
        val controller = controller(FakeVisionClient { TIMETABLE }, storage, keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        val state = controller.state.value
        assertEquals(TIMETABLE, state.timetable)
        assertTrue(state.message!!.contains("未能保存"))
    }

    @Test fun deletingRemovesTheTimetableFromTheDevice() = runTest {
        val storage = MemoryTimetableStorage()
        val controller = controller(storage = storage, keyStorage = keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        assertEquals(TIMETABLE, controller.state.value.timetable)
        controller.deleteTimetable()
        advanceUntilIdle()
        assertNull(controller.state.value.timetable)
        assertNull(storage.value)
    }

    @Test fun theKeyCanBeSavedReplacedAndDeleted() = runTest {
        val keyStorage = MemoryCiphertext()
        val controller = controller(keyStorage = keyStorage)
        controller.restore()
        advanceUntilIdle()

        controller.saveApiKey("   ")
        advanceUntilIdle()
        assertTrue(controller.state.value.message!!.contains("请输入"))
        assertFalse(controller.state.value.keyConfigured)

        controller.saveApiKey("  sk-fixture-abcdef  ")
        advanceUntilIdle()
        assertTrue(controller.state.value.keyConfigured)
        assertEquals("••••cdef", controller.state.value.keyHint)
        assertEquals("sk-fixture-abcdef", VisionApiKeyStore(keyStorage, CredentialKeyAccess { vaultKey }).load())

        controller.clearApiKey()
        advanceUntilIdle()
        assertFalse(controller.state.value.keyConfigured)
        assertEquals("", controller.state.value.keyHint)
        assertNull(VisionApiKeyStore(keyStorage, CredentialKeyAccess { vaultKey }).load())
    }

    @Test fun aStaleDismissalDoesNotClearANewerMessage() = runTest {
        val controller = controller()
        controller.restore()
        advanceUntilIdle()
        controller.retry()
        advanceUntilIdle()
        val current = controller.state.value.messageId
        controller.clearMessage(current - 1)
        assertTrue(controller.state.value.message != null)
        controller.clearMessage(current)
        assertNull(controller.state.value.message)
    }

    // ---- the endpoint compiled into the build -------------------------------------------------

    @Test fun aBuiltInKeyRecognisesWithNoSetupAtAll() = runTest {
        val keyStorage = MemoryCiphertext()
        val client = FakeVisionClient { TIMETABLE }
        val controller = controller(client, keyStorage = keyStorage, builtInKey = BUILT_IN_KEY)
        controller.restore()
        advanceUntilIdle()

        val restored = controller.state.value
        assertTrue(restored.builtInKey)
        // Nothing was ever entered, so there is no key screen to show and no key to store.
        assertFalse(restored.keyConfigured)
        assertEquals("", restored.keyHint)

        controller.useImage(IMAGE)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(1, client.calls)
        assertEquals(BUILT_IN_KEY, client.lastKey)
        assertEquals(TIMETABLE, state.timetable)
        assertNull(state.failure)
        // The build's key is never written into the encrypted vault.
        assertNull(VisionApiKeyStore(keyStorage, CredentialKeyAccess { vaultKey }).load())
    }

    @Test fun aBuiltInKeyStillGoesThroughTheVaultWhenTheBuildHasNone() = runTest {
        val client = FakeVisionClient { TIMETABLE }
        val controller = controller(client, keyStorage = keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        // The fallback path is unchanged: the stored key is the one that travels.
        assertEquals("sk-fixture-abcdef", client.lastKey)
        assertEquals(TIMETABLE, controller.state.value.timetable)
    }

    // ---- week navigation ----------------------------------------------------------------------

    @Test fun aFreshTimetableOpensOnTheCurrentWeekOnceTheTermStartIsSet() = runTest {
        val storage = MemoryTimetableStorage().also {
            it.termStart = TERM_START_EPOCH_DAY
            TimetableStore(it).save(TIMETABLE)
        }
        val controller = controller(storage = storage)
        controller.restore()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(TERM_START_EPOCH_DAY, state.termStartEpochDay)
        assertEquals(16, state.weekCount)
        assertEquals(3, state.currentWeek)
        assertEquals(3, state.selectedWeek)
        assertEquals(2, state.todayWeekday)
    }

    @Test fun withoutATermStartTheCurrentWeekIsOneRatherThanAGuess() = runTest {
        val controller = controller(keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        val state = controller.state.value
        assertEquals(TIMETABLE, state.timetable)
        assertNull(state.termStartEpochDay)
        assertEquals(1, state.currentWeek)
        assertEquals(1, state.selectedWeek)
    }

    @Test fun settingTheTermStartJumpsToTheCurrentWeek() = runTest {
        val storage = MemoryTimetableStorage()
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        assertEquals(TIMETABLE, controller.state.value.timetable)
        controller.selectWeek(1)

        controller.setTermStart(TERM_START_EPOCH_DAY)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(TERM_START_EPOCH_DAY, state.termStartEpochDay)
        assertEquals(3, state.currentWeek)
        assertEquals(3, state.selectedWeek)
        assertEquals(TERM_START_EPOCH_DAY, storage.termStart)
    }

    @Test fun theSelectedWeekIsClampedToTheTerm() = runTest {
        val controller = controller(keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        assertEquals(16, controller.state.value.weekCount)

        controller.selectWeek(99)
        assertEquals(16, controller.state.value.selectedWeek)
        controller.selectWeek(0)
        assertEquals(1, controller.state.value.selectedWeek)
        controller.selectWeek(-5)
        assertEquals(1, controller.state.value.selectedWeek)
    }

    @Test fun clearingTheTermStartForgetsTheCurrentWeek() = runTest {
        val storage = MemoryTimetableStorage().also {
            it.termStart = TERM_START_EPOCH_DAY
            TimetableStore(it).save(TIMETABLE)
        }
        val controller = controller(storage = storage)
        controller.restore()
        advanceUntilIdle()
        assertEquals(3, controller.state.value.currentWeek)

        controller.clearTermStart()
        advanceUntilIdle()

        val state = controller.state.value
        assertNull(state.termStartEpochDay)
        assertEquals(1, state.currentWeek)
        assertNull(storage.termStart)
    }

    @Test fun deletingTheTimetableKeepsTheTermStart() = runTest {
        val storage = MemoryTimetableStorage().also { it.termStart = TERM_START_EPOCH_DAY }
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        assertEquals(TIMETABLE, controller.state.value.timetable)
        assertTrue(controller.state.value.canRetry)

        controller.deleteTimetable()
        advanceUntilIdle()

        assertNull(controller.state.value.timetable)
        assertEquals(TERM_START_EPOCH_DAY, controller.state.value.termStartEpochDay)
    }

    @Test fun aTimetableRestoredFromDiskStillOffersRetryAfterAnotherImage() = runTest {
        val storage = MemoryTimetableStorage()
        val controller = controller(storage = storage, keyStorage = keyVault("sk-fixture-abcdef"))
        controller.restore()
        advanceUntilIdle()
        assertFalse(controller.state.value.canRetry)
        controller.useImage(IMAGE)
        advanceUntilIdle()
        assertTrue(controller.state.value.canRetry)
    }

    /** Never answers, so the controller stays in its busy state until something releases it. */
    private class HangingVisionClient : TimetableVisionClient {
        var calls = 0
        override suspend fun recognize(image: TimetableImage, apiKey: String): Timetable {
            calls++
            awaitCancellation()
        }
    }

    @Test fun cancellingRecognitionFreesTheScreenAndKeepsThePhoto() = runTest {
        val hanging = HangingVisionClient()
        val controller = controller(client = hanging, builtInKey = BUILT_IN_KEY)
        controller.useImage(IMAGE)
        assertTrue("the screen is busy while the request is in flight", controller.state.value.recognizing)

        controller.cancelRecognition()

        val cancelled = controller.state.value
        assertFalse("the page must never be left disabled", cancelled.recognizing)
        assertTrue("the user is told what happened", cancelled.message!!.contains("取消"))
        assertNull("a cancellation is not a failure of the image", cancelled.failure)
        assertTrue("换一张 / 重新识别 has to stay available", cancelled.canRetry)

        // The photo is still held, so 重新识别 goes straight back out without a new selection.
        controller.retry()
        assertTrue(controller.state.value.recognizing)
        assertEquals(2, hanging.calls)
    }

    @Test fun aSecondUploadIsRefusedWhileOneIsAlreadyInFlight() = runTest {
        val hanging = HangingVisionClient()
        val controller = controller(client = hanging, builtInKey = BUILT_IN_KEY)
        controller.useImage(IMAGE)
        controller.useImage(IMAGE)

        // Two answers racing for the same state is the failure this guard exists to prevent.
        assertEquals(1, hanging.calls)

        controller.cancelRecognition()
        controller.useImage(IMAGE)
        assertEquals("cancelling must release the slot, not keep it", 2, hanging.calls)
    }

    @Test fun aRecognitionThatOutlivesTheWatchdogReleasesTheScreen() = runTest {
        val hanging = HangingVisionClient()
        val controller = controller(client = hanging, builtInKey = BUILT_IN_KEY, timeoutMillis = 5_000)
        controller.useImage(IMAGE)
        assertTrue(controller.state.value.recognizing)

        advanceTimeBy(5_001)

        val timedOut = controller.state.value
        assertFalse(timedOut.recognizing)
        assertTrue("the user is told why it stopped", timedOut.message!!.contains("过长"))
        assertEquals(1, hanging.calls)
    }

    @Test fun cancellingWithNothingInFlightChangesNothing() = runTest {
        val controller = controller()
        controller.restore()
        advanceUntilIdle()
        val before = controller.state.value

        controller.cancelRecognition()

        assertEquals(before, controller.state.value)
    }

    private companion object {
        val IMAGE = TimetableImage(ByteArray(32) { 1 }, "image/jpeg")
        const val BUILT_IN_KEY = "sk-built-in-fixture"
        const val FIXTURE_KEY = "sk-fixture-abcdef"
        // 2026-10-06 is a Tuesday, two weeks after the term started on Monday 2026-09-21.
        val TODAY: LocalDate = LocalDate.of(2026, 10, 6)
        val TERM_START: LocalDate = LocalDate.of(2026, 9, 21)
        val TERM_START_EPOCH_DAY: Long = TERM_START.toEpochDay()
        val TIMETABLE = Timetable(
            term = "2025-2026学年第一学期",
            courses = listOf(TimetableCourse("高等数学-fixture", "教师甲", "文理楼201", 1, 1, 2, 1, 16)),
            recognizedAtMillis = 1_700_000_000_000L
        )
    }
}
