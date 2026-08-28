package com.lhs.share.hub.controller.starinventory

import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.starinventory.request.StarInventoryCurrentRequest
import com.lhs.share.hub.controller.starinventory.response.StarInventoryCurrentResponse
import com.lhs.share.hub.service.starinventory.StarInventoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Star inventory", description = "Current YuanStar instances in HubBackend")
@RequestMapping("/v1/star-inventory", produces = [MediaType.APPLICATION_JSON_VALUE])
@RestController
class StarInventoryController(
    private val starInventoryService: StarInventoryService,
    private val helper: AuthenticationHelper,
) {
    @Operation(summary = "Replace the current star inventory")
    @RequireJwt
    @PutMapping("/current", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun put(
        @RequestParam(name = "account_id") accountId: String,
        @Valid @RequestBody request: StarInventoryCurrentRequest,
    ): ApiResult<StarInventoryCurrentResponse> = success(
        starInventoryService.put(helper.requireUserId(), accountId, request),
    )

    @Operation(summary = "Get the current star inventory")
    @RequireJwt
    @GetMapping("/current")
    fun current(@RequestParam(name = "account_id") accountId: String): ApiResult<StarInventoryCurrentResponse> =
        success(starInventoryService.current(helper.requireUserId(), accountId))
}
