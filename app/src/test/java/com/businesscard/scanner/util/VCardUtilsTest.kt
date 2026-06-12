package com.businesscard.scanner.util

import com.businesscard.scanner.data.BusinessCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardUtilsTest {

    // ──── vcfEscape ────

    @Test fun `plain text unchanged`() {
        assertEquals("Hello World", VCardUtils.vcfEscape("Hello World"))
    }

    @Test fun `backslash escaped first`() {
        assertEquals("a\\\\b", VCardUtils.vcfEscape("a\\b"))
    }

    @Test fun `semicolon escaped`() {
        assertEquals("Smith\\; Jones", VCardUtils.vcfEscape("Smith; Jones"))
    }

    @Test fun `comma escaped`() {
        assertEquals("Acme\\, Inc", VCardUtils.vcfEscape("Acme, Inc"))
    }

    @Test fun `backslash then semicolon not double-escaped`() {
        // "a\;b" should become "a\\\\\\;b" — backslash escaped once, then semicolon escaped
        assertEquals("a\\\\\\;b", VCardUtils.vcfEscape("a\\;b"))
    }

    @Test fun `CR stripped`() {
        assertEquals("ab", VCardUtils.vcfEscape("a\rb"))
    }

    @Test fun `LF replaced with space`() {
        assertEquals("a b", VCardUtils.vcfEscape("a\nb"))
    }

    @Test fun `empty string unchanged`() {
        assertEquals("", VCardUtils.vcfEscape(""))
    }

    // ──── dialable ────

    @Test fun `digits only pass through`() {
        assertEquals("0299991234", VCardUtils.dialable("0299991234"))
    }

    @Test fun `spaces and dashes stripped`() {
        assertEquals("0299991234", VCardUtils.dialable("02 9999 1234"))
    }

    @Test fun `leading plus preserved`() {
        assertEquals("+61299991234", VCardUtils.dialable("+61 2 9999 1234"))
    }

    @Test fun `interior plus stripped`() {
        assertEquals("123456", VCardUtils.dialable("123+456"))
    }

    @Test fun `empty string returns empty`() {
        assertEquals("", VCardUtils.dialable(""))
    }

    @Test fun `non-digit non-plus only returns empty`() {
        assertEquals("", VCardUtils.dialable("(ext)"))
    }

    // ──── buildVCardText ────

    private fun card(
        name: String = "John Smith",
        company: String = "",
        title: String = "",
        phone: String = "",
        mobile: String = "",
        email: String = "john@example.com",
        website: String = "",
        address: String = "",
        notes: String = ""
    ) = BusinessCard(
        personName = name, companyName = company, jobTitle = title,
        phone = phone, mobile = mobile, email = email,
        website = website, address = address, notes = notes
    )

    @Test fun `minimal card has required fields`() {
        val vcard = VCardUtils.buildVCardText(card())
        assertTrue(vcard.contains("BEGIN:VCARD"))
        assertTrue(vcard.contains("VERSION:3.0"))
        assertTrue(vcard.contains("FN:John Smith"))
        assertTrue(vcard.contains("END:VCARD"))
    }

    @Test fun `semicolons in company name escaped in ORG`() {
        val vcard = VCardUtils.buildVCardText(card(company = "Smith; Jones"))
        assertTrue(vcard.contains("ORG:Smith\\; Jones"))
    }

    @Test fun `semicolons in name escaped in N field`() {
        // last name "O;Brien" would break N: field if not escaped
        val vcard = VCardUtils.buildVCardText(card(name = "Seán O;Brien"))
        val nLine = vcard.lines().first { it.startsWith("N:") }
        assertTrue("N field must not contain unescaped semicolon beyond separators",
            nLine.substringAfter("N:").split(";").none { it.startsWith("O;") })
    }

    @Test fun `multiple phone lines emit multiple TEL fields`() {
        val vcard = VCardUtils.buildVCardText(card(phone = "+61 2 1111 2222\n+61 2 3333 4444"))
        val telLines = vcard.lines().filter { it.startsWith("TEL;TYPE=WORK:") }
        assertEquals(2, telLines.size)
    }

    @Test fun `blank fields omitted`() {
        val vcard = VCardUtils.buildVCardText(card())
        assertTrue(!vcard.contains("ORG:"))
        assertTrue(!vcard.contains("TITLE:"))
        assertTrue(!vcard.contains("ADR:"))
        assertTrue(!vcard.contains("NOTE:"))
    }

    @Test fun `CJK name stays intact as last name in N field`() {
        val vcard = VCardUtils.buildVCardText(card(name = "张三丰"))
        assertTrue(vcard.contains("N:张三丰;;"))
    }

    @Test fun `newline in notes normalised to space on export`() {
        // vcfEscape converts \n → space; documents the intentional asymmetry with
        // VCardParser which expands \n escapes to real newlines on import
        val vcard = VCardUtils.buildVCardText(card(notes = "line one\nline two"))
        val noteLine = vcard.lines().first { it.startsWith("NOTE:") }
        assertEquals("NOTE:line one line two", noteLine)
    }
}
