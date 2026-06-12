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
        "designer", "analyst", "consultant", "president", "founder", "owner",
        "sales", "marketing", "account", "executive", "officer", "head of",
        "senior", "junior", "lead", "principal", "associate", "coordinator",
        "specialist", "representative", "advisor", "partner", "vice president", "vp"
    )

    private val COMPANY_INDICATORS = listOf(
        // English / Australian
        "pty", "ltd", "limited", "inc", "corp", "corporation", "llc", "co.",
        "group", "solutions", "services", "technologies", "consulting", "enterprises",
        "holdings", "international", "global", "australia", "pty ltd",
        // Chinese (Simplified & Traditional)
        "有限公司", "股份有限公司", "集团", "企业", "贸易", "科技",
        // Japanese
        "株式会社", "有限会社", "合同会社", "合資会社"
    )

    fun parse(frontText: String, backText: String = ""): ParsedContact {
        val allText = "$frontText\n$backText"
        val lines = allText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val email = extractEmail(allText)
        val (phone, mobile) = extractPhones(allText)
        val website = extractWebsite(allText, email)
        val address = extractAddress(lines)
        val jobTitle = extractJobTitle(lines)
        val allPhones = (phone.lines() + mobile.lines()).filter { it.isNotBlank() }
        val (personName, companyName) = extractNameAndCompany(lines, email, allPhones, website, jobTitle, address)

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

    // Erase ABN, ACN, BSB, TFN and similar AU business identifiers before phone matching
    private fun stripBusinessIdentifiers(text: String): String = text
        .replace(ABN_PATTERN, "")
        .replace(ACN_PATTERN, "")
        .replace(BSB_PATTERN, "")
        .replace(TFN_PATTERN, "")

    // Strips trailing text annotations like "(AUS)", "(FAX)" after the last digit
    private fun trimToLastDigit(s: String): String {
        val last = s.indexOfLast { it.isDigit() }
        return if (last >= 0) s.substring(0, last + 1).trim() else s
    }

    // Accepts 7-8 digits (local AU), 10 (AU with area code / mobile), 11 (+61...), 12-15 (other international)
    private fun isValidPhoneDigitCount(count: Int) = count in 7..8 || count in 10..15

    // Australian mobile: local 04xx or international +614xx
    private fun isMobile(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return digits.startsWith("04") || digits.startsWith("614")
    }

    private fun extractWebsite(text: String, email: String): String {
        val matcher = WEBSITE_PATTERN.matcher(text)
        while (matcher.find()) {
            val match = matcher.group()
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

    private fun extractJobTitle(lines: List<String>): String {
        for (line in lines) {
            val lower = line.lowercase()
            if (JOB_TITLE_KEYWORDS.any { lower.contains(it) } && line.length < 60) {
                return line
            }
        }
        return ""
    }

    private fun extractNameAndCompany(
        lines: List<String>,
        email: String,
        phones: List<String>,
        website: String,
        jobTitle: String,
        address: String
    ): Pair<String, String> {
        val skipLines = (setOf(email, website, jobTitle, address) + phones)
            .filter { it.isNotBlank() }

        val candidates = lines.filter { line ->
            skipLines.none { line.contains(it, ignoreCase = true) } &&
            !line.matches(DIGIT_RUN_PATTERN) &&
            line.length in 2..60
        }

        var personName = ""
        var companyName = ""

        for (line in candidates) {
            val lower = line.lowercase()
            if (companyName.isEmpty() && COMPANY_INDICATORS.any { lower.contains(it) }) {
                companyName = line
                continue
            }
            if (personName.isEmpty() && looksLikeName(line)) {
                personName = line
                continue
            }
            if (companyName.isEmpty() && line.length > 3) {
                companyName = line
            }
        }

        return Pair(personName, companyName)
    }

    private fun looksLikeName(text: String): Boolean {
        val trimmed = text.trim()
        // CJK names: checked first so the word-count guard below doesn't block names whose
        // characters OCR split into individual tokens (e.g. "张 三 李 四" → 4 words).
        val cjkCount = trimmed.count { CjkUtils.isCjk(it) }
        if (cjkCount in 2..6 && trimmed.all { it == ' ' || CjkUtils.isCjk(it) }) return true
        // Latin/mixed names: 1–4 capitalised words
        val words = trimmed.split(WHITESPACE)
        if (words.size !in 1..4) return false
        return words.all { word ->
            // Strip trailing punctuation (e.g. "ANDRICH," → "ANDRICH")
            val clean = word.trimEnd(',', '.', ';', ':')
            clean.isNotEmpty() &&
            clean[0].isUpperCase() &&
            clean.all { it.isLetter() || it == '-' || it == '\'' }
        }
    }
}
