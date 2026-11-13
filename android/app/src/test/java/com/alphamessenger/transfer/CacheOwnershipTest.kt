package com.alphamessenger.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ── ВЛАДЕНИЕ БАЙТАМИ: кэш против источника и OUTBOX ──────────────────────────
 *
 * Три вида байтов и ровно один, который «Удалить из кэша» вправе трогать:
 *
 * * **ИСТОЧНИК** — оригинал пользователя, и OUTBOX набора `filesDir/outbox`.
 *   Не наше либо нужно отправке. Не трогаем.
 * * **КЭШ** — `cacheDir/media`, `cacheDir/stream_chunks`, а также собственная
 *   копия ЗАВЕРШЁННОЙ одиночной отправки `cacheDir/send_*`.
 *
 * Про последнюю здесь и написано главное, потому что я на ней ошибся. У копии
 * ДВЕ фазы: пока отправка идёт — это источник докачки T4 и трогать его нельзя;
 * после завершения та же копия снова одноразовая и удаляться обязана. Я
 * запретил обе фазы и сломал вторую, то есть основной сценарий: «Удалить из
 * кэша» на документе перестало удалять что-либо и отвечало «В кэше нет файла».
 *
 * Решение принимает вызывающий ([deleteLocalCopy]) — для него нужен запрос к
 * очереди, а он не имеет права идти с главного потока.
 */
class CacheOwnershipTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── заглушка диска: повторяет РАЗДЕЛЕНИЕ владения, а не его реализацию ──

    private class FakeRepo(private val root: File) : TransferRepository {
        /** ref → путь кэша (одноразовые байты). */
        val cache = HashMap<String, File>()
        /** ref → путь ИСТОЧНИКА либо OUTBOX (свои байты). */
        val local = HashMap<String, File>()
        val evicted = HashSet<String>()
        /** Что удалила последняя очистка кэша — для проверки, что лишнего нет. */
        val deleted = ArrayList<File>()

        override fun cacheEntry(ref: String) = CacheEntry.ABSENT

        override fun mediaAvailability(ref: String, senderLocalPath: String?): FileSource {
            local[ref]?.takeIf { it.exists() }?.let { return FileSource.Original(it.absolutePath) }
            cache[ref]?.takeIf { it.exists() }?.let { return FileSource.Cache(it.absolutePath) }
            return FileSource.Remote
        }

        override suspend fun downloadTracked(ref: String, opId: String): String? = null

        /**
         * Повторяет правило хранилища: одноразовый кэш — всегда, собственная
         * копия — только внутри кэша И только когда отправка завершена.
         */
        override fun deleteCache(ref: String, deleteLocalCopy: Boolean): Long {
            var freed = 0L
            cache[ref]?.takeIf { it.exists() }?.let {
                freed += it.length(); it.delete(); deleted.add(it)
            }
            if (deleteLocalCopy) {
                // Наши места ДВА — по числу конвейеров отправки: копия документа
                // в кэше и готовый файл набора в `outbox/work`. Разделитель пути
                // системозависим, поэтому сравниваем каталоги, а не подстроку.
                local[ref]?.takeIf { it.exists() && ours(it) }?.let {
                    freed += it.length(); it.delete(); deleted.add(it)
                }
            }
            return freed
        }

        /** Копия сделана нами: кэш документа либо готовый файл набора. */
        private fun ours(f: File): Boolean =
            f.parentFile?.name == "cache" ||
                generateSequence(f.parentFile) { it.parentFile }.any { it.name == "work" }

        override fun markCacheEvicted(ref: String) { evicted.add(ref) }
        override fun unmarkCacheEvicted(ref: String) { evicted.remove(ref) }
        override fun isCacheEvicted(ref: String) = ref in evicted
        override val progress: SharedFlow<Triple<String, Long, Long>> = MutableSharedFlow()
    }

    private lateinit var repo: FakeRepo

    private fun engine(): TransferEngine {
        repo = FakeRepo(tmp.root)
        return TransferEngine(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repo = repo,
            policy = { _, _, _ -> false },
        )
    }

    private fun file(name: String, bytes: Int = 1024): File =
        File(tmp.root, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    /** Пузырь сообщает движку о вложении — иначе владелец не знает про свой файл. */
    private fun TransferEngine.present(ref: String, ownPath: String?, outgoing: Boolean) =
        consider(TransferKey.download(ref), "image", "f.jpg", 1024L, outgoing, ownPath, "op")

    // ── завершённая отправка: копия одноразовая, где бы её ни сделали ───────

    /**
     * Своё фото ведёт себя как документ.
     *
     * Разница между ними была ничем не оправдана: копия документа лежит в
     * `cacheDir/send_*`, копия фото — в `filesDir/outbox/work`, и условие знало
     * только про первое место. Документ удалялся, фото — нет, а надпись на
     * экране обещала одно и то же.
     */
    @Test
    fun OUTGOING_PHOTO_AFTER_SENT_IS_DISPOSABLE() {
        val e = engine()
        val ref = "ref-out"
        val sent = file("outbox/work/s1/0.r1.jpg", bytes = 4096)
        repo.local[ref] = sent

        e.present(ref, sent.absolutePath, outgoing = true)
        val key = TransferKey.download(ref)
        assertTrue("до удаления фото доступно локально", e.snapshotOf(key).isReady)

        val freed = e.deleteCacheOf(listOf(key), deleteLocalCopy = true)

        assertEquals("удалены настоящие байты", 4096L, freed)
        assertFalse("копия завершённой отправки удалена", sent.exists())
        assertTrue("предлагается скачать заново", e.stateOf(key) is TransferState.CacheEvicted)
    }

    /** Набор — то же правило поэлементно; отдельной модели у альбома нет. */
    @Test
    fun OUTGOING_ALBUM_AFTER_SENT_IS_DISPOSABLE() {
        val e = engine()
        val refs = (0..2).map { "ref-album-$it" }
        val files = refs.mapIndexed { i, r ->
            file("outbox/work/s1/$i.r1.jpg", bytes = 2048).also { repo.local[r] = it }
        }
        refs.forEach { e.present(it, repo.local[it]!!.absolutePath, outgoing = true) }

        val freed = e.deleteCacheOf(refs.map { TransferKey.download(it) }, deleteLocalCopy = true)

        assertEquals(3 * 2048L, freed)
        files.forEach { assertFalse("копия участника удалена", it.exists()) }
    }

    /** Пока отправка идёт — источник докачки неприкосновенен. */
    @Test
    fun ACTIVE_ALBUM_SOURCE_NOT_DELETED() {
        val e = engine()
        val ref = "ref-album-active"
        val src = file("outbox/work/s2/0.r1.jpg", bytes = 2048)
        repo.local[ref] = src
        e.present(ref, src.absolutePath, outgoing = true)

        val freed = e.deleteCacheOf(listOf(TransferKey.download(ref)), deleteLocalCopy = false)

        assertEquals(0L, freed)
        assertTrue("источник докачки уцелел", src.exists())
        assertTrue("остаётся доступным", e.snapshotOf(TransferKey.download(ref)).isReady)
    }

    /** Оригинал пользователя не наш — не удаляем даже после отправки. */
    @Test
    fun USER_SOURCE_NEVER_DELETED() {
        val e = engine()
        val ref = "ref-gallery"
        val gallery = file("DCIM/IMG_1.jpg", bytes = 4096)
        repo.local[ref] = gallery
        e.present(ref, gallery.absolutePath, outgoing = true)

        val freed = e.deleteCacheOf(listOf(TransferKey.download(ref)), deleteLocalCopy = true)

        assertEquals(0L, freed)
        assertTrue("оригинал в галерее на месте", gallery.exists())
        assertTrue("и остаётся доступным", e.snapshotOf(TransferKey.download(ref)).isReady)
    }

    /**
     * ЗАВЕРШЁННЫЙ документ снова одноразовый — это и есть поведение, которое
     * работало до правок владения кэшем и которое я сломал, объявив копию в
     * кэше неприкосновенной по месту хранения.
     */
    @Test
    fun DOCUMENT_AFTER_SENT_IS_DISPOSABLE() {
        val e = engine()
        val ref = "ref-doc"
        val copy = file("cache/send_1_report.pdf", bytes = 8192)
        repo.local[ref] = copy
        e.present(ref, copy.absolutePath, outgoing = true)
        val key = TransferKey.download(ref)
        assertTrue("до удаления документ доступен локально", e.snapshotOf(key).isReady)

        // Отправка завершена → решение вызывающего «копию можно».
        val freed = e.deleteCacheOf(listOf(key), deleteLocalCopy = true)

        assertEquals("удалены настоящие байты", 8192L, freed)
        assertFalse("копия завершённой отправки удалена", copy.exists())
        assertTrue("предлагается скачать заново", e.stateOf(key) is TransferState.CacheEvicted)
    }

    /** АКТИВНАЯ отправка — источник докачки T4, трогать запрещено. */
    @Test
    fun ACTIVE_SEND_SOURCE_NOT_DELETED() {
        val e = engine()
        val ref = "ref-doc-active"
        val copy = file("cache/send_2_active.pdf", bytes = 8192)
        repo.local[ref] = copy
        e.present(ref, copy.absolutePath, outgoing = true)
        val key = TransferKey.download(ref)

        // Строка ещё в очереди → вызывающий передаёт «нельзя».
        val freed = e.deleteCacheOf(listOf(key), deleteLocalCopy = false)

        assertEquals(0L, freed)
        assertTrue("источник докачки уцелел", copy.exists())
        assertTrue("документ остаётся доступным", e.snapshotOf(key).isReady)
    }

    // ── входящее: кэш действительно исчезает ────────────────────────────────

    @Test
    fun INCOMING_SINGLE_CLEAR_CACHE_BECOMES_REMOTE() {
        val e = engine()
        val ref = "ref-in"
        val cached = file("media/hash1", bytes = 4096)
        repo.cache[ref] = cached
        e.present(ref, null, outgoing = false)
        val key = TransferKey.download(ref)
        assertTrue(e.snapshotOf(key).isReady)

        val freed = e.deleteCacheOf(listOf(key), deleteLocalCopy = true)

        assertEquals("удалены настоящие байты", 4096L, freed)
        assertFalse(cached.exists())
        assertTrue("предлагается скачать заново", e.stateOf(key) is TransferState.CacheEvicted)
    }

    @Test
    fun INCOMING_ALBUM_CLEAR_CACHE_BECOMES_REMOTE() {
        val e = engine()
        val refs = (0..2).map { "ref-in-al-$it" }
        refs.forEachIndexed { i, r -> repo.cache[r] = file("media/h$i", bytes = 2048) }
        refs.forEach { e.present(it, null, outgoing = false) }

        val freed = e.deleteCacheOf(refs.map { TransferKey.download(it) }, deleteLocalCopy = true)

        assertEquals(3 * 2048L, freed)
        refs.forEach {
            assertTrue(
                "каждый участник предлагает загрузку",
                e.stateOf(TransferKey.download(it)) is TransferState.CacheEvicted,
            )
        }
    }

    // ── правдивый результат ─────────────────────────────────────────────────

    @Test
    fun NO_FAKE_DELETE_SUCCESS() {
        val e = engine()
        val ref = "ref-own"
        repo.local[ref] = file("DCIM/own.jpg")
        e.present(ref, repo.local[ref]!!.absolutePath, outgoing = true)

        // Наличие оригинала НЕ является доказательством удаления.
        assertEquals(
            "успехом считаются только удалённые байты кэша",
            0L, e.deleteCacheOf(listOf(TransferKey.download(ref)), deleteLocalCopy = true),
        )
    }

    // ── очистка не запускает загрузку сама ──────────────────────────────────

    @Test
    fun NO_AUTO_REDOWNLOAD_AFTER_CLEAR() {
        val e = engine()
        val ref = "ref-auto"
        repo.cache[ref] = file("media/h-auto")
        e.present(ref, null, outgoing = false)
        val key = TransferKey.download(ref)

        e.deleteCacheOf(listOf(key), deleteLocalCopy = true)
        assertTrue("метка поставлена", repo.isCacheEvicted(ref))

        // Политика снова смотрит на это вложение (прокрутка, смена сети,
        // переключение настроек) — и обязана промолчать.
        e.present(ref, null, outgoing = false)

        assertTrue(
            "удалённое не возвращается по сети само",
            e.stateOf(key) is TransferState.CacheEvicted,
        )
    }
}
