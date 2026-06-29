package com.businesscard.scanner.ocr

import android.util.Patterns
import java.util.regex.Pattern

object TextParser {

    data class ParsedContact(
        val personName: String = "",
        val companyName: String = "",
        val jobTitle: String = "",
        val phone: String = "",
        val mobile: String = "",
        val email: String = "",
        val website: String = "",
        val address: String = ""
    )

    // Matches phone numbers with optional country code, parentheses, separators
    private val PHONE_PATTERN = Pattern.compile(
        """(?:(?:\+|00)\d{1,3}[\s\-.])?(?:\(?\d{2,4}\)?[\s\-.])\d{3,4}[\s\-.]?\d{3,4}(?:[\s\-.]?\d{1,4})?"""
    )

    // Label prefixes like "T:", "M:", "Ph:", "Mobile:", "Tel:", "F:" before a number
    private val PHONE_LABEL_PATTERN = Pattern.compile(
        """(?:^|(?<=\s))(?:t|m|f|tel|mob|mobile|ph|phone|fax|direct|office)\s*[:\-]\s*([\d\s\-()+.]{7,})""",
        Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
    )

    private val EMAIL_PATTERN = Patterns.EMAIL_ADDRESS

    private val WEBSITE_PATTERN = Pattern.compile(
        """(?:https?://)?(?:www\.)?[a-zA-Z0-9][a-zA-Z0-9\-]{1,61}[a-zA-Z0-9]\.[a-zA-Z]{2,}(?:/\S*)?"""
    )

    private val JOB_TITLE_KEYWORDS = listOf(
        "ceo", "cto", "cfo", "coo", "director", "manager", "engineer", "developer",
        "designer", "analyst", "consultant", "president", "founder", "founding",
        "owner", "chairman", "secretary", "treasurer",
        "sales", "marketing", "account", "executive", "officer", "head of ",
        "senior", "junior", "lead", "principal", "associate", "coordinator",
        "specialist", "representative", "advisor", "partner", "vice president", "vp"
    )

    private val COMPANY_INDICATORS = listOf(
        // English / Australian
        "pty", "ltd", "limited", "inc", "corp", "corporation", "llc", "co.",
        "group", "solutions", "services", "technologies", "consulting", "enterprises",
        "holdings", "international", "global", "australia", "pty ltd",
        // Indonesian / SE Asian — matched as whole words via COMPANY_WORD_INDICATORS
        "tbk",
        // Chinese (Simplified & Traditional)
        "有限公司", "股份有限公司", "集团", "企业", "贸易", "科技",
        // Japanese
        "株式会社", "有限会社", "合同会社", "合資会社"
    )

    // Short tokens that would false-match as substrings (e.g. "pt" in "concept", "cv" in "invoice")
    // — checked as whole whitespace-delimited words only.
    private val COMPANY_WORD_INDICATORS = setOf("pt", "cv")

    private val ADDRESS_WORDS = listOf(
        "jl.", "ji.", "jln", "kav", "floor", "tower", "street", "road", "avenue",
        "blvd", "lane", "drive", "suite", "level"
    )

    private val GENERIC_EMAIL_DOMAINS = setOf(
        "gmail", "yahoo", "outlook", "hotmail", "icloud", "proton", "mail", "me",
        // Generic SLD labels that are not useful company identifiers
        "info", "web", "support", "contact", "digital", "media"
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /** Geometry-aware entry point — use this from live OCR / gallery scan. */
    fun parse(frontLines: List<OcrLine>, backLines: List<OcrLine> = emptyList()): ParsedContact {
        val allLines = frontLines + backLines
        val heightMap = allLines
            .filter { it.heightPx > 0 }
            .associate { it.text.trim() to it.heightPx }
        return parseInternal(allLines.map { it.text }, heightMap)
    }

    /** String-based entry point — kept for unit tests and legacy callers. */
    fun parse(frontText: String, backText: String = ""): ParsedContact {
        val allText = "$frontText\n$backText"
        val lines = allText.lines().map { it.trim() }.filter { it.isNotBlank() }
        return parseInternal(lines, emptyMap())
    }

    /** Returns a human-readable breakdown of how each piece of the raw OCR text was classified. */
    fun debugParse(frontLines: List<OcrLine>, backLines: List<OcrLine> = emptyList()): String {
        val allLines  = frontLines + backLines
        val lines     = allLines.map { it.text.trim() }.filter { it.isNotBlank() }
        val heightMap = allLines.filter { it.heightPx > 0 }.associate { it.text.trim() to it.heightPx }
        val allText   = lines.joinToString("\n")
        val sb = StringBuilder()

        sb.appendLine("Lines (${lines.size}):")
        allLines.filter { it.text.isNotBlank() }.forEachIndexed { i, ol ->
            val hTag = if (ol.heightPx > 0) " h=${ol.heightPx}px" else ""
            sb.appendLine("  [$i]$hTag ${ol.text.trim()}")
        }
        sb.appendLine()

        val email = extractEmail(allText)
        sb.appendLine("Email: ${email.ifBlank { "(none)" }}")

        val (phone, mobile) = extractPhones(allText)
        sb.appendLine("Phone: ${phone.ifBlank { "(none)" }}")
        sb.appendLine("Mobile: ${mobile.ifBlank { "(none)" }}")

        val website = extractWebsite(allText, email)
        sb.appendLine("Website: ${website.ifBlank { "(none)" }}")

        val address = extractAddress(lines)
        sb.appendLine("Address: ${address.ifBlank { "(none)" }}")

        val jobTitle = extractJobTitle(lines, address)
        sb.appendLine("Job title: ${jobTitle.ifBlank { "(none)" }}")

        val allPhones = (phone.lines() + mobile.lines()).filter { it.isNotBlank() }
        val (personName, companyName) = extractNameAndCompany(lines, email, allPhones, website, jobTitle, address, heightMap)
        sb.appendLine("Name: ${personName.ifBlank { "(none)" }}")
        sb.appendLine("Company: ${companyName.ifBlank { "(none)" }}")
        sb.appendLine()

        val skipLines = (setOf(email, website, jobTitle, address) + allPhones).filter { it.isNotBlank() }
        val candidates = lines.filter { line ->
            skipLines.none { line.contains(it, ignoreCase = true) } &&
            !line.matches(DIGIT_RUN_PATTERN) &&
            !line.trimEnd().endsWith(',') &&
            line.length in 2..60
        }
        sb.appendLine("Name/company candidates (${candidates.size}):")
        if (candidates.isEmpty()) sb.appendLine("  (all lines were skipped)")
        candidates.forEach { line ->
            val h = heightMap[line]?.let { " h=${it}px" } ?: ""
            sb.appendLine("  • [name=${nameLikelihood(line)}$h] $line")
        }
        sb.appendLine()
        sb.append("Skipped because matched phone/email/etc (${skipLines.size}): ${skipLines.joinToString(", ").ifBlank { "(none)" }}")

        return sb.toString()
    }

    /** String-based debugParse — for callers that don't have OcrLine geometry. */
    fun debugParse(frontText: String, backText: String = ""): String {
        val allText = "$frontText\n$backText"
        val lines = allText.lines().map { OcrLine(it.trim(), 0, 0) }.filter { it.text.isNotBlank() }
        return debugParse(lines, emptyList())
    }

    // ── Internal implementation ───────────────────────────────────────────────

    private fun parseInternal(lines: List<String>, heightMap: Map<String, Int>): ParsedContact {
        val allText = lines.joinToString("\n")
        val email = extractEmail(allText)
        val (phone, mobile) = extractPhones(allText)
        val website = extractWebsite(allText, email)
        val address = extractAddress(lines)
        val jobTitle = extractJobTitle(lines, address)
        val allPhones = (phone.lines() + mobile.lines()).filter { it.isNotBlank() }
        val (personName, companyName) = extractNameAndCompany(
            lines, email, allPhones, website, jobTitle, address, heightMap
        )
        return ParsedContact(
            personName = personName,
            companyName = companyName,
            jobTitle = jobTitle,
            phone = phone,
            mobile = mobile,
            email = email,
            website = website,
            address = address
        )
    }

    private fun extractEmail(text: String): String {
        val matcher = EMAIL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group() else ""
    }

    // Returns Pair(landlines, mobiles) — both as newline-joined strings
    private fun extractPhones(text: String): Pair<String, String> {
        // Remove known business identifiers so their digit sequences aren't matched as phone numbers
        val cleaned = stripBusinessIdentifiers(text)
        val found = mutableListOf<String>()

        // Labelled numbers first (T:, M:, Ph:, Mobile:, etc.)
        val labelMatcher = PHONE_LABEL_PATTERN.matcher(cleaned)
        while (labelMatcher.find()) {
            val raw = labelMatcher.group(1)?.trim() ?: continue
            val candidate = trimToLastDigit(raw)
            val digits = candidate.filter { it.isDigit() }
            if (isValidPhoneDigitCount(digits.length)) found.add(candidate)
        }

        // Unlabelled numbers
        val matcher = PHONE_PATTERN.matcher(cleaned)
        while (matcher.find()) {
            val candidate = matcher.group().trim()
            val digits = candidate.filter { it.isDigit() }
            if (isValidPhoneDigitCount(digits.length) &&
                found.none { it.contains(candidate) || candidate.contains(it) }) {
                found.add(candidate)
            }
        }

        val mobiles = found.filter { isMobile(it) }
        val landlines = found.filter { !isMobile(it) }
        return Pair(landlines.joinToString("\n"), mobiles.joinToString("\n"))
    }

    private val DIGIT_RUN_PATTERN = Regex(""".*\d{3,}.*""")
    private val WHITESPACE = Regex("\\s+")
    private val ABN_PATTERN = Regex("""(?i)ABN\s*:?\s*\d[\d\s]{8,13}""")
    private val ACN_PATTERN = Regex("""(?i)ACN\s*:?\s*\d[\d\s]{6,10}""")
    private val BSB_PATTERN = Regex("""(?i)BSB\s*:?\s*\d[\d\s-]{5,9}""")
    private val TFN_PATTERN = Regex("""(?i)TFN\s*:?\s*\d[\d\s]{6,10}""")
    // ISO certification numbers like "ISO 9001", "ISO 14001", "ISO 45001"
    private val ISO_CERT_PATTERN = Regex("""(?i)\bISO\s*\d{4,5}\b""")

    // Erase ABN, ACN, BSB, TFN, ISO cert numbers and similar business identifiers before phone matching
    private fun stripBusinessIdentifiers(text: String): String = text
        .replace(ABN_PATTERN, "")
        .replace(ACN_PATTERN, "")
        .replace(BSB_PATTERN, "")
        .replace(TFN_PATTERN, "")
        .replace(ISO_CERT_PATTERN, "")

    // Strips trailing text annotations like "(AUS)", "(FAX)" after the last digit
    private fun trimToLastDigit(s: String): String {
        val last = s.indexOfLast { it.isDigit() }
        return if (last >= 0) s.substring(0, last + 1).trim() else s
    }

    // Accepts 7-8 digits (local AU), 10 (AU with area code / mobile), 11 (+61...), 12-15 (other international)
    private fun isValidPhoneDigitCount(count: Int) = count in 7..15

    // Australian mobile: local 04xx, international +61 4xx, or alternate-prefix 0061 4xx
    private fun isMobile(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        val normalized = if (digits.startsWith("00")) digits.removePrefix("00") else digits
        return normalized.startsWith("04") || normalized.startsWith("614")
    }

    private fun extractWebsite(text: String, email: String): String {
        val matcher = WEBSITE_PATTERN.matcher(text)
        while (matcher.find()) {
            val match = matcher.group()
            val matchStart = matcher.start()
            val matchEnd = matcher.end()
            // Skip email local parts (followed by '@') and email domains (preceded by '@')
            if (matchEnd < text.length && text[matchEnd] == '@') continue
            if (matchStart > 0 && text[matchStart - 1] == '@') continue
            if (!match.contains("@") && !match.equals(email, ignoreCase = true)) {
                return match
            }
        }
        return ""
    }

    private fun extractAddress(lines: List<String>): String {
        val addressPatterns = listOf(
            Regex("""\d+\s+\w+.*(street|st|avenue|ave|road|rd|drive|dr|lane|ln|way|blvd|boulevard|court|ct|place|pl)""", RegexOption.IGNORE_CASE),
            Regex("""(po box|p\.o\. box)\s*\d+""", RegexOption.IGNORE_CASE),
            // Postcode must be preceded by a state abbreviation to avoid matching phone fragments
            Regex("""(nsw|vic|qld|wa|sa|tas|act|nt)\s*\d{4}""", RegexOption.IGNORE_CASE),
            // 4-digit postcode only when the line also contains a suburb/state word, not just digits
            Regex("""[a-zA-Z]{3,}.*\b\d{4}\b|\b\d{4}\b.*[a-zA-Z]{3,}""")
        )
        for (line in lines) {
            // Skip lines that are phone numbers
            val digitRatio = line.count { it.isDigit() }.toFloat() / line.length.coerceAtLeast(1)
            if (digitRatio > 0.5f) continue
            if (PHONE_LABEL_PATTERN.matcher(line).find()) continue
            if (PHONE_PATTERN.matcher(line).find()) continue
            for (pattern in addressPatterns) {
                if (pattern.containsMatchIn(line)) return line
            }
        }
        return ""
    }

    private fun extractJobTitle(lines: List<String>, addressLine: String = ""): String {
        for (line in lines) {
            if (addressLine.isNotBlank() && line == addressLine) continue
            // Section headers like "Head Office:" or "Representative Office:" end with a colon
            // and are not job titles even when they contain a keyword like "representative".
            if (line.trimEnd().endsWith(':')) continue
            val lower = line.lowercase()
            if (JOB_TITLE_KEYWORDS.any { lower.contains(it) } && line.length < 60) {
                return line
            }
        }
        return ""
    }

    // Finds the longest run of consecutive lines that look like a multi-word brand logo:
    // mostly uppercase, short (≤ 25 chars, ≤ 3 words), not already classified elsewhere.
    // Leading OCR noise characters (pipes, brackets, etc.) are stripped before evaluation.
    // Returns the joined cluster, or "" if no run of 2+ qualifying lines is found.
    private fun findAllCapsCluster(
        lines: List<String>,
        skipLines: List<String>,
        personName: String
    ): String {
        fun stripped(line: String) = line.dropWhile { !it.isLetter() }.trim()

        fun qualifies(line: String): Boolean {
            if (skipLines.any { line.contains(it, ignoreCase = true) }) return false
            if (line == personName) return false
            val s = stripped(line)
            if (s.isBlank() || s.length > 25) return false
            val letters = s.filter { it.isLetter() }
            if (letters.isEmpty()) return false
            val upperRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length
            return upperRatio >= 0.75f && s.split(WHITESPACE).size <= 3
        }

        var best = listOf<String>()
        var run = mutableListOf<String>()

        for (line in lines) {
            if (qualifies(line)) {
                run.add(stripped(line))
            } else {
                if (run.size >= 2 && run.size > best.size) best = run.toList()
                run = mutableListOf()
            }
        }
        if (run.size >= 2 && run.size > best.size) best = run.toList()

        return best.joinToString(" ")
    }

    private fun extractNameAndCompany(
        lines: List<String>,
        email: String,
        phones: List<String>,
        website: String,
        jobTitle: String,
        address: String,
        heightMap: Map<String, Int> = emptyMap()
    ): Pair<String, String> {
        val skipLines = (setOf(email, website, jobTitle, address) + phones)
            .filter { it.isNotBlank() }

        val candidates = lines.filter { line ->
            skipLines.none { line.contains(it, ignoreCase = true) } &&
            !line.matches(DIGIT_RUN_PATTERN) &&
            !line.trimEnd().endsWith(',') && // address fragments end with comma
            line.length in 2..60
        }

        var personName = ""
        var companyName = ""

        // First pass: find company by indicator — wins regardless of position in document
        for (line in candidates) {
            val lower = line.lowercase()
            val words = lower.split(WHITESPACE).toSet()
            if (COMPANY_INDICATORS.any { lower.contains(it) } ||
                COMPANY_WORD_INDICATORS.any { it in words }) {
                companyName = line
                break
            }
        }

        // Name: check lines adjacent to the job title first (name+title co-locate on most cards)
        if (jobTitle.isNotBlank()) {
            val jobIdx = lines.indexOf(jobTitle)
            if (jobIdx >= 0) {
                outer@ for (offset in listOf(1, -1, 2, -2)) {
                    val i = jobIdx + offset
                    if (i < 0 || i >= lines.size) continue
                    val line = lines[i]
                    if (line == companyName) continue // don't pick the company as the name
                    if (skipLines.any { line.contains(it, ignoreCase = true) }) continue
                    if (line.matches(DIGIT_RUN_PATTERN) || line.trimEnd().endsWith(',')) continue
                    if (nameLikelihood(line) >= 2) {
                        personName = line
                        break@outer
                    }
                }
            }
        }

        // Fall back: prefer score-2 (2+ words) over score-1 (single word).
        // Among equal-scoring candidates, prefer the one with the larger bounding-box
        // height (bigger text on the card is more likely to be the person's name).
        if (personName.isEmpty()) {
            var bestScore = 0
            var bestHeight = 0
            for (line in candidates) {
                if (line == companyName) continue
                val score = nameLikelihood(line)
                val height = heightMap[line] ?: 0
                if (score > bestScore || (score == bestScore && score > 0 && height > bestHeight)) {
                    personName = line
                    bestScore = score
                    bestHeight = height
                }
            }
        }

        // Second pass: look for a run of 2+ consecutive all-caps short lines — multi-word brand
        // logos are often typeset one word per line in large uppercase text on physical cards.
        if (companyName.isEmpty()) {
            companyName = findAllCapsCluster(lines, skipLines, personName)
        }

        // Fallback company: first candidate that isn't the person name and isn't address-like
        if (companyName.isEmpty()) {
            for (line in candidates) {
                if (line == personName) continue
                if (line.length <= 3) continue
                val lower = line.lowercase()
                // Skip lines that look like street addresses
                if (line.any { it.isDigit() } && ADDRESS_WORDS.any { lower.contains(it) }) continue
                // Skip single-word taglines ending with a period (e.g. "Sustain.", "Innovate.")
                if (!line.contains(' ') && line.endsWith('.')) continue
                companyName = line
                break
            }
        }

        // Last resort: derive company from website or email domain
        if (companyName.isEmpty()) {
            val domain = when {
                website.isNotBlank() -> website
                    .substringAfter("://").substringBefore("/")
                    .removePrefix("www.").substringBefore(".")
                email.contains("@") -> email.substringAfter("@").substringBefore(".")
                else -> ""
            }
            if (domain.length > 2 && domain.lowercase() !in GENERIC_EMAIL_DOMAINS) {
                companyName = domain.replaceFirstChar { it.uppercase() }
            }
        }

        return Pair(personName, companyName)
    }

    // Returns 0 = not a name, 1 = possible (single word), 2 = likely (2+ words)
    private fun nameLikelihood(text: String): Int {
        val trimmed = text.trim()
        // Lines ending with comma or colon are address/label fragments, not names
        if (trimmed.endsWith(',') || trimmed.endsWith(':')) return 0
        // CJK names: checked first so the word-count guard below doesn't block names whose
        // characters OCR split into individual tokens (e.g. "张 三 李 四" → 4 words).
        val cjkCount = trimmed.count { CjkUtils.isCjk(it) }
        if (cjkCount in 1..6 && trimmed.all { it == ' ' || CjkUtils.isCjk(it) }) return 2
        // Latin/mixed names: 1–4 capitalised words containing only letters, hyphens, apostrophes
        val words = trimmed.split(WHITESPACE)
        if (words.size !in 1..4) return 0
        val allWordsValid = words.all { word ->
            val clean = word.trimEnd(',', '.', ';', ':')
            clean.isNotEmpty() &&
            clean[0].isUpperCase() &&
            clean.all { it.isLetter() || it == '-' || it == '\'' }
        }
        if (!allWordsValid) return 0
        // Multi-word names are far more reliable than single words (which could be taglines/places)
        return if (words.size >= 2) 2 else 1
    }

    private fun looksLikeName(text: String) = nameLikelihood(text) > 0
}
