package com.businesscard.scanner.util

import com.businesscard.scanner.data.BusinessCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvUtilsTest {

    // ──── parseCsvLine ────

    @Test fun `simple fields`() {
        assertEquals(listOf("a", "b", "c"), CsvUtils.parseCsvLine("a,b,c"))
    }

    @Test fun `quoted field with comma`() {
        assertEquals(listOf("Smith, John", "Acme"), CsvUtils.parseCsvLine("\"Smith, John\",Acme"))
    }

    @Test fun `doubled quotes inside quoted field`() {
        assertEquals(listOf("say \"hi\""), CsvUtils.parseCsvLine("\"say \"\"hi\"\"\""))
    }

    @Test fun `empty fields`() {
        assertEquals(listOf("", "", ""), CsvUtils.parseCsvLine(",,"))
    }

    @Test fun `single field no comma`() {
        assertEquals(listOf("hello"), CsvUtils.parseCsvLine("hello"))
    }

    @Test fun `quoted empty field`() {
        assertEquals(listOf(""), CsvUtils.parseCsvLine("\"\""))
    }

    @Test fun `trailing comma gives empty last field`() {
        assertEquals(listOf("a", ""), CsvUtils.parseCsvLine("a,"))
    }

    // ──── parseCsvRows ────

    @Test fun `parseCsvRows splits simple rows`() {
        val rows = CsvUtils.parseCsvRows("a,b\nc,d\n")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), rows)
    }

    @Test fun `parseCsvRows keeps embedded newline inside quoted field`() {
        val rows = CsvUtils.parseCsvRows("name,notes\nJohn,\"line1\nline2\"\nJane,plain\n")
        assertEquals(3, rows.size)
        assertEquals(listOf("John", "line1\nline2"), rows[1])
        assertEquals(listOf("Jane", "plain"), rows[2])
    }

    @Test fun `parseCsvRows handles CRLF line endings`() {
        val rows = CsvUtils.parseCsvRows("a,b\r\nc,d\r\n")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), rows)
    }

    @Test fun `parseCsvRows skips blank lines`() {
        val rows = CsvUtils.parseCsvRows("a,b\n\nc,d\n")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), rows)
    }

    @Test fun `parseCsvRows handles no trailing newline`() {
        val rows = CsvUtils.parseCsvRows("a,b")
        assertEquals(listOf(listOf("a", "b")), rows)
    }

    // ──── mapCsvHeaders ────

    @Test fun `standard Outlook headers recognized`() {
        val headers = listOf(
            "First Name", "Last Name", "Company", "Job Title",
            "Business Phone", "Mobile Phone", "E-mail Address", "Web Page", "Business Street"
        )
        val map = CsvUtils.mapCsvHeaders(headers)
        assertEquals(0, map["firstName"])
        assertEquals(1, map["lastName"])
        assertEquals(2, map["company"])
        assertEquals(3, map["jobTitle"])
        assertEquals(4, map["phone"])
        assertEquals(5, map["mobile"])
        assertEquals(6, map["email"])
        assertEquals(7, map["website"])
        assertEquals(8, map["address"])
    }

    @Test fun `case insensitive matching`() {
        val map = CsvUtils.mapCsvHeaders(listOf("EMAIL", "COMPANY"))
        assertEquals(0, map["email"])
        assertEquals(1, map["company"])
    }

    @Test fun `alias matching`() {
        val map = CsvUtils.mapCsvHeaders(listOf("Organisation", "Mobile", "Mail"))
        assertEquals(0, map["company"])
        assertEquals(1, map["mobile"])
        assertEquals(2, map["email"])
    }

    @Test fun `unknown header ignored`() {
        val map = CsvUtils.mapCsvHeaders(listOf("Favourite Colour"))
        assertTrue(map.isEmpty())
    }

    @Test fun `fullName key for name column`() {
        val map = CsvUtils.mapCsvHeaders(listOf("Name"))
        assertEquals(0, map["fullName"])
    }

    // ──── splitName ────

    @Test fun `single word stays in first`() {
        assertEquals(Pair("Alice", ""), CsvUtils.splitName("Alice"))
    }

    @Test fun `two words split correctly`() {
        assertEquals(Pair("John", "Smith"), CsvUtils.splitName("John Smith"))
    }

    @Test fun `three words - last is surname`() {
        assertEquals(Pair("Mary Jane", "Watson"), CsvUtils.splitName("Mary Jane Watson"))
    }

    @Test fun `leading trailing whitespace trimmed`() {
        assertEquals(Pair("John", "Smith"), CsvUtils.splitName("  John  Smith  "))
    }

    @Test fun `CJK name kept intact with empty last name`() {
        assertEquals(Pair("田中一郎", ""), CsvUtils.splitName("田中一郎"))
    }

    @Test fun `CJK name with space not split on whitespace`() {
        assertEquals(Pair("田中 一郎", ""), CsvUtils.splitName("田中 一郎"))
    }

    // ──── csvField ────

    @Test fun `plain value wrapped in quotes`() {
        assertEquals("\"hello\"", CsvUtils.csvField("hello"))
    }

    @Test fun `embedded quotes doubled`() {
        assertEquals("\"say \"\"hi\"\"\"", CsvUtils.csvField("say \"hi\""))
    }

    @Test fun `empty string gives empty quoted field`() {
        assertEquals("\"\"", CsvUtils.csvField(""))
    }

    // ──── buildCsv round-trip ────

    @Test fun `exported header round-trips through mapCsvHeaders`() {
        val dummyCard = BusinessCard(personName = "Test", email = "t@example.com")
        val csv = CsvUtils.buildCsv(listOf(dummyCard))
        val headerLine = csv.lines().first()
        val headers = CsvUtils.parseCsvLine(headerLine)
        val map = CsvUtils.mapCsvHeaders(headers)
        assertTrue("phone column must be recognised", map.containsKey("phone"))
        assertTrue("email column must be recognised", map.containsKey("email"))
        assertTrue("notes column must be recognised", map.containsKey("notes"))
        assertTrue("tags column must be recognised", map.containsKey("tags"))
    }

    @Test fun `card data appears in correct columns`() {
        val card = BusinessCard(
            personName = "Jane Doe",
            companyName = "Acme",
            email = "jane@acme.com",
            phone = "+61 2 1234 5678",
            notes = "Met at conference",
            tags = "vip,partner"
        )
        val csv = CsvUtils.buildCsv(listOf(card))
        val lines = csv.lines().filter { it.isNotBlank() }
        val headers = CsvUtils.parseCsvLine(lines[0])
        val map = CsvUtils.mapCsvHeaders(headers)
        val row = CsvUtils.parseCsvLine(lines[1])

        assertEquals("Jane", row[map["firstName"]!!])
        assertEquals("Doe", row[map["lastName"]!!])
        assertEquals("Acme", row[map["company"]!!])
        assertEquals("jane@acme.com", row[map["email"]!!])
        assertEquals("+61 2 1234 5678", row[map["phone"]!!])
        assertEquals("Met at conference", row[map["notes"]!!])
        assertEquals("vip,partner", row[map["tags"]!!])
    }

    @Test fun `commas in field values properly escaped`() {
        val card = BusinessCard(personName = "Smith, John", email = "j@example.com")
        val csv = CsvUtils.buildCsv(listOf(card))
        val row = CsvUtils.parseCsvLine(csv.lines().filter { it.isNotBlank() }[1])
        // 11 columns: firstName, lastName, company, jobTitle, phone, mobile, email,
        // website, address, notes, tags
        assertEquals(11, row.size)
    }
}
