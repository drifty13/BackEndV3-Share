package com.lhs.share.hub.service.star

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lhs.share.config.external.ShareProperties
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.account.SubAccountService
import com.lhs.share.hub.service.inventory.InventoryApiException
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MaaYuan 星石截图的短期私有中转。
 *
 * 元数据仅保留在进程内；文件位于未暴露给静态资源的运行时目录，生命周期由 TTL
 * 或浏览器成功 consume 结束。此服务刻意不使用 MediaAsset、Mongo 或 Redis。
 */
@Service
class StarCaptureTransportService(
    private val objectMapper: ObjectMapper,
    private val properties: ShareProperties,
    private val accountService: SubAccountService,
    private val accountEventService: AccountEventService,
) {
    private val captures = ConcurrentHashMap<CaptureKey, StoredCapture>()

    fun upload(userId: String, accountId: String, rawManifest: String, files: List<MultipartFile>): StarCaptureUploadResponse {
        accountService.requireAccount(userId, accountId)
        cleanupExpired()
        val manifest = parseManifest(rawManifest, files)
        val key = CaptureKey(userId, accountId, manifest.captureId)
        val existing = captures[key]
        if (existing != null) {
            if (!sameContent(existing, manifest, files)) conflict("capture_id 已被不同内容使用")
            return existing.toUploadResponse()
        }

        val root = storageRoot()
        val directory = root.resolve("capture-" + UUID.randomUUID()).normalize()
        if (!directory.startsWith(root)) invalid("capture 存储目录无效")
        try {
            Files.createDirectories(directory)
            val storedImages = manifest.images.map { image ->
                val file = files.single { it.originalFilename == image.fileName }
                val destination = directory.resolve(image.fileName).normalize()
                if (!destination.startsWith(directory)) invalid("图片文件名无效")
                file.inputStream.use { input -> Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING) }
                StoredImage(image.sourceImageId, image.sourceOrder, image.fileName, destination)
            }
            val now = Instant.now()
            val stored = StoredCapture(
                key = key,
                manifest = manifest,
                images = storedImages,
                directory = directory,
                createdAt = now,
                expiresAt = now.plus(properties.starCapture.ttlMinutes.coerceAtLeast(1), ChronoUnit.MINUTES),
            )
            captures[key] = stored
            accountEventService.publish(
                userId,
                accountId,
                STAR_CAPTURE_READY_EVENT,
                "star-capture:${manifest.captureId}",
                StarCaptureReadyEvent(
                    eventId = "star-capture:${manifest.captureId}",
                    accountId = accountId,
                    captureId = manifest.captureId,
                    section = manifest.section,
                    imageCount = manifest.images.size,
                    occurredAt = now,
                ),
            )
            return stored.toUploadResponse()
        } catch (exception: InventoryApiException) {
            deleteDirectory(directory)
            throw exception
        } catch (_: Exception) {
            deleteDirectory(directory)
            throw InventoryApiException(HttpStatus.INTERNAL_SERVER_ERROR, "star_capture_store_failed", "无法保存临时星石采集")
        }
    }

    fun pending(userId: String, accountId: String): StarCapturePendingResponse? {
        accountService.requireAccount(userId, accountId)
        cleanupExpired()
        return captures.values
            .asSequence()
            .filter { it.key.userId == userId && it.key.accountId == accountId && !it.consumed }
            .maxByOrNull { it.createdAt }
            ?.toPendingResponse()
    }

    fun manifest(userId: String, accountId: String, captureId: String): StarCaptureManifestResponse =
        requirePending(userId, accountId, captureId).toManifestResponse()

    fun image(userId: String, accountId: String, captureId: String, sourceImageId: String): Resource {
        val capture = requirePending(userId, accountId, captureId)
        val image = capture.images.singleOrNull { it.sourceImageId == sourceImageId }
            ?: missing("星石采集图片不存在")
        if (!Files.isRegularFile(image.path)) missing("星石采集图片不存在")
        return FileSystemResource(image.path)
    }

    fun consume(userId: String, accountId: String, captureId: String): StarCaptureConsumeResponse {
        accountService.requireAccount(userId, accountId)
        cleanupExpired()
        val capture = captures[CaptureKey(userId, accountId, captureId)] ?: missing("星石采集不存在")
        if (!capture.consumed) {
            capture.consumed = true
            deleteDirectory(capture.directory)
        }
        return StarCaptureConsumeResponse(captureId = captureId, consumed = true)
    }

    /** Focused tests call this directly; the scheduler invokes the same path. */
    fun cleanupExpired(now: Instant = Instant.now()) {
        captures.entries.removeIf { (_, capture) ->
            if (!now.isBefore(capture.expiresAt)) {
                deleteDirectory(capture.directory)
                true
            } else {
                false
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    fun scheduledCleanup() = cleanupExpired()

    private fun requirePending(userId: String, accountId: String, captureId: String): StoredCapture {
        accountService.requireAccount(userId, accountId)
        cleanupExpired()
        val capture = captures[CaptureKey(userId, accountId, captureId)] ?: missing("星石采集不存在")
        if (capture.consumed) missing("星石采集已被消费")
        return capture
    }

    private fun parseManifest(rawManifest: String, files: List<MultipartFile>): StarCaptureManifest {
        val node = try {
            objectMapper.readTree(rawManifest) as? ObjectNode ?: invalid("manifest 必须是 JSON 对象")
        } catch (_: Exception) {
            invalid("manifest 不是有效 JSON")
        }
        return if (node.has("sections")) parseFullManifest(node, files) else parseLegacyManifest(node, files)
    }

    /** B4 remains a deliberately separate flat-main compatibility branch. */
    private fun parseLegacyManifest(node: ObjectNode, files: List<MultipartFile>): StarCaptureManifest {
        if (node.fieldNames().asSequence().any { it !in LEGACY_MANIFEST_FIELDS }) invalid("manifest 包含未知字段")
        val schemaVersion = node.path("schema_version").asInt(-1)
        val captureId = node.path("capture_id").asText().trim()
        val gameVersion = node.path("game_version").asText().trim()
        val section = node.path("section").asText().trim()
        val stopReason = node.path("stop_reason").asText().trim()
        if (schemaVersion != 1 || !SAFE_ID.matches(captureId) || gameVersion !in GAMES || section != "main" ||
            stopReason != "bottom_no_move"
        ) {
            invalid("manifest 字段无效")
        }
        val imagesNode = node.path("images")
        if (!imagesNode.isArray || imagesNode.isEmpty) invalid("manifest 缺少 images")
        val images = imagesNode.mapIndexed { index, image ->
            if (!image.isObject || image.fieldNames().asSequence().any { it !in IMAGE_FIELDS }) invalid("manifest 图片字段无效")
            val sourceImageId = image.path("source_image_id").asText().trim()
            val sourceOrder = image.path("source_order").asInt(-1)
            val fileName = image.path("file_name").asText().trim()
            if (!SAFE_ID.matches(sourceImageId) || sourceOrder != index + 1 || !SAFE_PNG.matches(fileName)) invalid("manifest 图片字段无效")
            StarCaptureImage(sourceImageId, sourceOrder, fileName)
        }
        if (images.map(StarCaptureImage::sourceImageId).toSet().size != images.size ||
            images.map(StarCaptureImage::fileName).toSet().size != images.size
        ) {
            invalid("manifest 图片重复")
        }
        val uploaded = files.associateBy { it.originalFilename }
        if (uploaded.size != files.size || uploaded.keys != images.map(StarCaptureImage::fileName).toSet() ||
            files.any { it.isEmpty || it.contentType?.substringBefore(';') != "image/png" || !hasPngSignature(it) }
        ) {
            invalid("PNG 文件与 manifest 不匹配")
        }
        val relationsNode = node.path("adjacent_relations")
        if (!relationsNode.isArray) invalid("manifest 缺少 adjacent_relations")
        val imageIds = images.map(StarCaptureImage::sourceImageId).toSet()
        val orders = images.associate { it.sourceImageId to it.sourceOrder }
        val relations = relationsNode.map { relation ->
            if (!relation.isObject ||
                relation.fieldNames().asSequence().any { it !in RELATION_FIELDS }
            ) {
                invalid("manifest overlap relation 无效")
            }
            val previous = relation.path("previous_source_image_id").asText().trim()
            val current = relation.path("current_source_image_id").asText().trim()
            if (relation.path("relation").asText() != "overlap" || previous !in imageIds || current !in imageIds ||
                orders.getValue(current) != orders.getValue(previous) + 1
            ) {
                invalid("manifest overlap relation 无效")
            }
            StarCaptureRelation(previous, current)
        }
        if (relations.map { it.previousSourceImageId to it.currentSourceImageId }.toSet().size !=
            relations.size
        ) {
            invalid("manifest overlap relation 重复")
        }
        return StarCaptureManifest(schemaVersion, captureId, gameVersion, section, stopReason, images, relations)
    }

    private fun parseFullManifest(node: ObjectNode, files: List<MultipartFile>): StarCaptureManifest {
        if (node.fieldNames().asSequence().any { it !in FULL_MANIFEST_FIELDS }) invalid("manifest 包含未知字段")
        val schemaVersion = node.path("schema_version").asInt(-1)
        val captureId = node.path("capture_id").asText().trim()
        val source = node.path("source").asText().trim()
        val gameVersion = node.path("game_version").asText().trim()
        if (schemaVersion != 1 || !SAFE_ID.matches(captureId) || source != "maayuan" || gameVersion !in GAMES) invalid("manifest 字段无效")
        val sectionsNode = node.path("sections")
        if (!sectionsNode.isObject || sectionsNode.fieldNames().asSequence().toSet() != SECTION_NAMES) invalid("manifest sections 无效")
        val seenIds = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        val sections = linkedMapOf<String, StarCaptureSection>()
        for (sectionName in SECTION_ORDER) {
            val sectionNode = sectionsNode.path(sectionName)
            val section = parseFullSection(sectionName, sectionNode, seenIds, seenNames)
            sections[sectionName] = section
        }
        val images = sections.values.flatMap { it.images }.sortedBy { it.sourceOrder }
        if (images.map { it.sourceOrder } != (1..images.size).toList()) invalid("manifest source_order 必须全局连续")
        val uploaded = files.associateBy { it.originalFilename }
        if (uploaded.size != files.size || uploaded.keys != seenNames ||
            files.any { it.isEmpty || it.contentType?.substringBefore(';') != "image/png" || !hasPngSignature(it) }
        ) {
            invalid("PNG 文件与 manifest 不匹配")
        }
        return StarCaptureManifest(
            schemaVersion = schemaVersion,
            captureId = captureId,
            gameVersion = gameVersion,
            section = "full",
            stopReason = "full_capture",
            images = images,
            adjacentRelations = emptyList(),
            source = source,
            sections = sections,
        )
    }

    private fun parseFullSection(
        sectionName: String,
        node: com.fasterxml.jackson.databind.JsonNode,
        seenIds: MutableSet<String>,
        seenNames: MutableSet<String>,
    ): StarCaptureSection {
        if (!node.isObject || node.fieldNames().asSequence().any { it !in SECTION_FIELDS }) invalid("manifest section 字段无效")
        val complete = node.path("complete").asBoolean(false)
        val stopReason = node.path("stop_reason").asText().trim()
        val expectedStopReason = if (sectionName == "experience") "single_capture" else "bottom_no_move"
        if (!complete || stopReason != expectedStopReason) invalid("manifest section 未完整采集")
        val imagesNode = node.path("images")
        val relationsNode = node.path("adjacent_relations")
        if (!imagesNode.isArray || !relationsNode.isArray ||
            (sectionName != "experience" && imagesNode.isEmpty)
        ) {
            invalid("manifest section 图片无效")
        }
        if (sectionName == "experience" && (imagesNode.size() != 1 || !relationsNode.isEmpty)) invalid("experience section 无效")
        val images = imagesNode.map { image ->
            if (!image.isObject || image.fieldNames().asSequence().any { it !in IMAGE_FIELDS }) invalid("manifest 图片字段无效")
            val sourceImageId = image.path("source_image_id").asText().trim()
            val sourceOrder = image.path("source_order").asInt(-1)
            val fileName = image.path("file_name").asText().trim()
            if (!SAFE_ID.matches(sourceImageId) || sourceOrder < 1 || !SAFE_PNG.matches(fileName) || !seenIds.add(sourceImageId) ||
                !seenNames.add(fileName)
            ) {
                invalid("manifest 图片字段无效")
            }
            StarCaptureImage(sourceImageId, sourceOrder, fileName)
        }
        val orders = images.associate { it.sourceImageId to it.sourceOrder }
        val relations = relationsNode.map { relation ->
            if (!relation.isObject ||
                relation.fieldNames().asSequence().any { it !in RELATION_FIELDS }
            ) {
                invalid("manifest overlap relation 无效")
            }
            val previous = relation.path("previous_source_image_id").asText().trim()
            val current = relation.path("current_source_image_id").asText().trim()
            if (relation.path("relation").asText() != "overlap" || previous !in orders || current !in orders ||
                orders.getValue(current) != orders.getValue(previous) + 1
            ) {
                invalid("manifest overlap relation 无效")
            }
            StarCaptureRelation(previous, current)
        }
        if (relations.map { it.previousSourceImageId to it.currentSourceImageId }.toSet().size !=
            relations.size
        ) {
            invalid("manifest overlap relation 重复")
        }
        return StarCaptureSection(images, relations, complete, stopReason)
    }

    private fun sameContent(existing: StoredCapture, manifest: StarCaptureManifest, files: List<MultipartFile>): Boolean {
        if (existing.manifest != manifest) return false
        return existing.images.all { stored ->
            val retry = files.singleOrNull { it.originalFilename == stored.fileName } ?: return false
            retry.inputStream.use { input -> Files.readAllBytes(stored.path).contentEquals(input.readAllBytes()) }
        }
    }

    private fun storageRoot(): Path {
        val root = Path.of(properties.starCapture.dir).toAbsolutePath().normalize()
        try {
            Files.createDirectories(root)
        } catch (_: Exception) {
            throw InventoryApiException(HttpStatus.INTERNAL_SERVER_ERROR, "star_capture_store_failed", "无法创建临时星石目录")
        }
        return root
    }

    private fun hasPngSignature(file: MultipartFile): Boolean = file.inputStream.use { input ->
        input.readNBytes(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
    }

    private fun deleteDirectory(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) } }
    }

    private fun invalid(message: String): Nothing =
        throw InventoryApiException(HttpStatus.UNPROCESSABLE_ENTITY, "star_capture_invalid", message)
    private fun conflict(message: String): Nothing = throw InventoryApiException(HttpStatus.CONFLICT, "star_capture_conflict", message)
    private fun missing(message: String): Nothing = throw InventoryApiException(HttpStatus.NOT_FOUND, "star_capture_not_found", message)

    private data class CaptureKey(val userId: String, val accountId: String, val captureId: String)
    private data class StoredImage(val sourceImageId: String, val sourceOrder: Int, val fileName: String, val path: Path)
    private data class StoredCapture(
        val key: CaptureKey,
        val manifest: StarCaptureManifest,
        val images: List<StoredImage>,
        val directory: Path,
        val createdAt: Instant,
        val expiresAt: Instant,
        var consumed: Boolean = false,
    ) {
        fun toUploadResponse() = StarCaptureUploadResponse(manifest.captureId, manifest.section, manifest.images.size, createdAt)
        fun toPendingResponse() =
            StarCapturePendingResponse(manifest.captureId, manifest.section, manifest.images.size, createdAt, expiresAt)
        fun toManifestResponse() = StarCaptureManifestResponse(
            captureId = manifest.captureId,
            gameVersion = manifest.gameVersion,
            section = manifest.section,
            stopReason = manifest.stopReason,
            images = manifest.images,
            adjacentRelations = manifest.adjacentRelations,
            source = manifest.source,
            sections = manifest.sections,
        )
    }

    companion object {
        const val STAR_CAPTURE_READY_EVENT = "star_capture_ready"
        private val SAFE_ID = Regex("[A-Za-z0-9:_-]{1,160}")
        private val SAFE_PNG = Regex("[A-Za-z0-9._-]{1,180}\\.png")
        private val GAMES = setOf("如鸢", "代号鸢")
        private val LEGACY_MANIFEST_FIELDS =
            setOf("schema_version", "capture_id", "game_version", "section", "stop_reason", "images", "adjacent_relations")
        private val FULL_MANIFEST_FIELDS = setOf("schema_version", "capture_id", "source", "game_version", "sections")
        private val SECTION_NAMES = setOf("main", "support", "experience")
        private val SECTION_ORDER = listOf("main", "support", "experience")
        private val SECTION_FIELDS = setOf("images", "adjacent_relations", "complete", "stop_reason")
        private val IMAGE_FIELDS = setOf("source_image_id", "source_order", "file_name")
        private val RELATION_FIELDS = setOf("previous_source_image_id", "current_source_image_id", "relation")
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

data class StarCaptureManifest(
    val schemaVersion: Int,
    val captureId: String,
    val gameVersion: String,
    val section: String,
    val stopReason: String,
    val images: List<StarCaptureImage>,
    val adjacentRelations: List<StarCaptureRelation>,
    val source: String? = null,
    val sections: Map<String, StarCaptureSection>? = null,
)

data class StarCaptureImage(val sourceImageId: String, val sourceOrder: Int, val fileName: String)
data class StarCaptureRelation(
    val previousSourceImageId: String,
    val currentSourceImageId: String,
    val relation: String = "overlap",
)
data class StarCaptureSection(
    val images: List<StarCaptureImage>,
    val adjacentRelations: List<StarCaptureRelation>,
    val complete: Boolean,
    val stopReason: String,
)
data class StarCaptureUploadResponse(val captureId: String, val section: String, val imageCount: Int, val createdAt: Instant)
data class StarCapturePendingResponse(
    val captureId: String,
    val section: String,
    val imageCount: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
)
data class StarCaptureManifestResponse(
    val captureId: String,
    val gameVersion: String,
    val section: String,
    val stopReason: String,
    val images: List<StarCaptureImage>,
    val adjacentRelations: List<StarCaptureRelation>,
    val source: String? = null,
    val sections: Map<String, StarCaptureSection>? = null,
)
data class StarCaptureConsumeResponse(val captureId: String, val consumed: Boolean)
data class StarCaptureReadyEvent(
    val eventId: String,
    val accountId: String,
    val captureId: String,
    val section: String,
    val imageCount: Int,
    val occurredAt: Instant,
)
