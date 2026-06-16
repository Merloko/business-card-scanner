package com.businesscard.scanner.data

import com.businesscard.scanner.util.VCardUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class VCardRoundTripTest {

    private fun roundTrip(card: BusinessCard): BusinessCard =
        VCardParser.parse(VCardUtils.buildVCardText(card)).first()

    @Test fun `plain card round-trips cleanly`() {
        val original = BusinessCard(personName = "Jane Doe", email = "jane@example.com")
        val parsed = roundTrip(original)
        assertEquals(original.personName, parsed.personName)
        assertEquals(original.email, parsed.email)
    }

    @Test fun `semicolons in company name survive round-trip`() {
        val original = BusinessCard(
            personName = "John Smith",
            companyName = "Smith; Jones & Associates",
            email = "john@example.com"
        )
        assertEquals(original.companyName, roundTrip(original).companyName)
    }

    @Test fun `commas in job title survive round-trip`() {
        val original = BusinessCard(
            personName = "Jane Doe",
            jobTitle = "Director, Sales & Marketing",
            email = "jane@example.com"
        )
        assertEquals(original.jobTitle, roundTrip(original).jobTitle)
    }

    @Test fun `backslash in name survives round-trip`() {
        val original = BusinessCard(personName = "O\\Brien", email = "o@example.com")
        assertEquals(original.personName, roundTrip(original).personName)
    }

    @Test fun `address with semicolons and commas survives round-trip`() {
        val original = BusinessCard(
            personName = "John Smith",
            email = "john@example.com",
            address = "Suite 5; Building 3, 123 Main St"
        )
        assertEquals(original.address, roundTrip(original).address)
    }

    @Test fun `multiple phone numbers survive round-trip`() {
        val original = BusinessCard(
            personName = "John Smith",
            email = "john@example.com",
            phone = "+61 2 1111 2222\n+61 2 3333 4444"
        )
        val expectedPhones = original.phone.lines().filter { it.isNotBlank() }
        val parsedPhones = roundTrip(original).phone.lines().filter { it.isNotBlank() }
        assertEquals(expectedPhones, parsedPhones)
    }

    @Test fun `notes with commas and semicolons survive round-trip`() {
        val original = BusinessCard(
            personName = "Jane Doe",
            email = "jane@example.com",
            notes = "Met at conference, great contact; follow up"
        )
        assertEquals(original.notes, roundTrip(original).notes)
    }

    @Test fun `notes with embedded newline survive round-trip`() {
        val original = BusinessCard(
            personName = "Jane Doe",
            email = "jane@example.com",
            notes = "line one\nline two"
        )
        assertEquals(original.notes, roundTrip(original).notes)
    }

    @Test fun `CJK name and company survive round-trip`() {
        val original = BusinessCard(
            personName = "田中一郎",
            companyName = "株式会社テクノ",
            email = "tanaka@techno.jp"
        )
        val parsed = roundTrip(original)
        assertEquals(original.personName, parsed.personName)
        assertEquals(original.companyName, parsed.companyName)
    }
}
