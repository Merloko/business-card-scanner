package com.businesscard.scanner.util

import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.ocr.CjkUtils

object CsvUtils {

    private val NON_ALPHA = Regex("[^a-z ]")
    private val TRAILING_DIGITS = Regex("\\d+$")
    private val WHITESPACE = Regex("\\s+")

    fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' && inQuotes -> inQuotes = false
                c == ',' && !inQuotes -> { fields.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    fun mapCsvHeaders(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        headers.forEachIndexed { i, h ->
            // Strip trailing digit sequences (e.g. "Phone1" → "phone") then non-alpha chars.
            // putIfAbsent ensures the first matching column wins when headers collide.
            val normalized = h.trim().lowercase()
                .replace(TRAILING_DIGITS, "")
                .replace(NON_ALPHA, "")
                .trim()
            val key = when (normalized) {
                "first name", "firstname", "given name" -> "firstName"
                "last name", "lastname", "surname", "family name" -> "lastName"
                "name", "full name", "fullname", "display name", "contact name" -> "fullName"
                "company", "organization", "organisation", "company name", "employer" -> "company"
                "job title", "title", "position", "jobtitle", "role" -> "jobTitle"
                "business phone", "phone", "telephone", "work phone", "tel", "phone number" -> "phone"
                "mobile phone", "mobile", "cell", "cell phone", "cellular", "mobile number" -> "mobile"
                "email", "email address", "mail" -> "email"
                "web page", "website", "url", "web", "homepage", "website url" -> "website"
                "business street", "address", "street address", "street", "full address" -> "address"
                "notes", "note", "comments", "comment", "memo" -> "notes"
                "tags", "tag", "labels", "label", "categories", "category" -> "tags"
                else -> null
            } ?: return@forEachIndexed
            map.putIfAbsent(key, i)
        }
        return map
    }

    fun csvField(value: String) = "\"${value.replace("\"", "\"\"")}\""

    fun splitName(fullName: String): Pair<String, String> {
        val trimmed = fullName.trim()
        if (CjkUtils.containsCjk(trimmed)) return Pair(trimmed, "")
        val parts = trimmed.split(WHITESPACE)
        return if (parts.size >= 2) Pair(parts.dropLast(1).joinToString(" "), parts.last())
        else Pair(trimmed, "")
    }

    fun buildCsv(cards: List<BusinessCard>): String {
        val header = listOf(
            "First Name", "Last Name", "Company", "Job Title",
            "Business Phone", "Mobile Phone", "E-mail Address", "Web Page", "Business Street",
            "Notes", "Tags"
        )
        val rows = cards.map { card ->
            val (first, last) = splitName(card.personName)
            listOf(
                first, last, card.companyName, card.jobTitle,
                card.phone.lines().firstOrNull { it.isNotBlank() }.orEmpty(),
                card.mobile.lines().firstOrNull { it.isNotBlank() }.orEmpty(),
                card.email, card.website, card.address,
                // Collapse newlines so each contact stays on a single CSV row.
                // parseCsvLine has no multi-line quoted-field awareness.
                card.notes.replace('\n', ' ').replace('\r', ' '),
                card.tags.replace('\n', ' ').replace('\r', ' ')
            )
        }
        return buildString {
            appendLine(header.joinToString(",") { csvField(it) })
            rows.forEach { appendLine(it.joinToString(",") { csvField(it) }) }
        }
    }
}
