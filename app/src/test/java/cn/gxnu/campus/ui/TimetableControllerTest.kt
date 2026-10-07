package cn.gxnu.campus.ui

import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.core.WeekParity
import cn.gxnu.campus.core.WordDocuments
import cn.gxnu.campus.data.CiphertextStorage
import cn.gxnu.campus.data.CredentialKeyAccess
import cn.gxnu.campus.data.TimetableStorage
import cn.gxnu.campus.data.TimetableStore
import cn.gxnu.campus.data.VisionApiKeyStore
import cn.gxnu.campus.network.TimetableImage
import cn.gxnu.campus.network.TimetableVisionClient
import cn.gxnu.campus.network.TimetableVisionException
import cn.gxnu.campus.network.TimetableVisionFailure
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
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
        timeoutMillis: Long = TimetableController.DEFAULT_RECOGNITION_TIMEOUT_MILLIS,
        // An own unconfined scope, because runTest's backgroundScope work is not run by
        // advanceUntilIdle and it would stall the controller's own coroutines.
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        now: () -> Long = { System.currentTimeMillis() },
        // A standard dispatcher instead of the unconfined one, so a test can look at the state of an
        // import while it is still in flight rather than only after it has already finished.
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)
    ) = TimetableController(
        client = client,
        store = TimetableStore(storage),
        keys = VisionApiKeyStore(keyStorage, CredentialKeyAccess { vaultKey }),
        scope = scope,
        dispatcher = dispatcher,
        builtInKey = builtInKey,
        today = { today },
        now = now,
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

    // ---- correcting a recognition by hand ------------------------------------------------------

    @Test fun aCourseAddedByHandIsSavedAndKeepsWhatTheUserWasLookingAt() = runTest {
        val storage = MemoryTimetableStorage().also {
            it.termStart = TERM_START_EPOCH_DAY
            TimetableStore(it).save(TIMETABLE)
        }
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        assertEquals(3, controller.state.value.currentWeek)
        // The user browses back to week 1; an edit must not drag them forward to the current week.
        controller.selectWeek(1)

        controller.saveCourse(null, DRAFT_ADDED)
        advanceUntilIdle()

        val state = controller.state.value
        val courses = state.timetable!!.courses
        assertEquals(2, courses.size)
        assertEquals("体育-fixture", courses.last().name)
        assertEquals("体育馆", courses.last().room)
        assertEquals(4, courses.last().weekday)
        assertEquals(3, courses.last().startPeriod)
        assertTrue(state.message!!.contains("保存"))
        assertTrue("换一张 / 重新识别 has to survive an edit", state.canRetry)
        assertEquals("the browsed week must not be reset", 1, state.selectedWeek)
        assertEquals(TERM_START_EPOCH_DAY, state.termStartEpochDay)
        assertEquals(2, TimetableStore(storage).load()!!.courses.size)
    }

    @Test fun replacingTheNthCourseSwapsOnlyThatOne() = runTest {
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(TIMETABLE) }
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.saveCourse(null, DRAFT_ADDED)
        advanceUntilIdle()

        controller.saveCourse(
            0,
            TimetableCourseDraft(
                name = "大学物理-fixture", room = "文理楼305", weekday = "周五",
                startPeriod = "5", endPeriod = "6", startWeek = "1", endWeek = "16"
            )
        )
        advanceUntilIdle()

        val courses = controller.state.value.timetable!!.courses
        assertEquals(2, courses.size)
        assertEquals("大学物理-fixture", courses[0].name)
        assertEquals("文理楼305", courses[0].room)
        assertEquals(5, courses[0].weekday)
        assertEquals(6, courses[0].endPeriod)
        assertTrue("the replaced course is gone", courses.none { it.name == "高等数学-fixture" })
        assertEquals("the other course is untouched", "体育-fixture", courses[1].name)
        assertEquals(2, TimetableStore(storage).load()!!.courses.size)
    }

    @Test fun deletingTheNthCourseRemovesOnlyThatOne() = runTest {
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(TIMETABLE) }
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.saveCourse(null, DRAFT_ADDED)
        advanceUntilIdle()

        controller.removeCourse(0)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("体育-fixture"), state.timetable!!.courses.map { it.name })
        assertTrue(state.message!!.contains("保存"))
        assertEquals(listOf("体育-fixture"), TimetableStore(storage).load()!!.courses.map { it.name })
    }

    @Test fun anIndexThatIsOutOfRangeIsReportedAndChangesNothing() = runTest {
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(TIMETABLE) }
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()

        controller.saveCourse(7, DRAFT_ADDED)
        advanceUntilIdle()
        assertEquals(TIMETABLE, controller.state.value.timetable)
        assertTrue("the user is told why nothing happened", controller.state.value.message != null)

        controller.removeCourse(-1)
        advanceUntilIdle()
        assertEquals(TIMETABLE, controller.state.value.timetable)
        assertTrue(controller.state.value.message != null)

        controller.removeCourse(1)
        advanceUntilIdle()
        assertEquals(TIMETABLE, controller.state.value.timetable)
        assertEquals(1, TimetableStore(storage).load()!!.courses.size)
    }

    @Test fun editingAnEmptyDeviceIsReportedInsteadOfCrashing() = runTest {
        val storage = MemoryTimetableStorage()
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()

        controller.saveCourse(null, DRAFT_ADDED)
        advanceUntilIdle()
        assertNull(controller.state.value.timetable)
        assertTrue(controller.state.value.message != null)

        controller.removeCourse(0)
        advanceUntilIdle()
        assertNull(controller.state.value.timetable)
        assertTrue(controller.state.value.message != null)
        assertNull(storage.value)
    }

    @Test fun aCourseThatClashesInTimeIsRefusedAndNothingIsWritten() = runTest {
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(TIMETABLE) }
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        val before = controller.state.value

        // 高等数学 holds 周一 第1-2节 for weeks 1-16, so this overlaps it in every one of them.
        controller.saveCourse(
            null,
            TimetableCourseDraft(
                name = "英语-fixture", weekday = "周一",
                startPeriod = "2", endPeriod = "3", startWeek = "1", endWeek = "16"
            )
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals("the timetable must be exactly as it was", TIMETABLE, state.timetable)
        assertTrue("the reason has to name the clash", state.message!!.contains("冲突"))
        assertEquals(1, TimetableStore(storage).load()!!.courses.size)
        assertEquals(before.selectedWeek, state.selectedWeek)
        assertEquals(before.termStartEpochDay, state.termStartEpochDay)
    }

    @Test fun aRefusedEditAlsoClearsTheStaleRecognitionCard() = runTest {
        // A failed re-recognition leaves its card on screen: the page shows no notice row while a
        // failure is up, and the card is titled 没识别到课表. An edit refusal has to displace it,
        // otherwise the user sees a message about their typing under a title about recognition.
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(TIMETABLE) }
        val client = FakeVisionClient { throw TimetableVisionException(TimetableVisionFailure.EMPTY_RESPONSE, "fixture") }
        val controller = controller(client = client, storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        assertEquals(TimetableVisionFailure.EMPTY_RESPONSE, controller.state.value.failure)

        controller.saveCourse(
            null,
            TimetableCourseDraft(
                name = "英语-fixture", weekday = "周一",
                startPeriod = "2", endPeriod = "3", startWeek = "1", endWeek = "16"
            )
        )

        val state = controller.state.value
        assertNull("the recognition card has to go with the edit", state.failure)
        assertTrue("the reason has to name the clash", state.message!!.contains("冲突"))
        assertEquals("the timetable itself is untouched", TIMETABLE, state.timetable)
    }

    @Test fun aCourseWithAnUnusableFieldIsRefusedAndNothingIsWritten() = runTest {
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(TIMETABLE) }
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()

        controller.saveCourse(
            null,
            TimetableCourseDraft(name = "没有星期的课", startPeriod = "3", endPeriod = "4", startWeek = "1", endWeek = "16")
        )
        advanceUntilIdle()

        assertEquals(TIMETABLE, controller.state.value.timetable)
        assertTrue("the reason has to name the field", controller.state.value.message!!.contains("星期"))
        assertEquals(1, TimetableStore(storage).load()!!.courses.size)
    }

    @Test fun aParityPairSurvivesAnEditWithoutTurningIntoAConflict() = runTest {
        val storage = MemoryTimetableStorage().also { TimetableStore(it).save(PARITY_TIMETABLE) }
        val controller = controller(storage = storage, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        assertEquals(PARITY_TIMETABLE, controller.state.value.timetable)

        // 单周 高数 and 双周 英语 share 周一 第1-2节. Rebuilding the whole timetable on an edit must
        // carry 单双周 with it, or the pair reads as a clash the moment anything else is added.
        controller.saveCourse(null, DRAFT_ODD_WEEK_ADDED)
        advanceUntilIdle()

        val courses = controller.state.value.timetable!!.courses
        assertEquals(3, courses.size)
        assertEquals(WeekParity.ODD, courses.single { it.name == "高数(单)-fixture" }.parity)
        assertEquals(WeekParity.EVEN, courses.single { it.name == "英语(双)-fixture" }.parity)
        assertEquals("单周 typed into the form has to reach the model", WeekParity.ODD, courses.single { it.name == "体育-fixture" }.parity)
    }

    // ---- importing a Word 课表 ------------------------------------------------------------------

    @Test fun aWordFileIsImportedPersistedAndShown() = runTest {
        // An import must need no key and no vision client at all: it is the entry point for a device
        // that never configured the recognition service.
        val storage = MemoryTimetableStorage().also { it.termStart = TERM_START_EPOCH_DAY }
        val controller = controller(storage = storage, now = { IMPORT_MILLIS })
        controller.restore()
        advanceUntilIdle()
        assertNull(controller.state.value.timetable)

        controller.importWord(WORD_SAMPLE, wordSample(WORD_SAMPLE))
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse("the import flag has to come back down", state.importing)
        assertNull("a Word import is not a recognition failure", state.failure)
        val timetable = requireNotNull(state.timetable) { "the imported timetable never reached the screen" }
        assertEquals("the printed timetable holds 7 courses", 7, timetable.courseCount)
        assertEquals(IMPORT_MILLIS, timetable.recognizedAtMillis)
        // 矩阵理论1班 is printed 周二 with a merge block reaching 16:20-17:00, so its span is 第6-9节.
        val matrix = timetable.courses.single { it.name == "矩阵理论1班" }
        assertEquals("邹艳丽", matrix.teacher)
        assertEquals(2, matrix.weekday)
        assertEquals(6, matrix.startPeriod)
        assertEquals(9, matrix.endPeriod)
        // The 教务系统 leaves the room bracket empty, which is exactly why 上课地点 has to stay editable.
        assertEquals("", matrix.room)
        assertEquals(
            "the notice carries both the course count and what was left out",
            "已从 Word 导入 7 门课程，忽略「无节次」行的 1 门课。",
            state.message
        )
        // The same refresh a recognition gets: week switcher, 当前周 and the term start stay in step.
        assertEquals(TERM_START_EPOCH_DAY, state.termStartEpochDay)
        assertEquals(18, state.weekCount)
        assertEquals(3, state.currentWeek)
        assertEquals("a fresh timetable still opens on the current week", 3, state.selectedWeek)
        assertEquals(2, state.todayWeekday)
        assertFalse("an import must not pretend a photo is waiting for 重新识别", state.canRetry)
        assertEquals(7, TimetableStore(storage).load()!!.courseCount)
    }

    @Test fun aFileThatIsNotAWordDocumentIsReportedAndStoresNothing() = runTest {
        val storage = MemoryTimetableStorage()
        val controller = controller(storage = storage)
        controller.restore()
        advanceUntilIdle()

        controller.importWord("课表.pdf", "%PDF-1.7 fixture".toByteArray())

        val state = controller.state.value
        assertFalse(state.importing)
        assertEquals("这个文件不是 Word 课表，请选择 .doc 或 .docx。", state.message)
        assertNull("a rejected file is not a recognition failure", state.failure)
        assertNull(state.timetable)
        assertNull(storage.value)
    }

    @Test fun aWordFilePastTheSizeLimitIsRefusedWithoutReadingIt() = runTest {
        val controller = controller()
        controller.restore()
        advanceUntilIdle()

        controller.importWord("超大课表.doc", ByteArray(WordDocuments.MAX_BYTES + 1))

        val state = controller.state.value
        assertFalse(state.importing)
        assertTrue("the file itself has to be named", state.message!!.contains("超大课表.doc"))
        assertNull(state.timetable)
    }

    @Test fun aWordFileThatPrintsNoCourseIsRefusedInTheLanguageOfAFile() = runTest {
        val storage = MemoryTimetableStorage()
        val controller = controller(storage = storage)
        controller.restore()
        advanceUntilIdle()

        controller.importWord("空课表.docx", docxOfRows(HEADER_ROW, listOf("第1节", "", "", "")))

        val state = controller.state.value
        assertFalse(state.importing)
        assertTrue("the notice has to say the file holds no course", state.message!!.contains("没有课程"))
        // The validator's own wording is addressed to the photo screen; an import must not borrow it.
        assertFalse("no photo wording may leak into an import", state.message!!.contains("照片"))
        assertNull(state.timetable)
        assertNull(storage.value)
    }

    @Test fun theImportingFlagIsUpWhileTheFileIsReadAndAlwaysComesBackDown() = runTest {
        val controller = controller(dispatcher = StandardTestDispatcher(testScheduler))
        controller.restore()
        advanceUntilIdle()
        assertFalse(controller.state.value.importing)

        controller.importWord(WORD_SAMPLE, wordSample(WORD_SAMPLE))

        assertTrue("the page has to be able to show the import as running", controller.state.value.importing)

        advanceUntilIdle()

        assertFalse("and the flag must never be left up", controller.state.value.importing)
        assertEquals(7, controller.state.value.timetable!!.courseCount)
    }

    @Test fun anImportClearsTheRecognitionCardItDisplaces() = runTest {
        // A failed photo read leaves a card titled 没识别到课表 on screen, and the page shows no notice
        // row while a failure is up — an import has to displace it or its own notice is never seen.
        val client = FakeVisionClient {
            throw TimetableVisionException(TimetableVisionFailure.EMPTY_RESPONSE, "fixture")
        }
        val controller = controller(client = client, keyStorage = keyVault(FIXTURE_KEY))
        controller.restore()
        advanceUntilIdle()
        controller.useImage(IMAGE)
        advanceUntilIdle()
        assertEquals(TimetableVisionFailure.EMPTY_RESPONSE, controller.state.value.failure)

        controller.importWord(WORD_SAMPLE, wordSample(WORD_SAMPLE))
        advanceUntilIdle()

        val state = controller.state.value
        assertNull("the photo card has to go with the import", state.failure)
        assertTrue(state.message!!.contains("已从 Word 导入"))
    }

    @Test fun anImportedTimetableCanBeCorrectedByHand() = runTest {
        // The whole point of the import: what it read can be fixed by hand, 上课地点 above all — the
        // 教务系统's print-out leaves that bracket empty, so the room arrives blank.
        val storage = MemoryTimetableStorage()
        val controller = controller(storage = storage)
        controller.restore()
        advanceUntilIdle()
        controller.importWord(WORD_SAMPLE, wordSample(WORD_SAMPLE))
        advanceUntilIdle()
        val imported = controller.state.value.timetable!!
        val index = imported.courses.indexOfFirst { it.name == "矩阵理论1班" }
        assertTrue("the course to correct has to be in the imported timetable", index >= 0)
        assertEquals("the print-out leaves 上课地点 empty", "", imported.courses[index].room)

        // 编辑这门课: the form that the course detail sheet opens, room filled in at last.
        controller.saveCourse(
            index,
            TimetableCourseDraft(
                name = "矩阵理论1班",
                teacher = "邹艳丽",
                room = "理二楼 401",
                weekday = "周二",
                startPeriod = "6",
                endPeriod = "9",
                startWeek = "2",
                endWeek = "18"
            )
        )
        advanceUntilIdle()

        assertEquals("理二楼 401", controller.state.value.timetable!!.courses[index].room)
        assertEquals(
            "the correction has to survive a reopen from disk",
            "理二楼 401",
            TimetableStore(storage).load()!!.courses.single { it.name == "矩阵理论1班" }.room
        )

        // 添加课程: the path 课表管理 leads to, on top of an imported timetable.
        controller.saveCourse(
            null,
            TimetableCourseDraft(
                name = "自习-fixture",
                room = "图书馆",
                weekday = "周日",
                startPeriod = "1",
                endPeriod = "2",
                startWeek = "1",
                endWeek = "16"
            )
        )
        advanceUntilIdle()
        assertEquals(8, controller.state.value.timetable!!.courseCount)

        // 删除这门课: the editor's own delete, and the timetable on disk follows it down.
        controller.removeCourse(index)
        advanceUntilIdle()

        val corrected = controller.state.value.timetable!!
        assertNull("an edit is not a failure", controller.state.value.failure)
        assertEquals(7, corrected.courseCount)
        assertTrue(corrected.courses.none { it.name == "矩阵理论1班" })
        assertEquals(7, TimetableStore(storage).load()!!.courseCount)
    }

    /** The 教务系统 print-out both readers are written against, straight off the test classpath. */    private fun wordSample(name: String): ByteArray =
        requireNotNull(TimetableControllerTest::class.java.getResourceAsStream("/word/$name")) {
            "test resource /word/$name is missing"
        }.use { it.readBytes() }

    /** A .docx package holding one plain table, written by hand so an empty 课表 can be handed over. */
    private fun docxOfRows(vararg rows: List<String>): ByteArray = zipOf(
        "[Content_Types].xml" to
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
              |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              |<Default Extension="xml" ContentType="application/xml"/>
              |<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              |<Override PartName="/word/document.xml"
              | ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              |</Types>""".trimMargin(),
        "_rels/.rels" to
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
              |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
              | Target="word/document.xml"/>
              |</Relationships>""".trimMargin(),
        "word/document.xml" to
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
              |<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              |<w:body><w:tbl>${rows.joinToString("") { rowOf(it) }}</w:tbl></w:body></w:document>""".trimMargin()
    )

    private fun rowOf(cells: List<String>): String = "<w:tr>" + cells.joinToString("") { cell ->
        "<w:tc><w:p><w:r><w:t xml:space='preserve'>$cell</w:t></w:r></w:p></w:tc>"
    } + "</w:tr>"

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    // ---- an Error must not be able to wedge the screen ------------------------------------------

    @Test fun anOutOfMemoryErrorEndsAsARecognitionFailureRatherThanAStuckScreen() = runTest {
        val client = FakeVisionClient { throw OutOfMemoryError("fixture: the decoded payload blew the heap") }
        val controller = controller(client, builtInKey = BUILT_IN_KEY)

        controller.useImage(IMAGE)
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse("an Error must not leave 识别中 set forever", state.recognizing)
        assertTrue("the user still has to be told it failed", state.message != null)
        assertNull(state.timetable)
        assertTrue("重新识别 has to stay available", state.canRetry)
    }

    @Test fun aRecognitionWhoseJobCannotStartDoesNotLeaveTheScreenBusy() = runTest {
        // A scope that is already cancelled hands back a job that will never run a single line.
        val dead = CoroutineScope(Job().apply { cancel() } + UnconfinedTestDispatcher(testScheduler))
        val controller = controller(client = FakeVisionClient { TIMETABLE }, builtInKey = BUILT_IN_KEY, scope = dead)

        controller.useImage(IMAGE)

        val state = controller.state.value
        assertFalse("a job that never ran must not keep the page disabled", state.recognizing)
        assertTrue("the user is told the request never started", state.message != null)
        assertTrue(state.canRetry)
    }

    private companion object {
        val IMAGE = TimetableImage(ByteArray(32) { 1 }, "image/jpeg")
        /** The real 教务系统 print-out kept in test resources, the same file the readers are held to. */
        const val WORD_SAMPLE = "课表打印.docx"
        const val IMPORT_MILLIS = 1_800_000_000_000L
        /** The row a printed 课表 opens with: the 节次 gutter plus the weekdays it names. */
        val HEADER_ROW = listOf("节次", "星期一", "星期二", "星期三")
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

        /** 单周 and 双周 courses sharing one slot: legal on a real timetable, so rebuilding must keep them. */
        val PARITY_TIMETABLE = Timetable(
            term = "2025-2026学年第一学期",
            courses = listOf(
                TimetableCourse("高数(单)-fixture", "教师甲", "文理楼201", 1, 1, 2, 1, 16, WeekParity.ODD),
                TimetableCourse("英语(双)-fixture", "教师乙", "文理楼202", 1, 1, 2, 1, 16, WeekParity.EVEN)
            ),
            recognizedAtMillis = 1_700_000_000_000L
        )

        /** What the edit form sends for a course added by hand: 周四 第3-4节, no clash with anything above. */
        val DRAFT_ADDED = TimetableCourseDraft(
            name = "体育-fixture",
            teacher = "教师丙",
            room = "体育馆",
            weekday = "周四",
            startPeriod = "3",
            endPeriod = "4",
            startWeek = "1",
            endWeek = "16"
        )

        val DRAFT_ODD_WEEK_ADDED = TimetableCourseDraft(
            name = "体育-fixture",
            teacher = "教师丙",
            room = "体育馆",
            weekday = "周二",
            startPeriod = "3",
            endPeriod = "4",
            startWeek = "1",
            endWeek = "16",
            parity = "单周"
        )
    }
}
