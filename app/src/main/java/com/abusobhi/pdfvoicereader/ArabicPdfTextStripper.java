package com.abusobhi.pdfvoicereader;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reconstructs Arabic PDF text in right-to-left visual order while retaining
 * explicit PDFBox spaces. Extra geometry-based spacing is restricted to the
 * lower footer region so it does not insert spaces inside words in the body.
 */
final class ArabicPdfTextStripper extends PDFTextStripper {
    private final StringBuilder output = new StringBuilder();
    private final StringBuilder sideHeadings = new StringBuilder();
    private boolean lastLineWasSideHeading;
    private final List<TextPosition> linePositions = new ArrayList<>();
    private static final float FOOTER_START_Y = 900.0f;

    private ArabicPdfTextStripper() throws IOException {
        super();
        setSortByPosition(true);
    }

    static String extractPage(PDDocument document, int pageNumber) throws IOException {
        if (document == null) throw new IllegalArgumentException("document must not be null");
        if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
            throw new IllegalArgumentException("pageNumber out of range");
        }
        ArabicPdfTextStripper stripper = new ArabicPdfTextStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        stripper.getText(document);
        return stripper.result();
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
        if (positions != null && !positions.isEmpty()) {
            linePositions.addAll(positions);
        }
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        flushLine();
        if (!lastLineWasSideHeading) output.append('\n');
        lastLineWasSideHeading = false;
    }

    @Override
    protected void writePageEnd() throws IOException {
        flushLine();
        super.writePageEnd();
    }

    private void flushLine() {
        if (linePositions.isEmpty()) return;

        boolean footerLine = false;
        int directionalPositions = 0;
        int verticalPositions = 0;
        for (TextPosition position : linePositions) {
            if (position.getYDirAdj() > FOOTER_START_Y) footerLine = true;
            String unicode = position.getUnicode();
            if (unicode == null || unicode.trim().isEmpty()) continue;
            directionalPositions++;
            float direction = (position.getDir() % 360f + 360f) % 360f;
            if ((direction >= 80f && direction <= 100f)
                    || (direction >= 260f && direction <= 280f)) {
                verticalPositions++;
            }
        }
        boolean sideHeadingLine = directionalPositions > 0
                && verticalPositions * 2 >= directionalPositions;

        final class Glyph {
            float x;
            float width;
            String unicode;

            Glyph(TextPosition position) {
                x = position.getXDirAdj();
                width = Math.max(0f, position.getWidthDirAdj());
                unicode = position.getUnicode();
            }
        }

        List<Glyph> glyphs = new ArrayList<>();
        List<TextPosition> marks = new ArrayList<>();
        for (TextPosition position : linePositions) {
            String unicode = position.getUnicode();
            if (unicode == null || unicode.isEmpty()) continue;
            if (isOnlyCombiningMarks(unicode)) marks.add(position);
            else glyphs.add(new Glyph(position));
        }

        // Attach detached Arabic diacritics to the nearest glyph center.
        for (TextPosition mark : marks) {
            if (glyphs.isEmpty()) {
                glyphs.add(new Glyph(mark));
                continue;
            }
            float markX = mark.getXDirAdj();
            Glyph nearest = null;
            float nearestDistance = Float.MAX_VALUE;
            for (Glyph glyph : glyphs) {
                if (glyph.unicode != null && glyph.unicode.trim().isEmpty()) continue;
                float center = glyph.x + glyph.width / 2f;
                float distance = Math.abs(markX - center);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = glyph;
                }
            }
            if (nearest != null) nearest.unicode += mark.getUnicode();
        }

        // Horizontal Arabic is read right-to-left. For rotated margin labels,
        // preserve PDFBox's character order instead of sorting by a nearly
        // constant X coordinate.
        if (!sideHeadingLine) {
            Collections.sort(glyphs, (a, b) -> Float.compare(b.x, a.x));
        } else {
            // PDFBox's extraction order for vertically rotated Arabic labels
            // can run opposite to natural reading order. Reverse the glyphs
            // so the spoken heading follows the intended Arabic sequence.
            Collections.reverse(glyphs);
        }

        StringBuilder lineOutput = new StringBuilder();
        float previousX = Float.NaN;
        float previousWidth = 0f;
        boolean haveGlyph = false;
        boolean previousWasSpace = false;

        for (Glyph glyph : glyphs) {
            if (glyph.unicode == null || glyph.unicode.isEmpty()) continue;

            boolean currentIsSpace = glyph.unicode.trim().isEmpty();
            if (haveGlyph && footerLine && !previousWasSpace && !currentIsSpace) {
                float gap = previousX - (glyph.x + glyph.width);
                if (gap > 20.0f) lineOutput.append(' ');
            }

            lineOutput.append(glyph.unicode);
            previousX = glyph.x;
            previousWidth = glyph.width;
            previousWasSpace = currentIsSpace;
            haveGlyph = true;
        }
        String lineText = restoreDigitOrder(lineOutput.toString().trim());
        if (sideHeadingLine && !lineText.isEmpty()) {
            if (sideHeadings.length() > 0) sideHeadings.append(' ');
            sideHeadings.append(lineText);
            lastLineWasSideHeading = true;
        } else {
            output.append(lineOutput);
            lastLineWasSideHeading = false;
        }
        linePositions.clear();
    }

    /**
     * Horizontal Arabic lines are assembled right-to-left, but digit sequences
     * are read left-to-right. Restore the original order inside each contiguous
     * run of Western, Arabic-Indic, or Eastern Arabic-Indic digits.
     */
    private static String restoreDigitOrder(String value) {
        StringBuilder result = new StringBuilder();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (isSupportedDigit(ch)) {
                digits.append(ch);
            } else {
                if (digits.length() > 0) {
                    result.append(digits.reverse());
                    digits.setLength(0);
                }
                result.append(ch);
            }
        }
        if (digits.length() > 0) result.append(digits.reverse());
        return result.toString();
    }

    private static boolean isSupportedDigit(char ch) {
        return (ch >= '0' && ch <= '9')
                || (ch >= '\\u0660' && ch <= '\\u0669')
                || (ch >= '\\u06F0' && ch <= '\\u06F9');
    }

    private static boolean isOnlyCombiningMarks(String value) {
        if (value == null || value.isEmpty()) return false;
        boolean foundMark = false;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            int type = Character.getType(codePoint);
            if (type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) {
                return false;
            }
            foundMark = true;
            i += Character.charCount(codePoint);
        }
        return foundMark;
    }

    private String result() {
        flushLine();
        if (sideHeadings.length() == 0) return output.toString();
        return sideHeadings.toString().trim() + "\n" + output.toString();
    }
}
