package com.businesscard.scanner.data

object VCardParser {

    private val LINE_FOLD = Regex("\n[ \t]")
    private val VCARD_BLOCK = Regex("(?i)BEGIN:VCARD.*?END:VCARD", RegexOption.DOT_MATCHES_ALL)

    fun parse(content: String): List<BusinessCard> {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val unfolded = normalized.replace(LINE_FOLD, "")
        return VCARD_BLOCK
            .findAll(unfolded)
            .map { parseOne(it.value) }
            .filter { it.personName.isNotBlank() || it.email.isNotBlank() }
            .toList()
    }

    private fun parseOne(block: String): BusinessCard {
        val fields = mutableMapOf<String, MutableList<String>>()
        for (line in block.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val key = line.substring(0, colonIdx).uppercase().trim()
            val value = line.substring(colonIdx + 1).trim()
            if (value.isNotEmpty() && key != "BEGIN" && key != "END" && key != "VERSION") {
                fields.getOrPut(key) { mutableListOf() }.add(value)
            }
        }

        val name = fields["FN"]?.firstOrNull()?.let { unescapeVcf(it) }
            ?: fields.entries.firstOrNull { (k, _) -> k == "N" || k.startsWith("N;") }
                ?.value?.firstOrNull()?.let { n ->
                    val parts = splitComponents(n)
                    "${parts.getOrElse(1) { "" }} ${parts.getOrElse(0) { "" }}".trim()
                } ?: ""

        val company = fields.entries
            .firstOrNull { (k, _) -> k == "ORG" || k.startsWith("ORG;") }
            ?.value?.firstOrNull()?.let { splitComponents(it).firstOrNull() } ?: ""

        val title = fields.entries
            .firstOrNull { (k, _) -> k == "TITLE" || k.startsWith("TITLE;") }
            ?.value?.firstOrNull()?.let { unescapeVcf(it) } ?: ""

        val phones = mutableListOf<String>()
        val mobiles = mutableListOf<String>()
        for ((k, v) in fields) {
            if (k != "TEL" && !k.startsWith("TEL;")) continue
            val isMobile = k.contains("CELL") || k.contains("MOBILE")
            if (isMobile) mobiles.addAll(v.map { unescapeVcf(it) })
            else phones.addAll(v.map { unescapeVcf(it) })
        }

        val email = fields.entries
            .firstOrNull { (k, _) -> k == "EMAIL" || k.startsWith("EMAIL;") }
            ?.value?.firstOrNull()?.let { unescapeVcf(it) } ?: ""

        val website = fields.entries
            .firstOrNull { (k, _) -> k == "URL" || k.startsWith("URL;") }
            ?.value?.firstOrNull()?.let { unescapeVcf(it) } ?: ""

        val address = fields.entries
            .firstOrNull { (k, _) -> k == "ADR" || k.startsWith("ADR;") }
            ?.value?.firstOrNull()
            ?.let { splitComponents(it).filter { it.trim().isNotBlank() }.joinToString(", ") } ?: ""

        val note = fields.entries
            .firstOrNull { (k, _) -> k == "NOTE" || k.startsWith("NOTE;") }
            ?.value?.firstOrNull()?.let { unescapeVcf(it) } ?: ""

        return BusinessCard(
            personName = name,
            companyName = company,
            jobTitle = title,
            phone = phones.joinToString("\n"),
            mobile = mobiles.joinToString("\n"),
            email = email,
            website = website,
            address = address,
            notes = note
        )
    }

    // Splits a vCard property value on structural (unescaped) semicolons,
    // unescaping each component. Used for N:, ORG:, and ADR: fields.
    private fun splitComponents(value: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            when {
                value[i] == '\\' && i + 1 < value.length -> {
                    sb.append(unescapeSeq(value[i + 1])); i += 2
                }
                value[i] == ';' -> { parts.add(sb.toString()); sb.clear(); i++ }
                else -> { sb.append(value[i]); i++ }
            }
        }
        parts.add(sb.toString())
        return parts
    }

    // Unescapes vCard 3.0 / RFC 6350 escape sequences in a flat field value
    // (FN, TITLE, EMAIL, URL, NOTE, TEL). Does not handle structural semicolons
    // — use splitComponents() for structured fields.
    internal fun unescapeVcf(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                sb.append(unescapeSeq(s[i + 1])); i += 2
            } else {
                sb.append(s[i]); i++
            }
        }
        return sb.toString()
    }

    // Returns the unescaped string for a vCard escape sequence: \X → result.
    // Unknown sequences preserve both characters per RFC 6350 §3.4.
    private fun unescapeSeq(c: Char): String = when (c) {
        '\\' -> "\\"
        ';'  -> ";"
        ','  -> ","
        'n', 'N' -> "\n"
        else -> "\\$c"
    }
}
