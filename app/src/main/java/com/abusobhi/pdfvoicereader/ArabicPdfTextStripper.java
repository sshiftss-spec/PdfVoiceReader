package com.abusobhi.pdfvoicereader;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * Uses PDFBox's built-in text reconstruction and bidirectional normalization.
 *
 * Do not rebuild Arabic by sorting individual glyphs by X coordinate: Arabic
 * glyphs can be stored in visual/presentation order, while the speech engine
 * expects logical Unicode order. PDFTextStripper already handles RTL line
 * normalization and text-position sorting.
 */
final class ArabicPdfTextStripper {
    private ArabicPdfTextStripper() {}

    static String extractPage(PDDocument document, int pageNumber) throws IOException {
        if (document == null) throw new IllegalArgumentException("document must not be null");
        if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
            throw new IllegalArgumentException("pageNumber out of range");
        }

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        return stripper.getText(document);
    }
}
