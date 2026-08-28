package com.lhs.share.hub.service.starinventory

import com.lhs.share.hub.controller.starinventory.request.StarInventoryCurrentRequest
import com.lhs.share.hub.controller.starinventory.request.StarInventoryEntryRequest
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.inventory.InventoryApiException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class StarInventoryServiceTest {
    private val accountRepository = mockk<SubAccountRepository>()
    private val currentRepository = mockk<StarInventoryCurrentRepository>()
    private val snapshots = mutableMapOf<Pair<String, String>, StarInventoryCurrent>()
    private lateinit var service: StarInventoryService

    @BeforeEach
    fun setUp() {
        snapshots.clear()
        every { accountRepository.findByUserIdAndAccountId(any(), any()) } answers {
            when (firstArg<String>() to secondArg<String>()) {
                "u1" to "main" -> SubAccount(id = "a1", userId = "u1", accountId = "main", name = "main")
                else -> null
            }
        }
        every { currentRepository.findByUserIdAndAccountId(any(), any()) } answers {
            snapshots[firstArg<String>() to secondArg<String>()]
        }
        every { currentRepository.save(any()) } answers {
            firstArg<StarInventoryCurrent>().also { snapshots[it.userId to it.accountId] = it }
        }
        service = StarInventoryService(accountRepository, currentRepository)
    }

    @Test
    fun `valid put creates and get returns the current snapshot`() {
        val stored = service.put("u1", "main", request(entry("one")))
        val fetched = service.current("u1", "main")

        assertEquals("main", stored.accountId)
        assertEquals("one", stored.entries.single().instanceId)
        assertEquals(stored, fetched)
        assertTrue(stored.updatedAt != null)
    }

    @Test
    fun `second put fully replaces prior entries`() {
        service.put("u1", "main", request(entry("one"), entry("two", kind = "support")))
        val replaced = service.put("u1", "main", request(entry("three", quality = "purple", level = 45)))

        assertEquals(listOf("three"), replaced.entries.map { it.instanceId })
        assertEquals(listOf("three"), service.current("u1", "main").entries.map { it.instanceId })
    }

    @Test
    fun `empty put clears the current inventory`() {
        service.put("u1", "main", request(entry("one")))
        val cleared = service.put("u1", "main", request())

        assertTrue(cleared.entries.isEmpty())
        assertTrue(service.current("u1", "main").entries.isEmpty())
    }

    @Test
    fun `valid account without inventory returns a successful empty snapshot`() {
        val current = service.current("u1", "main")

        assertEquals("main", current.accountId)
        assertNull(current.effectiveAt)
        assertNull(current.updatedAt)
        assertTrue(current.entries.isEmpty())
    }

    @Test
    fun `foreign or nonexistent accounts return account not found`() {
        val error = assertThrows(InventoryApiException::class.java) { service.current("u1", "foreign") }

        assertEquals(404, error.status.value())
        assertEquals("account_not_found", error.code)
    }

    @Test
    fun `duplicate instance id is rejected`() {
        val error = assertThrows(InventoryApiException::class.java) {
            service.put("u1", "main", request(entry("one"), entry("one", kind = "support")))
        }

        assertSchemaError(error)
    }

    @Test
    fun `invalid kind is rejected`() {
        val error = assertThrows(InventoryApiException::class.java) {
            service.put("u1", "main", request(entry("one", kind = "other")))
        }

        assertSchemaError(error)
    }

    @Test
    fun `invalid quality is rejected`() {
        val error = assertThrows(InventoryApiException::class.java) {
            service.put("u1", "main", request(entry("one", quality = "other")))
        }

        assertSchemaError(error)
    }

    @Test
    fun `levels outside the allowed range are rejected`() {
        val tooLow = assertThrows(InventoryApiException::class.java) {
            service.put("u1", "main", request(entry("one", level = 0)))
        }
        val tooHigh = assertThrows(InventoryApiException::class.java) {
            service.put("u1", "main", request(entry("one", level = 61)))
        }

        assertSchemaError(tooLow)
        assertSchemaError(tooHigh)
    }

    private fun request(vararg entries: StarInventoryEntryRequest) = StarInventoryCurrentRequest(
        effectiveAt = Instant.parse("2026-08-28T12:00:00Z"),
        entries = entries.toList(),
    )

    private fun entry(instanceId: String, kind: String = "main", quality: String = "orange", level: Int = 60) =
        StarInventoryEntryRequest(instanceId, kind, "Tianfu", quality, level)

    private fun assertSchemaError(error: InventoryApiException) {
        assertEquals(422, error.status.value())
        assertEquals("schema_validation_failed", error.code)
    }
}
