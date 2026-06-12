package com.businesscard.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardParserTest {

    // ──── Basic field parsing ────

    @Test fun `name and email from FN and EMAIL`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:Jane Doe
            EMAIL:jane@example.com
            END:VCARD
        """.trimIndent())
        assertEquals(1, cards.size)
        assertEquals("Jane Doe", cards[0].personName)
        assertEquals("jane@example.com", cards[0].email)
    }

    @Test fun `TEL work phone goes to phone field`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            TEL;TYPE=WORK:+61 2 1234 5678
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("+61 2 1234 5678", cards[0].phone)
        assertTrue(cards[0].mobile.isBlank())
    }

    @Test fun `TEL CELL goes to mobile field`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            TEL;TYPE=CELL:+61 400 123 456
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("+61 400 123 456", cards[0].mobile)
        assertTrue(cards[0].phone.isBlank())
    }

    @Test fun `TEL MOBILE goes to mobile field`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            TEL;TYPE=MOBILE:+61 400 000 000
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("+61 400 000 000", cards[0].mobile)
    }

    @Test fun `ORG field populates companyName`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            ORG:Acme Corp
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("Acme Corp", cards[0].companyName)
    }

    @Test fun `TITLE field populates jobTitle`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            TITLE:Senior Engineer
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("Senior Engineer", cards[0].jobTitle)
    }

    @Test fun `URL field populates website`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            URL:https://www.example.com
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("https://www.example.com", cards[0].website)
    }

    @Test fun `NOTE field populates notes`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            NOTE:Met at conference
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("Met at conference", cards[0].notes)
    }

    @Test fun `ADR field populates address without empty segments`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            ADR;TYPE=WORK:;;123 Main St;Sydney;NSW;2000;Australia
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        val addr = cards[0].address
        assertTrue(addr.contains("123 Main St"))
        assertTrue(addr.contains("Sydney"))
        assertFalse(addr.contains(";;"))
    }

    // ──── N field fallback ────

    @Test fun `N field used when FN absent`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            N:Smith;John;;;
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals(1, cards.size)
        assertEquals("John Smith", cards[0].personName)
    }

    // ──── Multiple contacts ────

    @Test fun `two vCards in one string`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:Jane Doe
            EMAIL:jane@example.com
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Bob Smith
            EMAIL:bob@example.com
            END:VCARD
        """.trimIndent())
        assertEquals(2, cards.size)
        assertEquals("Jane Doe", cards[0].personName)
        assertEquals("Bob Smith", cards[1].personName)
    }

    // ──── CJK content ────

    @Test fun `CJK name in FN field`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:张三丰
            EMAIL:zhang@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("张三丰", cards[0].personName)
    }

    @Test fun `Japanese company name`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:田中一郎
            ORG:株式会社テクノ
            EMAIL:tanaka@techno.jp
            END:VCARD
        """.trimIndent())
        assertEquals("田中一郎", cards[0].personName)
        assertEquals("株式会社テクノ", cards[0].companyName)
    }

    // ──── Edge cases ────

    @Test fun `empty input returns empty list`() {
        assertTrue(VCardParser.parse("").isEmpty())
    }

    @Test fun `vCard with no name and no email is filtered out`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            TEL:+61 2 1234 5678
            END:VCARD
        """.trimIndent())
        assertTrue(cards.isEmpty())
    }

    @Test fun `CRLF line endings normalised`() {
        val cards = VCardParser.parse(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Jane Doe\r\nEMAIL:jane@example.com\r\nEND:VCARD"
        )
        assertEquals(1, cards.size)
        assertEquals("Jane Doe", cards[0].personName)
    }

    @Test fun `properties with type parameters parsed`() {
        // e.g. ORG;CHARSET=UTF-8 — the key becomes "ORG;CHARSET=UTF-8", which starts with "ORG;"
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            ORG;CHARSET=UTF-8:Acme Corp
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("Acme Corp", cards[0].companyName)
    }

    @Test fun `malformed lines without colon are skipped`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            BADLINE
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals(1, cards.size)
        assertEquals("John Smith", cards[0].personName)
    }

    @Test fun `multiple TEL entries - work phones joined with newline`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            TEL;TYPE=WORK:+61 2 1111 2222
            TEL;TYPE=WORK:+61 2 3333 4444
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        val lines = cards[0].phone.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
    }

    // ──── vCard escape sequence handling ────

    @Test fun `escaped semicolons in ORG field are unescaped`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            ORG:Smith\; Jones & Associates
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("Smith; Jones & Associates", cards[0].companyName)
    }

    @Test fun `escaped commas in NOTE field are unescaped`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:Jane Doe
            NOTE:Met at Conf\, 2024
            EMAIL:jane@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("Met at Conf, 2024", cards[0].notes)
    }

    @Test fun `escaped backslash in FN field is unescaped`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:O\\Brien
            EMAIL:o@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("O\\Brien", cards[0].personName)
    }

    @Test fun `ADR with escaped semicolon in address value reassembles correctly`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Smith
            ADR;TYPE=WORK:;;Suite 5\; 12 Main St;Sydney;NSW;2000;Australia
            EMAIL:john@example.com
            END:VCARD
        """.trimIndent())
        val addr = cards[0].address
        assertTrue("address must contain 'Suite 5; 12 Main St'", addr.contains("Suite 5; 12 Main St"))
        assertTrue("address must contain Sydney", addr.contains("Sydney"))
    }

    @Test fun `plain values without backslash are returned unchanged`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:Alice Brown
            ORG:Acme Corp
            NOTE:No special chars
            EMAIL:alice@acme.com
            END:VCARD
        """.trimIndent())
        assertEquals("Acme Corp", cards[0].companyName)
        assertEquals("No special chars", cards[0].notes)
    }

    @Test fun `trailing backslash at end of field value is preserved`() {
        // unescapeVcf guard (i + 1 < s.length) falls through to else, keeping the backslash
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:trail\\\nEMAIL:t@example.com\nEND:VCARD"
        val cards = VCardParser.parse(vcard)
        assertEquals("trail\\", cards[0].personName)
    }

    @Test fun `backslash-n escape in NOTE expands to newline char`() {
        val cards = VCardParser.parse("""
            BEGIN:VCARD
            VERSION:3.0
            FN:Jane Doe
            NOTE:line one\nline two
            EMAIL:jane@example.com
            END:VCARD
        """.trimIndent())
        assertEquals("line one\nline two", cards[0].notes)
    }
}
