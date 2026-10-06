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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        override fun read(): String? = value
        override fun write(value: String) {
            if (failWrites) throw IllegalStateException("fixture disk full")
            this.value = value
        }
        override fun remove() { value = null }
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
        keyStorage: CiphertextStorage = MemoryCiphertext()
    ) = TimetableController(
        client = client,
        store = TimetableStore(storage),
        keys = VisionApiKeyStore(keyStorage, CredentialKeyAccess { vaultKey }),
        // An own unconfined scope, because runTest's backgroundScope work is not run by
        // advanceUntilIdle and it would stall the controller's own coroutines.
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        dispatcher = UnconfinedTestDispatcher(testScheduler)
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

    private companion object {
        val IMAGE = TimetableImage(ByteArray(32) { 1 }, "image/jpeg")
        val TIMETABLE = Timetable(
            term = "2025-2026学年第一学期",
            courses = listOf(TimetableCourse("高等数学-fixture", "教师甲", "文理楼201", 1, 1, 2, 1, 16)),
            recognizedAtMillis = 1_700_000_000_000L
        )
    }
}
