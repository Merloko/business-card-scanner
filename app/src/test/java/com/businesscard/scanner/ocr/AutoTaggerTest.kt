package com.businesscard.scanner.ocr

import com.businesscard.scanner.data.BusinessCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaggerTest {

    private fun card(
        name: String = "",
        title: String = "",
        company: String = "",
        rawFront: String = "",
        rawBack: String = "",
        tags: String = ""
    ) = BusinessCard(
        personName = name,
        jobTitle = title,
        companyName = company,
        rawTextFront = rawFront,
        rawTextBack = rawBack,
        tags = tags
    )

    private fun tagsFor(vararg fields: Pair<String, String>): List<String> {
        var c = card()
        fields.forEach { (k, v) ->
            c = when (k) {
                "name"    -> c.copy(personName = v)
                "title"   -> c.copy(jobTitle = v)
                "company" -> c.copy(companyName = v)
                "front"   -> c.copy(rawTextFront = v)
                else      -> c
            }
        }
        return AutoTagger.suggestTags(c)
    }

    // ── executive ────────────────────────────────────────────────────────────
    @Test fun `CEO triggers executive tag`() = assertTrue("executive" in tagsFor("title" to "CEO"))
    @Test fun `founder triggers executive tag`() = assertTrue("executive" in tagsFor("title" to "Founder & CTO"))
    @Test fun `Managing Director triggers executive`() = assertTrue("executive" in tagsFor("title" to "Managing Director"))

    // ── tech ─────────────────────────────────────────────────────────────────
    @Test fun `software engineer triggers tech tag`() = assertTrue("tech" in tagsFor("title" to "Software Engineer"))
    @Test fun `developer in company name triggers tech`() = assertTrue("tech" in tagsFor("company" to "Acme Developer Tools"))
    @Test fun `devops triggers tech`() = assertTrue("tech" in tagsFor("title" to "DevOps Lead"))
    @Test fun `architect triggers tech`() = assertTrue("tech" in tagsFor("title" to "Solutions Architect"))

    // ── sales ─────────────────────────────────────────────────────────────────
    @Test fun `account executive triggers sales`() = assertTrue("sales" in tagsFor("title" to "Account Executive"))
    @Test fun `business development triggers sales`() = assertTrue("sales" in tagsFor("title" to "Business Development Manager"))
    @Test fun `sales director triggers sales`() = assertTrue("sales" in tagsFor("title" to "Sales Director"))

    // ── marketing ────────────────────────────────────────────────────────────
    @Test fun `marketing manager triggers marketing`() = assertTrue("marketing" in tagsFor("title" to "Marketing Manager"))
    @Test fun `public relations triggers marketing`() = assertTrue("marketing" in tagsFor("title" to "Public Relations Officer"))
    @Test fun `growth in title triggers marketing`() = assertTrue("marketing" in tagsFor("title" to "Growth Hacker"))

    // ── health ────────────────────────────────────────────────────────────────
    @Test fun `doctor triggers health`() = assertTrue("health" in tagsFor("title" to "Doctor"))
    @Test fun `MD credential triggers health`() = assertTrue("health" in tagsFor("title" to "Jane Smith MD"))
    @Test fun `hospital in company triggers health`() = assertTrue("health" in tagsFor("company" to "City Hospital"))
    @Test fun `dentist triggers health`() = assertTrue("health" in tagsFor("title" to "Dentist"))

    // ── legal ─────────────────────────────────────────────────────────────────
    @Test fun `lawyer triggers legal`() = assertTrue("legal" in tagsFor("title" to "Lawyer"))
    @Test fun `solicitor triggers legal`() = assertTrue("legal" in tagsFor("title" to "Senior Solicitor"))
    @Test fun `attorney in raw text triggers legal`() = assertTrue("legal" in tagsFor("front" to "Attorney at Law"))

    // ── finance ───────────────────────────────────────────────────────────────
    @Test fun `accountant triggers finance`() = assertTrue("finance" in tagsFor("title" to "Accountant"))
    @Test fun `CPA triggers finance`() = assertTrue("finance" in tagsFor("title" to "John Doe CPA"))
    @Test fun `financial advisor triggers finance`() = assertTrue("finance" in tagsFor("title" to "Financial Advisor"))

    // ── design ────────────────────────────────────────────────────────────────
    @Test fun `designer triggers design`() = assertTrue("design" in tagsFor("title" to "Product Designer"))
    @Test fun `UX in title triggers design`() = assertTrue("design" in tagsFor("title" to "UX Lead"))
    @Test fun `art director triggers design`() = assertTrue("design" in tagsFor("title" to "Art Director"))

    // ── education ─────────────────────────────────────────────────────────────
    @Test fun `professor triggers education`() = assertTrue("education" in tagsFor("title" to "Professor"))
    @Test fun `university in company triggers education`() = assertTrue("education" in tagsFor("company" to "State University"))
    @Test fun `researcher triggers education`() = assertTrue("education" in tagsFor("title" to "Senior Researcher"))

    // ── consulting ────────────────────────────────────────────────────────────
    @Test fun `consultant triggers consulting`() = assertTrue("consulting" in tagsFor("title" to "Management Consultant"))
    @Test fun `advisor triggers consulting`() = assertTrue("consulting" in tagsFor("title" to "Strategy Advisor"))

    // ── hr ────────────────────────────────────────────────────────────────────
    @Test fun `HR manager triggers hr`() = assertTrue("hr" in tagsFor("title" to "HR Manager"))
    @Test fun `recruiter triggers hr`() = assertTrue("hr" in tagsFor("title" to "Technical Recruiter"))
    @Test fun `talent acquisition triggers hr`() = assertTrue("hr" in tagsFor("title" to "Talent Acquisition Lead"))

    // ── leadership ────────────────────────────────────────────────────────────
    @Test fun `VP triggers leadership`() = assertTrue("leadership" in tagsFor("title" to "VP Engineering"))
    @Test fun `head of triggers leadership`() = assertTrue("leadership" in tagsFor("title" to "Head of Product"))
    @Test fun `SVP triggers leadership`() = assertTrue("leadership" in tagsFor("title" to "SVP Sales"))

    // ── real estate ───────────────────────────────────────────────────────────
    @Test fun `realtor triggers real estate`() = assertTrue("real estate" in tagsFor("title" to "Licensed Realtor"))
    @Test fun `property in company triggers real estate`() = assertTrue("real estate" in tagsFor("company" to "Premier Property Group"))
    @Test fun `mortgage broker triggers real estate`() = assertTrue("real estate" in tagsFor("title" to "Mortgage Broker"))

    // ── startup ───────────────────────────────────────────────────────────────
    @Test fun `startup triggers startup tag`() = assertTrue("startup" in tagsFor("company" to "Tech Startup Inc"))
    @Test fun `VC triggers startup`() = assertTrue("startup" in tagsFor("title" to "VC Partner"))
    @Test fun `angel investor triggers startup`() = assertTrue("startup" in tagsFor("title" to "Angel Investor"))

    // ── no false positives / existing tags skipped ───────────────────────────
    @Test fun `unrelated card gets no tags`() {
        assertTrue(tagsFor("name" to "John Smith", "title" to "Coordinator").isEmpty())
    }

    @Test fun `already tagged card is not re-tagged`() {
        val tags = AutoTagger.suggestTags(card(title = "Software Engineer", tags = "tech"))
        assertFalse("tech" in tags)
    }

    @Test fun `multiple rules can fire on one card`() {
        val tags = tagsFor("title" to "CTO and Founder", "company" to "FinTech Startup")
        assertTrue("executive" in tags)
        assertTrue("startup" in tags)
    }

    @Test fun `case insensitive matching`() {
        assertTrue("tech" in tagsFor("title" to "SENIOR SOFTWARE ENGINEER"))
        assertTrue("legal" in tagsFor("title" to "corporate LAWYER"))
    }

    @Test fun `OCR text in rawTextBack is scanned`() {
        val c = card(rawBack = "Attorney at Law, licensed in NSW")
        assertTrue("legal" in AutoTagger.suggestTags(c))
    }
}
