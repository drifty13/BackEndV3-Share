package com.lhs.share.hub.controller.starinventory

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.hub.controller.starinventory.response.StarInventoryCurrentResponse
import com.lhs.share.hub.controller.starinventory.response.StarInventoryEntryResponse
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.hub.service.starinventory.StarInventoryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import java.time.Instant

class StarInventoryControllerContractTest {
    private val service = mockk<StarInventoryService>()
    private val helper = mockk<AuthenticationHelper>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mockMvc = MockMvcBuilders
            .standaloneSetup(StarInventoryController(service, helper))
            .setControllerAdvice(InventoryExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator)
            .build()
    }

    @Test
    fun `PUT and GET use authenticated owner and snake case current snapshot fields`() {
        val response = StarInventoryCurrentResponse(
            accountId = "acc_a",
            effectiveAt = Instant.parse("2026-08-28T12:00:00Z"),
            updatedAt = Instant.parse("2026-08-28T12:00:01Z"),
            entries = listOf(StarInventoryEntryResponse("ys_1", "main", "Tianfu", "orange", 60)),
        )
        every { helper.requireUserId() } returns "jwt-user"
        every { service.put("jwt-user", "acc_a", any()) } returns response
        every { service.current("jwt-user", "acc_a") } returns response
        val body =
            """
            {
              "effective_at": "2026-08-28T12:00:00Z",
              "entries": [{"instance_id": "ys_1", "kind": "main", "name": "Tianfu", "quality": "orange", "level": 60}]
            }
            """.trimIndent()

        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status_code").value(200))
            .andExpect(jsonPath("$.data.account_id").value("acc_a"))
            .andExpect(jsonPath("$.data.effective_at").value("2026-08-28T12:00:00Z"))
            .andExpect(jsonPath("$.data.entries[0].instance_id").value("ys_1"))
        mockMvc.perform(get("/v1/star-inventory/current").param("account_id", "acc_a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updated_at").value("2026-08-28T12:00:01Z"))

        verify { service.put("jwt-user", "acc_a", any()) }
        verify { service.current("jwt-user", "acc_a") }
    }

    @Test
    fun `invalid entry values use the existing schema validation error`() {
        val invalidKindBody =
            """
            {
              "effective_at": "2026-08-28T12:00:00Z",
              "entries": [{"instance_id": "ys_1", "kind": "invalid", "name": "Tianfu", "quality": "orange", "level": 60}]
            }
            """.trimIndent()
        val validBody =
            """
            {
              "effective_at": "2026-08-28T12:00:00Z",
              "entries": [{"instance_id": "ys_1", "kind": "main", "name": "Tianfu", "quality": "orange", "level": 60}]
            }
            """.trimIndent()

        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidKindBody),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))
        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody.replace("\"level\": 60", "\"level\": 1.5")),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))
        mockMvc.perform(
            put("/v1/star-inventory/current")
                .param("account_id", "acc_a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody.replaceFirst("{", "{\"user_id\":\"other-user\",")),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error.code").value("schema_validation_failed"))
        verify(exactly = 0) { service.put(any(), any(), any()) }
    }

    @Test
    fun `account not found stays hidden behind the standard error envelope`() {
        every { helper.requireUserId() } returns "jwt-user"
        every { service.current("jwt-user", "foreign") } throws
            InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

        mockMvc.perform(get("/v1/star-inventory/current").param("account_id", "foreign"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("account_not_found"))
    }
}
