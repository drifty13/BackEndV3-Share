package com.lhs.share.openapi

import com.lhs.share.hub.service.star.StarCaptureTransportService
import com.lhs.share.hub.service.star.StarCaptureUploadResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.time.Instant

class OpenApiStarCaptureControllerContractTest {
    private val tokenService = mockk<OpenApiTokenService>()
    private val captureService = mockk<StarCaptureTransportService>()
    private val controller = OpenApiStarCaptureController(tokenService, captureService)

    @Test
    fun `upload requires the dedicated scope and only forwards the token bound account`() {
        val files = listOf(MockMultipartFile("files", "capture-00.png", "image/png", byteArrayOf(1)))
        every { tokenService.validateAuthorization("Bearer star", OpenApiPermission.STAR_CAPTURE_WRITE) } returns
            OpenApiPrincipal("u1", "acc1")
        every { captureService.upload("u1", "acc1", "{}", files) } returns StarCaptureUploadResponse("capture", "main", 1, Instant.EPOCH)

        val response = controller.upload("Bearer star", "{}", files)

        assertEquals("capture", response.data?.captureId)
        verify { tokenService.validateAuthorization("Bearer star", OpenApiPermission.STAR_CAPTURE_WRITE) }
        verify { captureService.upload("u1", "acc1", "{}", files) }
    }
}
