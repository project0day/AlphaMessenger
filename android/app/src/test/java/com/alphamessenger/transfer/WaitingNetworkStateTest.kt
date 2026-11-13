package com.alphamessenger.transfer

import com.alphamessenger.media.TransferStage
import com.alphamessenger.media.MediaKind
import com.alphamessenger.media.MediaState
import com.alphamessenger.media.toOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ── КОНТРАКТ СОСТОЯНИЙ ПЕРЕДАЧИ: ожидание сети ───────────────────────────────
 *
 * Три РАЗНЫХ исхода, а не два. Дефект, ради которого писан этот файл, выглядел
 * так: при выключенном Wi-Fi отправка большого файла показывала ↻, нажатие на
 * него возвращало тот же значок через полсекунды, а ✕ не было вовсе — отменить
 * зависшую отправку было нечем. Ядро при этом за одну минуту доложило о неудаче
 * двадцать три раза, хотя никакой неудачи не происходило: ждали транспорт.
 *
 * Проверяется ЖИВАЯ цепочка, ту же самую, что рисует пузырь:
 *
 *   ядро (on_delivery_update "waiting_network")
 *     → TransferEngine.onUploadWaitingNetwork
 *     → TransferState.WaitingNetwork
 *     → MediaState.stage
 *     → toOverlayState
 *     → FileUiState
 *
 * Второй модели состояния здесь нет и быть не должно — см. записи на местах
 * удалённых `TransferGlyph` и `MediaControl`.
 */
class WaitingNetworkStateTest {

    // ── тестовые заглушки: движок не должен трогать диск и сеть ──────────────

    private class FakeRepo : TransferRepository {
        override fun cacheEntry(ref: String) = CacheEntry.ABSENT
        override fun mediaAvailability(ref: String, senderLocalPath: String?) = FileSource.Missing
        override suspend fun downloadTracked(ref: String, opId: String): String? = null
        override fun deleteCache(ref: String, deleteLocalCopy: Boolean): Long = 0L
        override fun markCacheEvicted(ref: String) = Unit
        override fun unmarkCacheEvicted(ref: String) = Unit
        override fun isCacheEvicted(ref: String) = false
        override val progress: SharedFlow<Triple<String, Long, Long>> = MutableSharedFlow()
    }

    private fun engine() = TransferEngine(
        scope = CoroutineScope(Dispatchers.Unconfined),
        repo = FakeRepo(),
        policy = { _, _, _ -> false },
    )

    private val msg = "msg-1"
    private val key = TransferKey.upload(msg)

    /** Довести отправку до подтверждённого рубежа в 40 из 100 байт. */
    private fun TransferEngine.uploadedTo(bytes: Long, total: Long = 100L) {
        onUploadEncrypting(msg)
        onUploadBytes(msg, bytes, total)
    }

    private fun overlayOf(stage: TransferStage, bytes: Long = 0L, total: Long = 0L): FileUiState =
        MediaState(
            kind = MediaKind.FILE, stage = stage, progress = null,
            bytes = bytes, total = total, localPath = null, playable = false,
            streamable = false, poster = null, durationMs = 0, sizeBytes = 0L,
            albumItems = null, albumDone = 0, albumTotal = 0,
        ).toOverlayState(isOutgoing = true)

    // ── 1. QUEUED — это не ожидание сети ────────────────────────────────────

    @Test
    fun QUEUED_IS_NOT_WAITING_NETWORK() {
        val e = engine()
        e.onUploadQueued(msg)
        val s = e.stateOf(key)
        assertTrue("очередь остаётся очередью", s is TransferState.Queued)
        assertFalse("очередь не должна выдавать себя за ожидание сети", s is TransferState.WaitingNetwork)
    }

    // ── 2. Ожидание сети — не неудача ───────────────────────────────────────

    @Test
    fun WAITING_NETWORK_NOT_FAILED() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        val snap = e.snapshotOf(key)
        assertTrue(snap.isWaitingNetwork)
        assertFalse("ожидание не является неудачей", snap.isFailed)
        assertFalse("ожидание не терминально — работа продолжится сама", snap.isTerminal)
    }

    // ── 3. Ядро объявляет ожидание — движок его принимает ───────────────────

    @Test
    fun OFFLINE_EMITS_WAITING_NETWORK() {
        val e = engine()
        e.uploadedTo(40L)
        // именно то, что делает мост доставки при статусе "waiting_network"
        e.onUploadWaitingNetwork(msg)
        assertTrue(e.stateOf(key) is TransferState.WaitingNetwork)
    }

    // ── 4. Рубеж докачки переживает потерю связи ────────────────────────────

    @Test
    fun WAITING_NETWORK_PRESERVES_PROGRESS() {
        val e = engine()
        e.uploadedTo(40L, total = 100L)
        e.onUploadWaitingNetwork(msg)
        val p = e.snapshotOf(key).progress
        assertEquals("подтверждённые байты не обнуляются", 40L, p.bytes)
        assertEquals(100L, p.total)
    }

    // ── 5. Показывается ✕ ───────────────────────────────────────────────────

    @Test
    fun WAITING_NETWORK_SHOWS_CANCEL() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        assertTrue("✕ обязан быть на каждом занятом состоянии", e.snapshotOf(key).canCancel)

        // и то же самое на самом конце цепочки, где рисуется значок
        val ui = overlayOf(TransferStage.WaitingNetwork, 40L, 100L)
        assertTrue(ui is FileUiState.WaitingNetwork)
        assertTrue("оверлей занят → центральный значок ✕", ui.isActive)
    }

    // ── 6. ↻ НЕ показывается ────────────────────────────────────────────────

    @Test
    fun WAITING_NETWORK_NO_RETRY() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        assertFalse("повторять нечего — работа продолжится сама", e.snapshotOf(key).canRetry)

        // ↻ в оверлее рисуется РОВНО по одной ветке — Failed. Её быть не должно.
        val ui = overlayOf(TransferStage.WaitingNetwork, 40L, 100L)
        assertFalse("ожидание не должно попадать в ветку ↻", ui is FileUiState.Failed)
    }

    // ── 7. Связь вернулась — докачка, а не начало заново ────────────────────

    @Test
    fun NETWORK_RETURN_RESUMES() {
        val e = engine()
        e.uploadedTo(40L, total = 100L)
        e.onUploadWaitingNetwork(msg)
        // ядро захватило строку и объявило «отправляю»
        e.onUploadResumed(msg)
        val s = e.stateOf(key)
        assertTrue("ожидание переходит в загрузку", s is TransferState.Uploading)
        assertEquals("докачка идёт с рубежа, а не с нуля", 40L, e.snapshotOf(key).progress.bytes)
    }

    // ── 8. Отмена по ожиданию не воскресает ─────────────────────────────────

    @Test
    fun CANCEL_WAITING_NO_RESURRECTION() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        e.dispatch(key, TransferAction.Cancel)
        assertTrue(e.stateOf(key) is TransferState.Cancelled)

        // Доклад об ожидании мог быть уже в пути к моменту нажатия ✕ — он не
        // имеет права оживить отменённое.
        e.onUploadWaitingNetwork(msg)
        assertTrue("отменённое остаётся отменённым", e.stateOf(key) is TransferState.Cancelled)
    }

    // ── 9. Настоящая неудача по-прежнему даёт ↻ ─────────────────────────────

    @Test
    fun FAILED_SHOWS_RETRY() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadFailed(msg, "сервер отказал")
        val snap = e.snapshotOf(key)
        assertTrue(snap.isFailed)
        assertTrue("настоящая ошибка требует ↻", snap.canRetry)

        val ui = overlayOf(TransferStage.Failed)
        assertTrue("↻ рисуется по ветке Failed", ui is FileUiState.Failed)
    }

    // ── 10. Повтор без сети даёт ожидание, а не круг неудач ────────────────

    @Test
    fun RETRY_OFFLINE_WAITING() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadFailed(msg, "обрыв")
        assertTrue(e.snapshotOf(key).isFailed)

        // Пользователь жмёт ↻. Ядро ставит строку обратно в очередь, шлюз связи
        // не пропускает и докладывает ожидание.
        e.onUploadEncrypting(msg)
        e.onUploadWaitingNetwork(msg)
        assertTrue("повтор без сети — ожидание", e.stateOf(key) is TransferState.WaitingNetwork)

        // И пессимистичный ответ отправителя «не получилось» НЕ имеет права
        // затереть точный ответ ядра — иначе возвращается тот самый круг ↻.
        e.onUploadFailed(msg)
        assertTrue(
            "общее «нет» не перебивает точное «ждём транспорт»",
            e.stateOf(key) is TransferState.WaitingNetwork,
        )
    }

    // ── 11. У повтора один владелец ─────────────────────────────────────────

    @Test
    fun RETRY_SINGLE_OWNER() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        // Отправка — дело конвейера отправки, а не нажатия. Повтор загрузки из
        // интерфейса не может завести ВТОРОГО отправителя: движок отказывает
        // любому Start по ключу отправки.
        e.dispatch(key, TransferAction.Start)
        assertTrue(
            "нажатие не заводит второго отправителя",
            e.stateOf(key) is TransferState.WaitingNetwork,
        )
    }

    // ── 12. Публикация ровно одна ───────────────────────────────────────────

    @Test
    fun PUBLISH_ONCE() {
        val e = engine()
        e.uploadedTo(40L)
        e.onUploadWaitingNetwork(msg)
        e.onUploadResumed(msg)
        e.onUploadBytes(msg, 100L, 100L)
        e.onUploadSent(msg)
        assertTrue(e.stateOf(key) is TransferState.Completed)

        // Опоздавший доклад об ожидании (шлюз связи успел объявить его до
        // подтверждения публикации) не откатывает завершённое обратно в работу.
        e.onUploadWaitingNetwork(msg)
        assertTrue("завершённое не возвращается в ожидание", e.stateOf(key) is TransferState.Completed)
    }

    // ── T4: регрессия докачки ───────────────────────────────────────────────

    @Test
    fun T4_RESUME_REGRESSION() {
        val e = engine()
        // 40 % ушло, связь пропала
        e.uploadedTo(40L, total = 100L)
        e.onUploadWaitingNetwork(msg)
        assertEquals(40L, e.snapshotOf(key).progress.bytes)

        // связь вернулась, ядро продолжает С РУБЕЖА (абсолютные числа от ядра)
        e.onUploadResumed(msg)
        e.onUploadBytes(msg, 41L, 100L)
        assertTrue(e.stateOf(key) is TransferState.Uploading)
        assertTrue(
            "докачка не начинается с нуля",
            e.snapshotOf(key).progress.bytes >= 40L,
        )

        e.onUploadBytes(msg, 100L, 100L)
        e.onUploadSent(msg)
        assertTrue(e.stateOf(key) is TransferState.Completed)
        assertNull("у отправки нет локального файла назначения", e.snapshotOf(key).localPath)
    }

    // ── карта состояний оверлея целиком (ничего не потерялось) ─────────────

    @Test
    fun ALBUM_AND_STAGE_MAPPING() {
        assertTrue(overlayOf(TransferStage.Uploading, 1, 2) is FileUiState.Uploading)
        assertTrue(overlayOf(TransferStage.Downloading, 1, 2) is FileUiState.Downloading)
        assertTrue(overlayOf(TransferStage.Paused, 1, 2) is FileUiState.Paused)
        assertTrue(overlayOf(TransferStage.WaitingNetwork, 1, 2) is FileUiState.WaitingNetwork)
        assertTrue(overlayOf(TransferStage.Failed) is FileUiState.Failed)
        assertTrue(overlayOf(TransferStage.NeedDownload) is FileUiState.NeedDownload)
        assertTrue(overlayOf(TransferStage.Ready) is FileUiState.Ready)
        assertTrue(overlayOf(TransferStage.Uploaded) is FileUiState.Ready)
        assertTrue(overlayOf(TransferStage.Idle) is FileUiState.Hidden)
        assertTrue(overlayOf(TransferStage.Canceled) is FileUiState.Hidden)
    }

    // ── восстановление после гибели процесса ───────────────────────────────

    /**
     * Строка очереди пережила процесс — экран обязан это увидеть.
     *
     * Наблюдалось на устройстве: приложение убито посреди отправки, после
     * запуска ядро продолжило докачку с рубежа (`RESUME 192 из 241`, одна
     * публикация), а на пузыре были только часы — ни ✕, ни кольца.
     */
    @Test
    fun SEED_RESTORES_QUEUED_AFTER_RESTART() {
        val e = engine()  // свежий движок = состояние после перезапуска
        assertTrue("движок ничего не помнит", e.stateOf(key) is TransferState.Idle)

        e.seedQueuedIfUnknown(msg)
        val snap = e.snapshotOf(key)
        assertTrue(snap.state is TransferState.Queued)
        assertTrue("✕ обязан появиться сразу", snap.canCancel)
        assertFalse("это не неудача — ↻ не предлагается", snap.canRetry)
    }

    /** Живое состояние сильнее восстановленного: обновление списка не сбрасывает работу. */
    @Test
    fun SEED_NEVER_CLOBBERS_LIVE() {
        val e = engine()
        e.uploadedTo(40L, total = 100L)
        e.seedQueuedIfUnknown(msg)
        assertTrue("идущая загрузка не откатывается в очередь", e.stateOf(key) is TransferState.Uploading)

        e.onUploadWaitingNetwork(msg)
        e.seedQueuedIfUnknown(msg)
        assertTrue("ожидание сети не подменяется очередью", e.stateOf(key) is TransferState.WaitingNetwork)

        e.dispatch(key, TransferAction.Cancel)
        e.seedQueuedIfUnknown(msg)
        assertTrue("отменённое не воскресает из очереди", e.stateOf(key) is TransferState.Cancelled)
    }

    /** Публикация не рисует кольцо байтов: байты уже ушли. */
    @Test
    fun PUBLISHING_NO_BYTE_PROGRESS() {
        val e = engine()
        e.uploadedTo(100L, total = 100L)
        e.onUploadPublishing(msg)
        assertTrue(e.stateOf(key) is TransferState.Publishing)
        assertNull("у публикации нет собственного прогресса", e.stateOf(key).progressOrNull)
        // и на конце цепочки кольца тоже нет
        assertFalse(overlayOf(TransferStage.Uploaded, 100L, 100L).isActive)
    }
}
