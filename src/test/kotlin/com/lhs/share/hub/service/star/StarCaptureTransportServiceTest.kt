package com.lhs.share.hub.service.star

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.external.ShareProperties
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Path
import java.time.Instant

class StarCaptureTransportServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val accountService = mockk<SubAccountService>()
    private val eventService = mockk<AccountEventService>(relaxed = true)
    private lateinit var service: StarCaptureTransportService

    @BeforeEach
    fun setUp() {
        every { accountService.requireAccount(any(), any()) } answers {
            SubAccount(userId = firstArg(), accountId = secondArg(), name = "账号")
        }
        val properties = ShareProperties().apply {
            starCapture.dir = temporaryDirectory.resolve("captures").toString()
            starCapture.ttlMinutes = 30
        }
        service = StarCaptureTransportService(jacksonObjectMapper(), properties, accountService, eventService)
    }

    @Test
    fun `upload keeps six PNGs private publishes a small account event and reads them only for the bound account`() {
        val event = slot<Any>()
        val upload = service.upload("u1", "acc1", manifest(), files())

        assertEquals("capture-a", upload.captureId)
        assertEquals(6, upload.imageCount)
        assertEquals("capture-a", service.pending("u1", "acc1")?.captureId)
        val loadedManifest = service.manifest("u1", "acc1", "capture-a")
        assertEquals(6, loadedManifest.images.size)
        assertEquals("overlap", loadedManifest.adjacentRelations.single().relation)
        assertTrue(
            service.image("u1", "acc1", "capture-a", "capture-a:main:000").inputStream.readBytes().decodeToString().endsWith("png-0"),
        )
        assertThrows(InventoryApiException::class.java) { service.manifest("u2", "acc1", "capture-a") }
        verify(exactly = 1) { eventService.publish("u1", "acc1", "star_capture_ready", any(), capture(event)) }
        val payload = event.captured as StarCaptureReadyEvent
        assertEquals("capture-a", payload.captureId)
        assertEquals(6, payload.imageCount)
        assertFalse(payload.toString().contains("png-0"))
    }

    @Test
    fun `same capture id is idempotent only for identical manifest and images`() {
        service.upload("u1", "acc1", manifest(), files())
        assertEquals("capture-a", service.upload("u1", "acc1", manifest(), files()).captureId)

        val error = assertThrows(InventoryApiException::class.java) {
            service.upload("u1", "acc1", manifest(), files(changeFirst = true))
        }
        assertEquals(409, error.status.value())
        verify(exactly = 1) { eventService.publish(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `full CaptureBatchV1 keeps all three sections, global order and account isolation`() {
        val event = slot<Any>()
        val upload = service.upload("u1", "acc1", fullManifest(), fullFiles())

        assertEquals("full", upload.section)
        assertEquals(4, upload.imageCount)
        val loaded = service.manifest("u1", "acc1", "capture-full")
        assertEquals("maayuan", loaded.source)
        assertEquals(listOf("main", "support", "experience"), loaded.sections!!.keys.toList())
        assertEquals(listOf(1, 2, 3, 4), loaded.images.map { it.sourceOrder })
        assertEquals("overlap", loaded.sections.getValue("main").adjacentRelations.single().relation)
        assertTrue(
            service.image(
                "u1",
                "acc1",
                "capture-full",
                "capture-full:support:000",
            ).inputStream.readBytes().decodeToString().endsWith("support"),
        )
        assertThrows(InventoryApiException::class.java) { service.manifest("u1", "acc2", "capture-full") }
        verify(exactly = 1) { eventService.publish("u1", "acc1", "star_capture_ready", any(), capture(event)) }
        val payload = event.captured as StarCaptureReadyEvent
        assertEquals("full", payload.section)
        assertEquals(4, payload.imageCount)
    }

    @Test
    fun `full CaptureBatchV1 rejects malformed experience and duplicate file names`() {
        assertInvalid(fullManifest().replace("\"stop_reason\":\"single_capture\"", "\"stop_reason\":\"bottom_no_move\""), fullFiles())
        assertInvalid(fullManifest().replace("support-000.png", "main-000.png"), fullFiles())
    }

    @Test
    fun `manifest rejects missing files duplicate ids invalid relations and traversal names`() {
        assertInvalid(manifest(), files().dropLast(1))
        assertInvalid(manifest(sourceIds = listOf("duplicate", "duplicate", "id2", "id3", "id4", "id5")), files())
        assertInvalid(manifest(relationPrevious = "unknown"), files())
        assertInvalid(
            manifest(
                fileNames = listOf(
                    "../capture-00.png",
                    "capture-01.png",
                    "capture-02.png",
                    "capture-03.png",
                    "capture-04.png",
                    "capture-05.png",
                ),
            ),
            files(),
        )
        assertInvalid(
            manifest(),
            files().toMutableList().also {
                it[0] =
                    MockMultipartFile("files", "capture-00.png", MediaType.IMAGE_PNG_VALUE, "not-a-png".toByteArray())
            },
        )
    }

    @Test
    fun `consume is idempotent deletes files and prevents later reads`() {
        service.upload("u1", "acc1", manifest(), files())
        assertTrue(service.consume("u1", "acc1", "capture-a").consumed)
        assertTrue(service.consume("u1", "acc1", "capture-a").consumed)
        assertThrows(InventoryApiException::class.java) { service.image("u1", "acc1", "capture-a", "capture-a:main:000") }
    }

    @Test
    fun `expired capture is removed by cleanup`() {
        service.upload("u1", "acc1", manifest(), files())
        service.cleanupExpired(Instant.now().plusSeconds(31 * 60))
        assertEquals(null, service.pending("u1", "acc1"))
    }

    private fun assertInvalid(manifest: String, files: List<MockMultipartFile>) {
        val error = assertThrows(InventoryApiException::class.java) { service.upload("u1", "acc1", manifest, files) }
        assertEquals(422, error.status.value())
    }

    private fun files(changeFirst: Boolean = false): List<MockMultipartFile> = (0..5).map { index ->
        MockMultipartFile(
            "files",
            "capture-${index.toString().padStart(2, '0')}.png",
            MediaType.IMAGE_PNG_VALUE,
            PNG_SIGNATURE + (if (changeFirst && index == 0) "different" else "png-$index").toByteArray(),
        )
    }

    private fun manifest(
        sourceIds: List<String> = (0..5).map { "capture-a:main:${it.toString().padStart(3, '0')}" },
        fileNames: List<String> = (0..5).map { "capture-${it.toString().padStart(2, '0')}.png" },
        relationPrevious: String = "capture-a:main:004",
    ): String = buildString {
        append(
            "{\"schema_version\":1,\"capture_id\":\"capture-a\",\"game_version\":\"如鸢\",\"section\":\"main\",\"stop_reason\":\"bottom_no_move\",\"images\":[",
        )
        sourceIds.forEachIndexed { index, sourceId ->
            if (index > 0) append(',')
            append(
                "{\"source_image_id\":\"",
            ).append(
                sourceId,
            ).append("\",\"source_order\":").append(index + 1).append(",\"file_name\":\"").append(fileNames[index]).append("\"}")
        }
        append(
            "],\"adjacent_relations\":[{\"previous_source_image_id\":\"",
        ).append(relationPrevious).append("\",\"current_source_image_id\":\"capture-a:main:005\",\"relation\":\"overlap\"}]}")
    }

    private fun fullFiles(): List<MockMultipartFile> = listOf(
        fullFile("main-000.png", "main-0"),
        fullFile("main-001.png", "main-1"),
        fullFile("support-000.png", "support"),
        fullFile("experience-000.png", "experience"),
    )

    private fun fullFile(name: String, contents: String) = MockMultipartFile(
        "files",
        name,
        MediaType.IMAGE_PNG_VALUE,
        PNG_SIGNATURE + contents.toByteArray(),
    )

    private fun fullManifest(): String = """
        {"schema_version":1,"capture_id":"capture-full","source":"maayuan","game_version":"如鸢","sections":{
          "main":{"images":[
            {"source_image_id":"capture-full:main:000","source_order":1,"file_name":"main-000.png"},
            {"source_image_id":"capture-full:main:001","source_order":2,"file_name":"main-001.png"}],
            "adjacent_relations":[{"previous_source_image_id":"capture-full:main:000","current_source_image_id":"capture-full:main:001","relation":"overlap"}],"complete":true,"stop_reason":"bottom_no_move"},
          "support":{"images":[{"source_image_id":"capture-full:support:000","source_order":3,"file_name":"support-000.png"}],"adjacent_relations":[],"complete":true,"stop_reason":"bottom_no_move"},
          "experience":{"images":[{"source_image_id":"capture-full:experience:000","source_order":4,"file_name":"experience-000.png"}],"adjacent_relations":[],"complete":true,"stop_reason":"single_capture"}}}
    """.trimIndent()

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
