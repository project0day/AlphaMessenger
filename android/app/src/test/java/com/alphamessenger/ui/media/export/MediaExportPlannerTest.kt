package com.alphamessenger.ui.media.export

import com.alphamessenger.ui.media.editor.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Планировщик — чистая функция, поэтому его поведение проверяется целиком, без
 * устройства и без файлов. Это единственный способ не дать политике разъехаться:
 * правило «HDR приводится к SDR независимо от обрезки» иначе снова превратится в
 * побочный эффект чужого условия, как уже было.
 */
class MediaExportPlannerTest {

    private val sdr = SourceMetadata(
        kind = SourceMetadata.Kind.VIDEO,
        hdr = false,
        videoMime = "video/avc",
        width = 1920, height = 1080, frameRate = 30,
        hasAudio = true, audioBitrate = 128_000,
        fileBytes = 50_000_000, durationMs = 60_000,
    )

    private val dolbyVision = sdr.copy(
        hdr = true,
        videoMime = "video/dolby-vision",
        width = 3840, height = 2160,
        transfer = SourceMetadata.TRANSFER_PQ,
        fileBytes = 350_000_000,
    )

    // ── Ничего не менялось ────────────────────────────────────────────────

    @Test
    fun `sdr без правок переиспользуется`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec())
        assertEquals(ExportRoute.REUSE, plan.route)
        assertNull(plan.profile)
        assertEquals(0, plan.videoBitrate)
    }

    // ── Время и звук: пиксели не трогаем ──────────────────────────────────

    @Test
    fun `обрезка по времени идёт переносом потока`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(trimStartMs = 5_000, trimEndMs = 20_000))
        assertEquals(ExportRoute.REMUX, plan.route)
        assertEquals(5_000L, plan.trimStartMs)
        assertEquals(20_000L, plan.trimEndMs)
        assertTrue(plan.reason.contains("пиксели не изменяются"))
    }

    @Test
    fun `выключение звука идёт переносом потока`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(muted = true))
        assertEquals(ExportRoute.REMUX, plan.route)
        assertTrue(plan.removeAudio)
    }

    @Test
    fun `звук нельзя выключить там где его нет`() {
        val plan = MediaExportPlanner.plan(sdr.copy(hasAudio = false), MediaEditSpec(muted = true))
        assertEquals(ExportRoute.REUSE, plan.route)
    }

    @Test
    fun `дрожание в конце шкалы не считается обрезкой`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(trimEndMs = 59_980))
        assertEquals(ExportRoute.REUSE, plan.route)
    }

    // ── Геометрия: только перекодирование ─────────────────────────────────

    @Test
    fun `обрезка кадра требует перекодирования`() {
        val plan = MediaExportPlanner.plan(
            sdr, MediaEditSpec(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)),
        )
        assertEquals(ExportRoute.TRANSCODE, plan.route)
        assertNotNull(plan.profile)
        assertTrue(plan.videoBitrate > 0)
    }

    @Test
    fun `поворот требует перекодирования`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(rotationDegrees = 90))
        assertEquals(ExportRoute.TRANSCODE, plan.route)
    }

    // ── HDR: решает политика, а не обрезка ────────────────────────────────

    @Test
    fun `dolby vision без правок всё равно приводится к sdr по умолчанию`() {
        val plan = MediaExportPlanner.plan(dolbyVision, MediaEditSpec())
        assertEquals(ExportRoute.TRANSCODE, plan.route)
        assertTrue(plan.toneMapHdrToSdr)
        assertTrue(plan.reason.contains("HDR"))
    }

    @Test
    fun `dolby vision с обрезкой тоже приводится к sdr`() {
        val plan = MediaExportPlanner.plan(dolbyVision, MediaEditSpec(trimStartMs = 3_000))
        assertEquals(ExportRoute.TRANSCODE, plan.route)
        assertTrue(plan.toneMapHdrToSdr)
    }

    @Test
    fun `при политике сохранения hdr без правок остаётся как есть`() {
        val plan = MediaExportPlanner.plan(
            dolbyVision, MediaEditSpec(),
            MediaExportPolicy(hdr = MediaExportPolicy.HdrPolicy.KEEP_HDR_WHEN_SUPPORTED),
        )
        assertEquals(ExportRoute.REUSE, plan.route)
        assertTrue(!plan.toneMapHdrToSdr)
    }

    @Test
    fun `при политике сохранения hdr обрезка времени не включает перекодирование`() {
        val plan = MediaExportPlanner.plan(
            dolbyVision, MediaEditSpec(trimStartMs = 1_000),
            MediaExportPolicy(hdr = MediaExportPolicy.HdrPolicy.KEEP_HDR_WHEN_SUPPORTED),
        )
        assertEquals(ExportRoute.REMUX, plan.route)
    }

    @Test
    fun `политика original не трогает цвет никогда`() {
        val plan = MediaExportPlanner.plan(
            dolbyVision, MediaEditSpec(crop = CropRect(0f, 0f, 0.5f, 0.5f)),
            MediaExportPolicy(hdr = MediaExportPolicy.HdrPolicy.ORIGINAL),
        )
        assertEquals(ExportRoute.TRANSCODE, plan.route)
        assertTrue(!plan.toneMapHdrToSdr)
    }

    // ── Материализация — ось, независимая от маршрута ─────────────────────

    @Test
    fun `переиспользование всё равно требует своей копии`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec())
        assertEquals(ExportRoute.REUSE, plan.route)
        assertEquals(Materialization.COPY_TO_OWNED_STORAGE, plan.materialization)
    }

    @Test
    fun `обработка создаёт файл сама и копировать нечего`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(trimStartMs = 1_000))
        assertEquals(Materialization.PRODUCED_BY_EXPORT, plan.materialization)
    }

    // ── Геометрия видео: представима, но в V1 не выполняется ──────────────

    @Test
    fun `геометрия видео помечается в плане отдельным признаком`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(rotationDegrees = 90))
        assertTrue(plan.videoGeometryRequested)
    }

    @Test
    fun `для фотографии признак геометрии видео не ставится`() {
        val plan = MediaExportPlanner.plan(
            SourceMetadata(kind = SourceMetadata.Kind.IMAGE),
            MediaEditSpec(rotationDegrees = 90),
        )
        assertTrue(!plan.videoGeometryRequested)
    }

    // ── Документы ─────────────────────────────────────────────────────────

    @Test
    fun `отправка файлом не конвертируется даже для hdr`() {
        val plan = MediaExportPlanner.plan(dolbyVision, MediaEditSpec(sendAsFile = true))
        assertEquals(ExportRoute.REUSE, plan.route)
        assertTrue(plan.reason.contains("файлом"))
    }

    // ── Фото ──────────────────────────────────────────────────────────────

    @Test
    fun `фотография всегда пересобирается по профилю`() {
        val plan = MediaExportPlanner.plan(
            SourceMetadata(kind = SourceMetadata.Kind.IMAGE), MediaEditSpec(),
        )
        assertEquals(ExportRoute.TRANSCODE, plan.route)
    }

    // ── Профиль ───────────────────────────────────────────────────────────

    @Test
    fun `битрейт не превышает битрейт исходника`() {
        val plan = MediaExportPlanner.plan(sdr, MediaEditSpec(rotationDegrees = 90))
        assertTrue(plan.videoBitrate <= sdr.videoBitrate)
    }

    @Test
    fun `битрейт исходника неизвестен — берётся качественный по разрешению`() {
        // Файл настолько мал, что за вычетом звука на видео не остаётся ничего:
        // битрейт исходника определить нельзя, и профиль считает его по кадру.
        val tiny = sdr.copy(fileBytes = 100_000)
        val plan = MediaExportPlanner.plan(tiny, MediaEditSpec(rotationDegrees = 90))
        val expected = (1920L * 1080 * 30 * MediaExportProfile.V1_COMPATIBILITY.bitsPerPixel).toInt()
        assertEquals(expected, plan.videoBitrate)
    }

    @Test
    fun `лёгкий исходник не раздувается нижней границей профиля`() {
        // 60 секунд, 5 МБ → около 540 кбит/с видео. Нижняя граница профиля выше,
        // но результат не должен уехать получателю втрое тяжелее исходника.
        val light = sdr.copy(fileBytes = 5_000_000)
        val plan = MediaExportPlanner.plan(light, MediaEditSpec(rotationDegrees = 90))
        assertTrue(
            "битрейт ${plan.videoBitrate} превысил двойной от исходника ${light.videoBitrate}",
            plan.videoBitrate <= light.videoBitrate * 2,
        )
    }

    @Test
    fun `4k не уменьшается профилем совместимости`() {
        assertNull(MediaExportProfile.V1_COMPATIBILITY.scaledSize(dolbyVision))
    }

    @Test
    fun `кадр крупнее потолка уменьшается до чётных сторон`() {
        val huge = sdr.copy(width = 7680, height = 4320)
        val scaled = MediaExportProfile.V1_COMPATIBILITY.scaledSize(huge)
        assertNotNull(scaled)
        assertEquals(3840, scaled!!.first)
        assertEquals(0, scaled.second % 2)
    }

    // ── Отпечаток ─────────────────────────────────────────────────────────

    @Test
    fun `отпечаток меняется вместе с правкой`() {
        val a = MediaEditSpec(trimStartMs = 0).canonical()
        val b = MediaEditSpec(trimStartMs = 1).canonical()
        assertTrue(a != b)
    }

    @Test
    fun `отпечаток меняется вместе с качеством`() {
        val a = MediaEditSpec(photoQuality = 80).canonical()
        val b = MediaEditSpec(photoQuality = 90).canonical()
        assertTrue(a != b)
    }

    // ── Ключ готового файла ───────────────────────────────────────────────

    private fun revision(
        source: String = "file:///src.mp4",
        spec: MediaEditSpec = MediaEditSpec(),
        profileVersion: Int = MediaExportProfile.V1_COMPATIBILITY.version,
    ) = com.alphamessenger.ui.media.send.MediaSendJob.revisionOf(source, spec, profileVersion)

    @Test
    fun `один и тот же источник правки и профиль дают один ключ`() {
        assertEquals(revision(), revision())
    }

    @Test
    fun `смена версии профиля даёт другой ключ`() {
        assertTrue(revision(profileVersion = 1) != revision(profileVersion = 2))
    }

    @Test
    fun `смена правки даёт другой ключ`() {
        assertTrue(revision() != revision(spec = MediaEditSpec(trimStartMs = 1)))
    }

    @Test
    fun `смена источника даёт другой ключ`() {
        assertTrue(revision() != revision(source = "file:///other.mp4"))
    }

    @Test
    fun `ключ неотрицателен — он идёт в имя файла`() {
        assertTrue(revision() >= 0L)
    }
}
