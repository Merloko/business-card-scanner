package com.businesscard.scanner.util

import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.ocr.CjkUtils

object VCardUtils {

    fun vcfEscape(s: String) = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r", "")
        .replace("\n", "\\n")

    fun dialable(number: String): String {
        val sb = StringBuilder()
        for (c in number) {
            if (c.isDigit()) sb.append(c)
            else if (c == '+' && sb.isEmpty()) sb.append(c)
        }
        return sb.toString()
    }

    fun buildVCardText(card: BusinessCard) = buildString {
        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        appendLine("FN:${vcfEscape(card.personName)}")
        val nameParts = card.personName.trim().split(Regex("\\s+"))
        val (lastName, firstName) = when {
            CjkUtils.containsCjk(card.personName) -> Pair(card.personName, "")
            nameParts.size >= 2 -> Pair(nameParts.last(), nameParts.dropLast(1).joinToString(" "))
            else -> Pair("", card.personName)
        }
        appendLine("N:${vcfEscape(lastName)};${vcfEscape(firstName)};;;")
        if (card.companyName.isNotBlank()) appendLine("ORG:${vcfEscape(card.companyName)}")
        if (card.jobTitle.isNotBlank()) appendLine("TITLE:${vcfEscape(card.jobTitle)}")
        for (line in card.phone.lines()) if (line.isNotBlank()) appendLine("TEL;TYPE=WORK:${vcfEscape(line.trim())}")
        for (line in card.mobile.lines()) if (line.isNotBlank()) appendLine("TEL;TYPE=CELL:${vcfEscape(line.trim())}")
        if (card.email.isNotBlank()) appendLine("EMAIL:${vcfEscape(card.email)}")
        if (card.website.isNotBlank()) appendLine("URL:${vcfEscape(card.website)}")
        if (card.address.isNotBlank()) appendLine("ADR;TYPE=WORK:;;${vcfEscape(card.address)};;;;")
        if (card.notes.isNotBlank()) appendLine("NOTE:${vcfEscape(card.notes)}")
        appendLine("END:VCARD")
    }
}
