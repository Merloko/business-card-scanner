package com.businesscard.scanner.data

object VCardParser {

    fun parse(content: String): List<BusinessCard> {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val unfolded = normalized.replace(Regex("\n[ \t]"), "")
        return Regex("(?i)BEGIN:VCARD.*?END:VCARD", RegexOption.DOT_MATCHES_ALL)
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

        val name = fields["FN"]?.firstOrNull()
            ?: fields.entries.firstOrNull { (k, _) -> k == "N" || k.startsWith("N;") }
                ?.value?.firstOrNull()?.let { n ->
                    val parts = n.split(";")
                    "${parts.getOrElse(1) { "" }} ${parts.getOrElse(0) { "" }}".trim()
                } ?: ""

        val company = fields.entries
            .firstOrNull { (k, _) -> k == "ORG" || k.startsWith("ORG;") }
            ?.value?.firstOrNull()?.split(";")?.firstOrNull() ?: ""

        val title = fields.entries
            .firstOrNull { (k, _) -> k == "TITLE" || k.startsWith("TITLE;") }
            ?.value?.firstOrNull() ?: ""

        val phones = mutableListOf<String>()
        val mobiles = mutableListOf<String>()
        for ((k, v) in fields) {
            if (k != "TEL" && !k.startsWith("TEL;")) continue
            val isMobile = k.contains("CELL") || k.contains("MOBILE")
            if (isMobile) mobiles.addAll(v) else phones.addAll(v)
        }

        val email = fields.entries
            .firstOrNull { (k, _) -> k == "EMAIL" || k.startsWith("EMAIL;") }
            ?.value?.firstOrNull() ?: ""

        val website = fields.entries
            .firstOrNull { (k, _) -> k == "URL" || k.startsWith("URL;") }
            ?.value?.firstOrNull() ?: ""

        val address = fields.entries
            .firstOrNull { (k, _) -> k == "ADR" || k.startsWith("ADR;") }
            ?.value?.firstOrNull()
            ?.split(";")?.filter { it.isNotBlank() }?.joinToString(", ") ?: ""

        val note = fields.entries
            .firstOrNull { (k, _) -> k == "NOTE" || k.startsWith("NOTE;") }
            ?.value?.firstOrNull() ?: ""

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
}
