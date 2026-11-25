package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2ServiceResultTest {
    @Test fun freshUuidResultDoesNotDependOnTheAndroidCacheCatchingUp() {
        assertTrue(Iap2Bluetooth.ServiceResult.fromProbe(4, "present", cached = false, checkedAt = 100).available)
        assertTrue(Iap2Bluetooth.ServiceResult.fromProbe(4, "timeout", cached = true, checkedAt = 100).available)
        assertFalse(Iap2Bluetooth.ServiceResult.fromProbe(4, "absent", cached = false, checkedAt = 100).available)
    }

    @Test fun aTemporaryFailureExpiresAndTargetChangesInvalidateBothResults() {
        val missed = Iap2Bluetooth.ServiceResult.fromProbe(4, "timeout", cached = false, checkedAt = 100)
        val found = Iap2Bluetooth.ServiceResult.fromProbe(4, "present", cached = false, checkedAt = 100)
        assertTrue(missed.canReuse(4, 10_099))
        assertFalse(missed.canReuse(4, 10_100))
        assertTrue(found.canReuse(4, 10_100))
        assertFalse(missed.canReuse(5, 101))
        assertFalse(found.canReuse(5, 101))
    }
}
