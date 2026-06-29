package com.businesscard.scanner.ocr

/**
 * A single line of OCR output with its plain text and the bounding-box
 * height of that line in the source image (pixels).
 *
 * heightPx is a reliable proxy for font size: company logos are typeset
 * at large point sizes and produce tall bounding boxes; contact details
 * (phone, email) are small and produce short ones. topPx preserves the
 * original reading order when lines are merged across recognisers.
 */
data class OcrLine(
    val text: String,
    val heightPx: Int,
    val topPx: Int
)
