package com.businesscard.scanner.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkUtilsTest {

    // ── isCjk: CJK Unified Ideographs (0x4E00–0x9FFF) ──

    @Test fun `CJK Unified - first codepoint`() = assertTrue(CjkUtils.isCjk('一'))
    @Test fun `CJK Unified - last codepoint`() = assertTrue(CjkUtils.isCjk('鿿'))
    @Test fun `CJK Unified - common char 张`() = assertTrue(CjkUtils.isCjk('张'))
    @Test fun `CJK Unified - before block is false`() = assertFalse(CjkUtils.isCjk('䷿'))
    @Test fun `CJK Unified - after block is false`() = assertFalse(CjkUtils.isCjk('ꀀ'))

    // ── isCjk: CJK Extension A (0x3400–0x4DBF) ──

    @Test fun `CJK Extension A - first codepoint`() = assertTrue(CjkUtils.isCjk('㐀'))
    @Test fun `CJK Extension A - last codepoint`() = assertTrue(CjkUtils.isCjk('䶿'))
    @Test fun `CJK Extension A - before block is false`() = assertFalse(CjkUtils.isCjk('㏿'))

    // ── isCjk: Hiragana (0x3040–0x309F) ──

    @Test fun `Hiragana - first codepoint`() = assertTrue(CjkUtils.isCjk('぀'))
    @Test fun `Hiragana - last codepoint`() = assertTrue(CjkUtils.isCjk('ゟ'))
    @Test fun `Hiragana - あ`() = assertTrue(CjkUtils.isCjk('あ'))
    @Test fun `Hiragana - ん`() = assertTrue(CjkUtils.isCjk('ん'))
    @Test fun `Hiragana - before block is false`() = assertFalse(CjkUtils.isCjk('〿'))

    // ── isCjk: Katakana fullwidth (0x30A0–0x30FF) ──

    @Test fun `Katakana - first codepoint`() = assertTrue(CjkUtils.isCjk('゠'))
    @Test fun `Katakana - last codepoint`() = assertTrue(CjkUtils.isCjk('ヿ'))
    @Test fun `Katakana - ア`() = assertTrue(CjkUtils.isCjk('ア'))
    @Test fun `Katakana - before block is false`() = assertFalse(CjkUtils.isCjk('ゟ').not())

    // ── isCjk: Halfwidth Katakana (0xFF65–0xFF9F) — range added in this PR ──

    @Test fun `Halfwidth Katakana - first codepoint`() = assertTrue(CjkUtils.isCjk('･'))
    @Test fun `Halfwidth Katakana - last codepoint`() = assertTrue(CjkUtils.isCjk('ﾟ'))
    @Test fun `Halfwidth Katakana - ｱ`() = assertTrue(CjkUtils.isCjk('ｱ'))   // halfwidth ア
    @Test fun `Halfwidth Katakana - ﾝ`() = assertTrue(CjkUtils.isCjk('ﾝ'))   // halfwidth ン
    @Test fun `Halfwidth Katakana - before block is false`() = assertFalse(CjkUtils.isCjk('､'))
    @Test fun `Halfwidth Katakana - after block is false`() = assertFalse(CjkUtils.isCjk('ﾠ'))

    // ── isCjk: non-CJK characters ──

    @Test fun `Latin uppercase not CJK`() = assertFalse(CjkUtils.isCjk('A'))
    @Test fun `Latin lowercase not CJK`() = assertFalse(CjkUtils.isCjk('z'))
    @Test fun `Digit not CJK`() = assertFalse(CjkUtils.isCjk('5'))
    @Test fun `Space not CJK`() = assertFalse(CjkUtils.isCjk(' '))
    @Test fun `At-sign not CJK`() = assertFalse(CjkUtils.isCjk('@'))

    // ── containsCjk ──

    @Test fun `containsCjk - pure CJK name`() = assertTrue(CjkUtils.containsCjk("张三丰"))
    @Test fun `containsCjk - hiragana`() = assertTrue(CjkUtils.containsCjk("こんにちは"))
    @Test fun `containsCjk - katakana`() = assertTrue(CjkUtils.containsCjk("アイウ"))
    @Test fun `containsCjk - halfwidth Katakana`() = assertTrue(CjkUtils.containsCjk("ｱｲｳ"))
    @Test fun `containsCjk - mixed with Latin`() = assertTrue(CjkUtils.containsCjk("John 张"))
    @Test fun `containsCjk - pure Latin false`() = assertFalse(CjkUtils.containsCjk("John Smith"))
    @Test fun `containsCjk - digits false`() = assertFalse(CjkUtils.containsCjk("12345"))
    @Test fun `containsCjk - empty string false`() = assertFalse(CjkUtils.containsCjk(""))
}
