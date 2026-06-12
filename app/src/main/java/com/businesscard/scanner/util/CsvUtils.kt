package com.businesscard.scanner.util

import com.businesscard.scanner.data.BusinessCard

object CsvUtils {

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
        val nonAlpha = Regex("[^a-z ]")
        val map = mutableMapOf<String, Int>()
        headers.forEachIndexed { i, h ->
            when (h.trim().lowercase().replace(nonAlpha, "")) {
                "first name", "firstname", "given name" -> map["firstName"] = i
                "last name", "lastname", "surname", "family name" -> map["lastName"] = i
                "name", "full name", "fullname", "display name", "contact name" -> map["fullName"] = i
                "company", "organization", "organisation", "company name", "employer" -> map["company"] = i
                "job title", "title", "position", "jobtitle", "role" -> map["jobTitle"] = i
                "business phone", "phone", "telephone", "work phone", "tel", "phone number" -> map["phone"] = i
                "mobile phone", "mobile", "cell", "cell phone", "cellular", "mobile number" -> map["mobile"] = i
                "e-mail address", "email", "e-mail", "email address", "mail" -> map["email"] = i
                "web page", "website", "url", "web", "homepage", "website url" -> map["website"] = i
                "business street", "address", "street address", "street", "full address" -> map["address"] = i
                "notes", "note", "comments", "comment", "memo" -> map["notes"] = i
                "tags", "tag", "labels", "label", "categories", "category" -> map["tags"] = i
            }
        }
        return map
    }

    fun csvField(value: String) = "\"${value.replace("\"", "\"\"")}\""

    fun splitName(fullName: String): Pair<String, String> {
        val parts = fullName.trim().split(Regex("\\s+"))
        return if (parts.size >= 2) Pair(parts.dropLast(1).joinToString(" "), parts.last())
        else Pair(fullName, "")
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
                card.phone.lines().firstOrNull().orEmpty(),
                card.mobile.lines().firstOrNull().orEmpty(),
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
