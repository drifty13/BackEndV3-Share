package com.lhs.share.openapi

import com.lhs.share.config.doc.RequireOpenApiToken
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.service.star.StarCaptureTransportService
import com.lhs.share.hub.service.star.StarCaptureUploadResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@Tag(name = "OpenAPI 星石采集", description = "MaaYuan 星石背包临时截图上传")
@RequestMapping("/open-api/star", produces = [MediaType.APPLICATION_JSON_VALUE])
@RestController
class OpenApiStarCaptureController(
    private val tokenService: OpenApiTokenService,
    private val captureService: StarCaptureTransportService,
) {
    @Operation(summary = "上传星石背包临时采集")
    @RequireOpenApiToken
    @PostMapping("/captures", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestPart("manifest") manifest: String,
        @RequestPart("files") files: List<MultipartFile>,
    ): ApiResult<StarCaptureUploadResponse> {
        val principal = tokenService.validateAuthorization(authorization, OpenApiPermission.STAR_CAPTURE_WRITE)
        return success(captureService.upload(principal.userId, principal.accountId, manifest, files))
    }
}
