package com.businesscard.scanner.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdTest {

    // Mirrors the masking used in ReminderReceiver and ContactDetailActivity for
    // PendingIntent request codes and notification IDs. Long IDs >= 2^31 would
    // produce negative or duplicate Int values without the mask.
    private fun mask(id: Long) = (id and 0x7FFFFFFF).toInt()

    @Test fun `mask always produces non-negative Int`() {
        val ids = listOf(
            0L,
            1L,
            Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong() + 1,
            2_147_483_648L,
            Long.MAX_VALUE,
            -1L,
            Long.MIN_VALUE
        )
        for (id in ids) {
            val masked = mask(id)
            assertTrue("id=$id masked to $masked must be >= 0", masked >= 0)
        }
    }

    @Test fun `mask is stable - same id always produces same result`() {
        val id = 9_999_999_999L
        assertTrue(mask(id) == mask(id))
    }

    @Test fun `mask strips high bits leaving lower 31`() {
        // 2^31 = 2147483648; masking should give 0
        val id = 1L shl 31
        assertTrue(mask(id) == 0)
        // 2^31 + 1 should give 1
        assertTrue(mask(id + 1) == 1)
    }
}
