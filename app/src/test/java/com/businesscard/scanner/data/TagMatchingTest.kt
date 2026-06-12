package com.businesscard.scanner.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the SQL expression used in BusinessCardDao.getCardsByTag:
 *   ',' || tags || ',' LIKE '%,' || :tag || ',%'
 *
 * Ensures whole-tag matching — searching "net" must NOT return a card tagged "networking".
 */
class TagMatchingTest {

    private fun tagsContain(tags: String, tag: String): Boolean {
        val wrapped = ",$tags,"
        val pattern = ",$tag,"
        return wrapped.contains(pattern, ignoreCase = true)
    }

    // ── positive matches ──

    @Test fun `single tag matches`() = assertTrue(tagsContain("vip", "vip"))
    @Test fun `first tag in list matches`() = assertTrue(tagsContain("networking,vip", "networking"))
    @Test fun `last tag in list matches`() = assertTrue(tagsContain("networking,vip", "vip"))
    @Test fun `middle tag in list matches`() = assertTrue(tagsContain("a,b,c", "b"))
    @Test fun `case insensitive match`() = assertTrue(tagsContain("VIP", "vip"))
    @Test fun `tag with spaces normalised`() = assertTrue(tagsContain("follow up,vip", "follow up"))

    // ── old LIKE '%%' false-positives now prevented ──

    @Test fun `substring of tag does not match`() = assertFalse(tagsContain("networking", "net"))
    @Test fun `substring of tag does not match middle`() = assertFalse(tagsContain("networking,vip", "work"))
    @Test fun `prefix of tag does not match`() = assertFalse(tagsContain("client", "cli"))
    @Test fun `suffix of tag does not match`() = assertFalse(tagsContain("client", "ient"))

    // ── empty / missing ──

    @Test fun `empty tags returns false`() = assertFalse(tagsContain("", "vip"))
    @Test fun `empty tag query returns false`() = assertFalse(tagsContain("vip,networking", ""))
}
