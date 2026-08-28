package com.lhs.share.hub.controller.starinventory.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant

data class StarInventoryCurrentRequest(
    @field:NotNull(message = "effective_at is required")
    @field:Schema(format = "date-time")
    val effectiveAt: Instant,
    @field:NotNull(message = "entries is required")
    @field:Valid
    val entries: List<StarInventoryEntryRequest>,
) {
    @JsonAnySetter
    fun rejectUserId(name: String, value: Any?) {
        if (name == "user_id") {
            throw IllegalArgumentException("user_id must not be present in a star inventory snapshot")
        }
    }
}

data class StarInventoryEntryRequest(
    @field:NotBlank(message = "entries[].instance_id is required")
    val instanceId: String,
    @field:NotBlank(message = "entries[].kind is required")
    @field:Pattern(regexp = "main|support", message = "entries[].kind must be main or support")
    @field:Schema(allowableValues = ["main", "support"])
    val kind: String,
    @field:NotBlank(message = "entries[].name is required")
    val name: String,
    @field:NotBlank(message = "entries[].quality is required")
    @field:Pattern(regexp = "orange|purple|blue|green|white", message = "entries[].quality is invalid")
    @field:Schema(allowableValues = ["orange", "purple", "blue", "green", "white"])
    val quality: String,
    @field:JsonDeserialize(using = StrictIntegerDeserializer::class)
    @field:Min(value = 1, message = "entries[].level must be at least 1")
    @field:Max(value = 60, message = "entries[].level must be at most 60")
    val level: Int,
)

private class StrictIntegerDeserializer : JsonDeserializer<Int>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Int {
        if (parser.currentToken != JsonToken.VALUE_NUMBER_INT) {
            throw JsonMappingException.from(parser, "entries[].level must be an integer")
        }
        return parser.intValue
    }
}
