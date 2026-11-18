package io.github.hypercar.settings

import org.junit.Assert.*
import org.junit.Test

class SavedPhoneMacTest {
    private val real = "84:AE:DE:56:D3:76"
    private val fallback = "F6:6F:1B:BF:FC:CE"
    private var persisted: String? = null
    private fun settings() = SavedPhoneMac({ persisted }, { persisted = it })

    @Test fun firstSuccessfulAddressSurvivesReloadAndLaterCandidates() {
        for ((first, later) in listOf(real to fallback, fallback to real)) {
            persisted = null
            val initial = settings()
            assertTrue(initial.remember(initial.ticket(), first.lowercase()))
            val reopened = settings()
            assertFalse(reopened.remember(reopened.ticket(), later))
            assertEquals(first, persisted)
        }
    }

    @Test fun manualEditsAndClearsSuppressPendingAutomaticWrites() {
        val settings = settings()
        val oldSession = settings.ticket()
        settings.edit(real)
        assertFalse(settings.remember(oldSession, fallback))
        assertFalse(settings.remember(settings.ticket(), fallback))
        val pendingQuery = settings.ticket()
        settings.edit(null)
        assertFalse(settings.remember(oldSession, fallback))
        assertFalse(settings.remember(pendingQuery, real))
        assertNull(persisted)
        assertTrue(settings.remember(settings.ticket(), real))
    }

    @Test fun missingOrPlaceholderResultsLeaveFieldAvailable() {
        val settings = settings()
        for (address in listOf(null, "", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF"))
            assertFalse(settings.remember(settings.ticket(), address))
        assertNull(persisted)
        assertTrue(settings.remember(settings.ticket(), fallback))
    }
}
