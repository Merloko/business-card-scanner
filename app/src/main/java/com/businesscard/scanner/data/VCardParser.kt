package com.businesscard.scanner.data

import java.nio.charset.Charset

object VCardParser {

    private val VCARD_BLOCK = Regex("(?i)BEGIN:VCARD.*?END:VCARD", RegexOption.DOT_MATCHES_ALL)

    fun parse(content: String): List<BusinessCard> {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        // Line-folding is handled per-property inside parseOne so that the QP
        // soft-break mechanism (= at end of encoded line) and the RFC 6350
        // fold mechanism (CRLF + single WSP) can be distinguished correctly.
        return VCARD_BLOCK
            .findAll(normalized)
            .map { parseOne(it.value) }
            .filter { it.personName.isNotBlank() || it.email.isNotBlank() }
            .toList()
    }

    private fun parseOne(block: String): BusinessCard {
        val fields = mutableMapOf<String, MutableList<String>>()
        val physicalLines = block.lines()
        var i = 0

        while (i < physicalLines.size) {
            val firstLine = physicalLines[i]
            val colonIdx = firstLine.indexOf(':')
            if (colonIdx < 0) { i++; continue }

            val rawKey = firstLine.substring(0, colonIdx).uppercase().trim()
            val isQP = rawKey.contains("ENCODING=QUOTED-PRINTABLE") || rawKey.contains("ENCODING=QP")
            val charsetName = if (rawKey.contains("CHARSET=")) {
                rawKey.substringAfter("CHARSET=").substringBefore(";").substringBefore(":").trim()
                    .takeIf { it.isNotEmpty() } ?: "UTF-8"
            } else "UTF-8"

            val value: String
            if (isQP) {
                // vCard 2.1 QP: a trailing '=' on the encoded value signals a QP soft
                // line break; the next physical line is the continuation.  An optional
                // leading WSP on that line is the vCard fold-indicator — strip it.
                val qp = StringBuilder(firstLine.substring(colonIdx + 1))
                while (qp.endsWith("=") && i + 1 < physicalLines.size) {
                    qp.deleteCharAt(qp.length - 1) // remove the QP soft-break marker
                    i++
                    val cont = physicalLines[i]
                    qp.append(
                        if (cont.isNotEmpty() && (cont[0] == ' ' || cont[0] == '\t')) cont.substring(1)
                        else cont
                    )
                }
                // Consume any trailing RFC fold continuations (e.g. from non-QP params)
                while (i + 1 < physicalLines.size) {
                    val next = physicalLines[i + 1]
                    if (next.isNotEmpty() && (next[0] == ' ' || next[0] == '\t')) {
                        qp.append(next.substring(1)); i++
                    } else break
                }
                // Drop any leading whitespace between ':' and the encoded value
                value = decodeQuotedPrintable(qp.toString().trimStart(), charsetName)
            } else {
                // RFC 6350 §3.2: a continuation line starts with exactly one WSP char.
                // Strip that char and join to rebuild the logical line.
                val sb = StringBuilder(firstLine.substring(colonIdx + 1))
                while (i + 1 < physicalLines.size) {
                    val next = physicalLines[i + 1]
                    if (next.isNotEmpty() && (next[0] == ' ' || next[0] == '\t')) {
                        sb.append(next.substring(1)); i++
                    } else break
                }
                value = sb.toString().trim()
            }

            val baseKey = rawKey.substringBefore(";")
            if (value.isNotEmpty() && baseKey != "BEGIN" && baseKey != "END" && baseKey != "VERSION") {
                fields.getOrPut(rawKey) { mutableListOf() }.add(value)
            }
            i++
        }

        // Normalize display name: vCard \n sequences become real newlines after unescaping;
        // replace them with spaces so personName is always a single displayable line.
        val name = fields.entries
            .firstOrNull { (k, _) -> k == "FN" || k.startsWith("FN;") }
            ?.value?.firstOrNull()?.let { unescapeVcf(it).replace('\n', ' ').replace('\r', ' ').trim() }
            ?: fields.entries.firstOrNull { (k, _) -> k == "N" || k.startsWith("N;") }
                ?.value?.firstOrNull()?.let { n ->
                    val parts = splitComponents(n)
                    "${parts.getOrElse(1) { "" }} ${parts.getOrElse(0) { "" }}".trim()
                        .replace('\n', ' ').replace('\r', ' ')
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
            ?.let { splitComponents(it).filter { component -> component.trim().isNotBlank() }.joinToString(", ") } ?: ""

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

    // Decodes a vCard 2.1 QUOTED-PRINTABLE encoded value.
    // QP soft line breaks and fold-indicator WSP are already stripped by parseOne;
    // this function only needs to handle hex-encoded bytes (=XX) and literal chars.
    private fun decodeQuotedPrintable(encoded: String, charsetName: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < encoded.length) {
            when {
                encoded[i] != '=' -> {
                    bytes.add(encoded[i].code.and(0xFF).toByte()); i++
                }
                i + 1 >= encoded.length -> {
                    // Trailing bare '=' with nothing after — malformed, skip it
                    i++
                }
                encoded[i + 1] == '\n' || encoded[i + 1] == '\r' -> {
                    // Defensive: QP soft break (parseOne should have consumed these)
                    i++
                    if (i < encoded.length && encoded[i] == '\r') i++
                    if (i < encoded.length && encoded[i] == '\n') i++
                }
                i + 2 >= encoded.length -> {
                    // '=X' at end of string — incomplete hex sequence, skip
                    i = encoded.length
                }
                else -> {
                    val hex = encoded.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        bytes.add(byte.toByte()); i += 3
                    } else {
                        // Not a valid hex pair — emit '=' literally and retry from i+1
                        bytes.add('='.code.toByte()); i++
                    }
                }
            }
        }
        val cs = try { Charset.forName(charsetName) } catch (_: Exception) { Charsets.UTF_8 }
        return bytes.toByteArray().toString(cs)
    }
}
