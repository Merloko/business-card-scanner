package com.businesscard.scanner.ocr

import com.businesscard.scanner.data.BusinessCard

object AutoTagger {

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(ceo|coo|cto|cfo|president|founder|owner|managing director|director general)\\b") to "executive",
        Regex("(?i)\\b(developer|engineer|programmer|software|architect|devops|sre|fullstack|backend|frontend|mobile dev)\\b") to "tech",
        Regex("(?i)\\b(sales|account executive|business development|bdm|account manager|sales director)\\b") to "sales",
        Regex("(?i)\\b(marketing|brand|communications|\\bpr\\b|public relations|social media|growth)\\b") to "marketing",
        Regex("(?i)\\b(doctor|physician|\\bdr\\.\\b|\\bmd\\b|nurse|health|medical|clinic|hospital|dentist)\\b") to "health",
        Regex("(?i)\\b(lawyer|attorney|solicitor|barrister|counsel|legal|notary)\\b") to "legal",
        Regex("(?i)\\b(finance|\\bcpa\\b|accountant|auditor|banking|investment|financial advisor|wealth)\\b") to "finance",
        Regex("(?i)\\b(designer|\\bux\\b|\\bui\\b|creative|art director|illustrator|graphic design)\\b") to "design",
        Regex("(?i)\\b(professor|lecturer|teacher|researcher|academic|university|college|school|educator)\\b") to "education",
        Regex("(?i)\\b(consultant|advisory|strategy|management consulting|advisor)\\b") to "consulting",
        Regex("(?i)\\b(\\bhr\\b|human resources|recruiter|talent acquisition|people operations|staffing)\\b") to "hr",
        Regex("(?i)\\b(\\bvp\\b|vice president|\\bsvp\\b|\\bevp\\b|director of|head of)\\b") to "leadership",
        Regex("(?i)\\b(real estate|realtor|property|mortgage|broker|agent)\\b") to "real estate",
        Regex("(?i)\\b(startup|venture|\\bvc\\b|angel investor|accelerator|incubator)\\b") to "startup",
    )

    fun suggestTags(card: BusinessCard): List<String> {
        val text = "${card.personName} ${card.jobTitle} ${card.companyName} ${card.rawTextFront} ${card.rawTextBack}"
        val existing = card.tags.split(",").map { it.trim().lowercase() }.toSet()
        return rules
            .filter { (regex, tag) -> regex.containsMatchIn(text) && tag !in existing }
            .map { (_, tag) -> tag }
            .distinct()
    }
}
