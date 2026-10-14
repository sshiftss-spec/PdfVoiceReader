
package com.abusobhi.pdfboxexacttest;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_PDF = 41;
    private TextView output;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        PDFBoxResourceLoader.init(getApplicationContext());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        Button choose = new Button(this);
        choose.setText("اختر ملف الأربعين النووية PDF");
        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/pdf");
            startActivityForResult(i, PICK_PDF);
        });
        root.addView(choose,
                new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText(
                "اختبار PDFBox معزول: مقارنة الاستخراج " +
                "وفحص رموز الحروف ومواضعها والخطوط. " +
                "لا يصلح النص تلقائيًا."
        );
        root.addView(note,
                new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(16);
        output.setPadding(0, 20, 0, 20);
        scroll.addView(output);

        root.addView(scroll,
                new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_PDF ||
                resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        output.setText("جارٍ فحص الصفحة الرابعة...");

        new Thread(() -> {
            String result;

            try (InputStream in =
                         getContentResolver().openInputStream(uri);
                 PDDocument doc =
                         in == null ? null : PDDocument.load(in)) {

                if (doc == null) {
                    throw new IllegalStateException(
                            "تعذر فتح ملف PDF");
                }

                if (doc.getNumberOfPages() < 4) {
                    throw new IllegalStateException(
                            "الملف أقل من أربع صفحات");
                }

                StringBuilder all = new StringBuilder();
                all.append("PDFBox Android: 2.0.27.0\n")
                   .append("Pages: ")
                   .append(doc.getNumberOfPages())
                   .append("\n");

                all.append(
                        "\n========== 1: RAW DEFAULT ==========\n");
                all.append(extract(doc, false, 0.5f));

                all.append(
                        "\n========== 2: SORT BY POSITION ==========\n");
                all.append(extract(doc, true, 0.5f));

                all.append(
                        "\n========== 3: SORT + TOLERANCE 0.2 ==========\n");
                all.append(extract(doc, true, 0.2f));

                all.append(
                        "\n========== 4: CHARACTER DIAGNOSTICS ==========\n");
                all.append(diagnose(doc));

                all.append(
                        "\n========== 5A: POSITION RTL, GAP 0.25 ==========\n");
                all.append(extractPositionRtl(doc, 0.25f));

                all.append(
                        "\n========== 5B: POSITION RTL, GAP 0.45 ==========\n");
                all.append(extractPositionRtl(doc, 0.45f));

                all.append(
                        "\n========== 5C: POSITION RTL, GAP 0.70 ==========\n");
                all.append(extractPositionRtl(doc, 0.70f));

                all.append("\n========== 5D: POSITION RTL, WHOLE LINE ==========\n");
                all.append(extractPositionRtl(doc, 0.35f));

                all.append("\n========== 5E: CENTER-BASED DIACRITIC ATTACHMENT ==========\n");
                all.append(extractPositionRtlCenterMarks(doc, 0.35f));

                all.append("\n========== 6: ADJACENT GLYPH GAP DIAGNOSTICS ==========\n");
                all.append(diagnoseGaps(doc));

                all.append("\n========== 7: PDFBOX-STYLE RTL THRESHOLD DISAGREEMENTS ==========\n");
                all.append(diagnosePdfBoxThreshold(doc));

                all.append("\n========== 8: PDFBOX DEFAULT WORD-BOUNDARY CHECK ==========\n");
                all.append(diagnosePdfBoxWordBoundaries(doc));

                result = all.toString();

            } catch (Throwable e) {
                result = "TEST FAILED\n"
                        + e.getClass().getName()
                        + ": " + e.getMessage();
            }

            final String shown = result;
            runOnUiThread(() -> output.setText(shown));

        }).start();
    }

    private String extract(
            PDDocument doc,
            boolean sortByPosition,
            float spacingTolerance) throws Exception {

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(sortByPosition);
        stripper.setSpacingTolerance(spacingTolerance);

        return stripper.getText(doc);
    }

    private String extractPositionRtl(
            PDDocument doc, float gapFactor) throws Exception {
        PositionRtlStripper stripper = new PositionRtlStripper(gapFactor);
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(true);
        stripper.getText(doc);
        return stripper.result();
    }

    private String extractPositionRtlCenterMarks(
            PDDocument doc, float gapFactor) throws Exception {
        PositionRtlStripper stripper =
                new PositionRtlStripper(gapFactor, true);
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(true);
        stripper.getText(doc);
        return stripper.result();
    }

    private String diagnose(PDDocument doc) throws Exception {
        DiagnosticStripper stripper = new DiagnosticStripper();
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(false);
        stripper.getText(doc);
        return stripper.report();
    }

    /*
     * Experimental only: collect every TextPosition in a complete PDFBox line,
     * then reconstruct that line by descending X. Sorting each writeString()
     * fragment separately incorrectly split one visual line into many lines.
     */
    private static class PositionRtlStripper extends PDFTextStripper {
        private final StringBuilder out = new StringBuilder();
        private final List<TextPosition> linePositions = new ArrayList<>();
        private final float gapFactor;
        private final boolean centerBasedMarks;

        PositionRtlStripper(float gapFactor) throws IOException {
            this(gapFactor, false);
        }

        PositionRtlStripper(
                float gapFactor, boolean centerBasedMarks) throws IOException {
            super();
            this.gapFactor = gapFactor;
            this.centerBasedMarks = centerBasedMarks;
        }

        @Override
        protected void writeString(
                String text, List<TextPosition> textPositions)
                throws IOException {
            if (textPositions != null && !textPositions.isEmpty()) {
                linePositions.addAll(textPositions);
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
            out.append('\n');
        }

        @Override
        protected void writePageEnd() throws IOException {
            flushLine();
            super.writePageEnd();
        }

        private void flushLine() {
            if (linePositions.isEmpty()) {
                return;
            }

            class Glyph {
                float x;
                float width;
                String unicode;

                Glyph(TextPosition p) {
                    x = p.getXDirAdj();
                    width = Math.max(0f, p.getWidthDirAdj());
                    unicode = p.getUnicode();
                }
            }

            List<Glyph> glyphs = new ArrayList<>();
            List<TextPosition> marks = new ArrayList<>();

            for (TextPosition p : linePositions) {
                String u = p.getUnicode();
                if (u == null || u.isEmpty() || u.trim().isEmpty()) {
                    continue;
                }
                if (isOnlyCombiningMarks(u)) {
                    marks.add(p);
                } else {
                    glyphs.add(new Glyph(p));
                }
            }

            // Attach zero-width Arabic diacritics to their nearest glyph.
            for (TextPosition mark : marks) {
                if (glyphs.isEmpty()) {
                    glyphs.add(new Glyph(mark));
                    continue;
                }
                float mx = mark.getXDirAdj();
                Glyph best = null;
                float bestDistance = Float.MAX_VALUE;
                for (Glyph g : glyphs) {
                    float distance;
                    if (centerBasedMarks) {
                        // A mark can lie inside more than one overlapping glyph
                        // box. Edge-distance then ties at zero and picks the
                        // first glyph, so use the nearest glyph center instead.
                        float center = g.x + (g.width / 2f);
                        distance = Math.abs(mx - center);
                    } else {
                        float right = g.x + g.width;
                        distance = mx < g.x ? g.x - mx
                                : (mx > right ? mx - right : 0f);
                    }
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = g;
                    }
                }
                if (best != null) {
                    best.unicode += mark.getUnicode();
                }
            }

            Collections.sort(glyphs,
                    (a, b) -> Float.compare(b.x, a.x));

            float previousX = Float.NaN;
            float previousWidth = 0f;
            boolean haveGlyph = false;

            for (Glyph g : glyphs) {
                if (g.unicode == null || g.unicode.isEmpty()) {
                    continue;
                }
                if (haveGlyph) {
                    float gap = previousX - (g.x + g.width);
                    float referenceWidth = Math.max(
                            1.0f, Math.min(previousWidth, g.width));
                    if (gap > Math.max(2.0f, referenceWidth * gapFactor)) {
                        out.append(' ');
                    }
                }
                out.append(g.unicode);
                previousX = g.x;
                previousWidth = g.width;
                haveGlyph = true;
            }
            linePositions.clear();
        }

        private static boolean isOnlyCombiningMarks(String value) {
            if (value == null || value.isEmpty()) {
                return false;
            }
            boolean foundMark = false;
            for (int i = 0; i < value.length();) {
                int cp = value.codePointAt(i);
                int type = Character.getType(cp);
                if (type != Character.NON_SPACING_MARK
                        && type != Character.COMBINING_SPACING_MARK
                        && type != Character.ENCLOSING_MARK) {
                    return false;
                }
                foundMark = true;
                i += Character.charCount(cp);
            }
            return foundMark;
        }

        String result() {
            flushLine();
            return out.toString();
        }
    }

    /*
     * Diagnostic only. Reports the geometry of adjacent glyphs on each PDFBox
     * line, comparing the current fixed-width rule with a PDFBox-inspired
     * threshold. It does not change the extracted text or choose a new rule.
     */
    private String diagnosePdfBoxThreshold(PDDocument doc) throws Exception {
        PdfBoxThresholdStripper stripper = new PdfBoxThresholdStripper();
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(true);
        stripper.getText(doc);
        return stripper.report();
    }

    private String diagnoseGaps(PDDocument doc) throws Exception {
        GapDiagnosticStripper stripper = new GapDiagnosticStripper();
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(true);
        stripper.getText(doc);
        return stripper.report();
    }

    private static class GapDiagnosticStripper extends PDFTextStripper {
        private static final int MAX_PAIRS = 220;
        private final StringBuilder report = new StringBuilder();
        private final List<TextPosition> line = new ArrayList<>();
        private int pairCount;
        private int printedPairs;
        private int lineCount;

        GapDiagnosticStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions)
                throws IOException {
            if (positions != null) {
                line.addAll(positions);
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
            report.append("\n");
        }

        @Override
        protected void writePageEnd() throws IOException {
            flushLine();
            super.writePageEnd();
        }

        private void flushLine() {
            if (line.isEmpty()) return;

            List<TextPosition> glyphs = new ArrayList<>();
            for (TextPosition p : line) {
                String u = p.getUnicode();
                if (u == null || u.trim().isEmpty()
                        || isOnlyMarks(u)) continue;
                glyphs.add(p);
            }
            Collections.sort(glyphs, (a, b) ->
                    Float.compare(b.getXDirAdj(), a.getXDirAdj()));

            if (glyphs.size() >= 2) {
                lineCount++;
                report.append(String.format(Locale.US,
                        "\nLINE %03d glyphs=%d\n", lineCount, glyphs.size()));
                for (int i = 0; i + 1 < glyphs.size(); i++) {
                    TextPosition right = glyphs.get(i);
                    TextPosition left = glyphs.get(i + 1);

                    float rx = right.getXDirAdj();
                    float rw = Math.max(0f, right.getWidthDirAdj());
                    float lx = left.getXDirAdj();
                    float lw = Math.max(0f, left.getWidthDirAdj());
                    // RTL: distance from the right edge of the left glyph
                    // to the starting edge of the glyph immediately to its right.
                    float gap = rx - (lx + lw);
                    float fixedThreshold = Math.max(2f,
                            Math.min(Math.max(0f, rw), Math.max(0f, lw)) * 0.35f);

                    float rightSpace = right.getWidthOfSpace();
                    float leftSpace = left.getWidthOfSpace();
                    float space = validPositive(rightSpace) && validPositive(leftSpace)
                            ? (rightSpace + leftSpace) / 2f
                            : (validPositive(rightSpace) ? rightSpace : leftSpace);
                    int rightCount = Math.max(1, right.getUnicode().codePointCount(
                            0, right.getUnicode().length()));
                    int leftCount = Math.max(1, left.getUnicode().codePointCount(
                            0, left.getUnicode().length()));
                    float averageChar = ((rw / rightCount) + (lw / leftCount)) / 2f;
                    float pdfLikeThreshold = validPositive(space)
                            ? Math.min(space * 0.5f, averageChar * 0.3f)
                            : averageChar * 0.3f;

                    boolean fixedAdds = gap > fixedThreshold;
                    boolean pdfLikeAdds = gap > pdfLikeThreshold;
                    pairCount++;

                    // Keep the report useful: print pairs near a possible boundary,
                    // and all cases where the two candidate rules disagree.
                    if (printedPairs < MAX_PAIRS
                            && (gap > -1.0f || fixedAdds != pdfLikeAdds)) {
                        report.append(String.format(Locale.US,
                                "R=%s[x=%.1f,w=%.1f,sp=%.1f] "
                                + "L=%s[x=%.1f,w=%.1f,sp=%.1f] "
                                + "gap=%.2f fixed35=%.2f:%s pdfLike=%.2f:%s\n",
                                describe(right.getUnicode()), rx, rw, rightSpace,
                                describe(left.getUnicode()), lx, lw, leftSpace,
                                gap, fixedThreshold, fixedAdds ? "SPACE" : "JOIN",
                                pdfLikeThreshold, pdfLikeAdds ? "SPACE" : "JOIN"));
                        printedPairs++;
                    }
                }
            }
            line.clear();
        }

        private static boolean validPositive(float value) {
            return value > 0f && !Float.isNaN(value)
                    && !Float.isInfinite(value);
        }

        private static boolean isOnlyMarks(String value) {
            if (value == null || value.isEmpty()) return false;
            for (int i = 0; i < value.length();) {
                int cp = value.codePointAt(i);
                int type = Character.getType(cp);
                if (type != Character.NON_SPACING_MARK
                        && type != Character.COMBINING_SPACING_MARK
                        && type != Character.ENCLOSING_MARK) return false;
                i += Character.charCount(cp);
            }
            return true;
        }

        private static String describe(String value) {
            if (value == null) return "(null)";
            StringBuilder codes = new StringBuilder();
            value.codePoints().forEach(cp -> {
                if (codes.length() > 0) codes.append(',');
                codes.append(String.format(Locale.US, "U+%04X", cp));
            });
            return "'" + value.replace("\n", "\\n") + "'[" + codes + "]";
        }

        String report() {
            flushLine();
            report.append("\nLines analysed: ").append(lineCount)
                    .append("\nAdjacent pairs analysed: ").append(pairCount)
                    .append("\nPairs shown: ").append(printedPairs)
                    .append(" (maximum ").append(MAX_PAIRS).append(")");
            report.append("\nNOTE: pdfLike is diagnostic only; no text rule changed.");
            return report.toString();
        }
    }

    /*
     * Diagnostic only: records explicit spaces inserted by PDFTextStripper
     * between adjacent TextPositions in its emitted writeString text.
     * A writeString callback may contain multiple words, so callback identity
     * is NOT treated as word identity.
     */
    private String diagnosePdfBoxWordBoundaries(PDDocument doc) throws Exception {
        PdfBoxWordBoundaryStripper stripper = new PdfBoxWordBoundaryStripper();
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(true);
        stripper.getText(doc);
        return stripper.report();
    }

    private static class PdfBoxWordBoundaryStripper extends PDFTextStripper {
        private static final int MAX_PRINTED = 100;
        private final StringBuilder report = new StringBuilder();
        private final List<TextPosition> line = new ArrayList<>();
        private final Map<TextPosition, Set<TextPosition>> explicitSpacePairs =
                new IdentityHashMap<>();
        private int pairCount;
        private int printedCount;
        private int explicitSpaceCount;
        private int noSpaceCount;

        PdfBoxWordBoundaryStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions)
                throws IOException {
            if (positions == null || positions.isEmpty()) return;
            line.addAll(positions);

            // Align each glyph's Unicode with the actual string emitted by
            // PDFTextStripper. Whitespace between consecutive glyphs is the
            // evidence; writeString callback boundaries alone are not.
            String emitted = text == null ? "" : text;
            int cursor = 0;
            TextPosition previous = null;
            for (TextPosition current : positions) {
                String unicode = current.getUnicode();
                if (unicode == null || unicode.isEmpty()) continue;
                int at = emitted.indexOf(unicode, cursor);
                if (at < 0) {
                    previous = current;
                    continue;
                }
                if (previous != null && at > cursor
                        && containsWhitespace(emitted.substring(cursor, at))) {
                    addExplicitSpacePair(previous, current);
                }
                cursor = at + unicode.length();
                previous = current;
            }
        }

        private static boolean containsWhitespace(String value) {
            for (int i = 0; i < value.length(); i++) {
                if (Character.isWhitespace(value.charAt(i))) return true;
            }
            return false;
        }

        private void addExplicitSpacePair(TextPosition a, TextPosition b) {
            Set<TextPosition> aSet = explicitSpacePairs.get(a);
            if (aSet == null) {
                aSet = Collections.newSetFromMap(new IdentityHashMap<TextPosition, Boolean>());
                explicitSpacePairs.put(a, aSet);
            }
            aSet.add(b);
            Set<TextPosition> bSet = explicitSpacePairs.get(b);
            if (bSet == null) {
                bSet = Collections.newSetFromMap(new IdentityHashMap<TextPosition, Boolean>());
                explicitSpacePairs.put(b, bSet);
            }
            bSet.add(a);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
            report.append("\n");
        }

        @Override
        protected void writePageEnd() throws IOException {
            flushLine();
            super.writePageEnd();
        }

        private void flushLine() {
            if (line.isEmpty()) return;
            List<TextPosition> glyphs = new ArrayList<>(line);
            Collections.sort(glyphs, (a, b) ->
                    Float.compare(b.getXDirAdj(), a.getXDirAdj()));

            for (int i = 0; i + 1 < glyphs.size(); i++) {
                TextPosition right = glyphs.get(i);
                TextPosition left = glyphs.get(i + 1);
                float gap = right.getXDirAdj()
                        - (left.getXDirAdj() + Math.max(0f, left.getWidthDirAdj()));
                Set<TextPosition> neighbors = explicitSpacePairs.get(right);
                boolean hasOutputSpace = neighbors != null && neighbors.contains(left);
                pairCount++;
                if (hasOutputSpace) explicitSpaceCount++;
                else noSpaceCount++;

                if (printedCount < MAX_PRINTED && (gap > 2.0f || hasOutputSpace)) {
                    report.append(String.format(Locale.US,
                            "R=%s[x=%.1f,w=%.1f] L=%s[x=%.1f,w=%.1f] "
                            + "gap=%.2f PDFBoxOutputSpace=%s\\n",
                            GapDiagnosticStripper.describe(right.getUnicode()),
                            right.getXDirAdj(), right.getWidthDirAdj(),
                            GapDiagnosticStripper.describe(left.getUnicode()),
                            left.getXDirAdj(), left.getWidthDirAdj(),
                            gap, hasOutputSpace ? "YES" : "NO"));
                    printedCount++;
                }
            }
            line.clear();
            explicitSpacePairs.clear();
        }

        String report() {
            flushLine();
            report.append("\nAdjacent pairs checked: ").append(pairCount)
                    .append("\nPairs with explicit PDFBox output space: ").append(explicitSpaceCount)
                    .append("\nPairs without explicit PDFBox output space: ").append(noSpaceCount)
                    .append("\nPairs shown: ").append(printedCount)
                    .append(" (maximum ").append(MAX_PRINTED).append(")")
                    .append("\nNOTE: checks emitted whitespace, not writeString grouping; diagnostic only.");
            return report.toString();
        }
    }

    /*
     * Diagnostic-only comparison using PDFTextStripper's two threshold ideas
     * adapted to descending-X RTL order. This does not change sections 1-6.
     */
    private static class PdfBoxThresholdStripper extends PDFTextStripper {
        private final StringBuilder report = new StringBuilder();
        private final List<TextPosition> line = new ArrayList<>();
        private int pairCount;
        private int disagreementCount;
        private int printedCount;
        private static final int MAX_PRINTED = 180;

        PdfBoxThresholdStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions)
                throws IOException {
            if (positions != null) line.addAll(positions);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
        }

        @Override
        protected void writePageEnd() throws IOException {
            flushLine();
            super.writePageEnd();
        }

        private void flushLine() {
            if (line.isEmpty()) return;
            List<TextPosition> glyphs = new ArrayList<>();
            for (TextPosition p : line) {
                String u = p.getUnicode();
                if (u == null || u.trim().isEmpty() || GapDiagnosticStripper.isOnlyMarks(u)) {
                    continue;
                }
                glyphs.add(p);
            }
            Collections.sort(glyphs, (a, b) ->
                    Float.compare(b.getXDirAdj(), a.getXDirAdj()));

            float previousAverageCharWidth = -1f;
            float previousSpaceWidth = -1f;
            for (int i = 0; i + 1 < glyphs.size(); i++) {
                TextPosition right = glyphs.get(i);
                TextPosition left = glyphs.get(i + 1);
                float gap = right.getXDirAdj()
                        - (left.getXDirAdj() + Math.max(0f, left.getWidthDirAdj()));
                float rw = Math.max(0f, right.getWidthDirAdj());
                float lw = Math.max(0f, left.getWidthDirAdj());
                float fixedThreshold = Math.max(2f, Math.min(rw, lw) * 0.35f);

                int count = left.getIndividualWidths() == null
                        ? 0 : left.getIndividualWidths().length;
                if (count <= 0) {
                    String u = left.getUnicode();
                    count = (u == null || u.isEmpty()) ? 1
                            : Math.max(1, u.codePointCount(0, u.length()));
                }
                float currentAverage = lw / count;
                float averageCharWidth = previousAverageCharWidth < 0f
                        ? currentAverage
                        : (previousAverageCharWidth + currentAverage) / 2f;
                float deltaChar = averageCharWidth * 0.3f;

                float currentSpace = left.getWidthOfSpace();
                float deltaSpace;
                if (!(currentSpace > 0f) || Float.isNaN(currentSpace)) {
                    deltaSpace = Float.MAX_VALUE;
                } else if (!(previousSpaceWidth > 0f)
                        || Float.isNaN(previousSpaceWidth)) {
                    deltaSpace = currentSpace * 0.5f;
                } else {
                    deltaSpace = (currentSpace + previousSpaceWidth) / 2f * 0.5f;
                }
                float pdfBoxThreshold = Math.min(deltaSpace, deltaChar);

                boolean fixedSpace = gap > fixedThreshold;
                boolean pdfBoxSpace = gap > pdfBoxThreshold;
                pairCount++;
                if (fixedSpace != pdfBoxSpace) {
                    disagreementCount++;
                    if (printedCount < MAX_PRINTED) {
                        report.append(String.format(Locale.US,
                                "R=%s[x=%.1f,w=%.1f] L=%s[x=%.1f,w=%.1f] "
                                + "gap=%.2f fixed35=%.2f:%s pdfBoxLike=%.2f:%s\n",
                                GapDiagnosticStripper.describe(right.getUnicode()),
                                right.getXDirAdj(), rw,
                                GapDiagnosticStripper.describe(left.getUnicode()),
                                left.getXDirAdj(), lw, gap, fixedThreshold,
                                fixedSpace ? "SPACE" : "JOIN", pdfBoxThreshold,
                                pdfBoxSpace ? "SPACE" : "JOIN"));
                        printedCount++;
                    }
                }
                previousAverageCharWidth = averageCharWidth;
                previousSpaceWidth = currentSpace;
            }
            line.clear();
        }

        String report() {
            flushLine();
            report.append("Adjacent pairs analysed: ").append(pairCount)
                    .append("\nThreshold disagreements: ").append(disagreementCount)
                    .append("\nDisagreements shown: ").append(printedCount)
                    .append(" (maximum ").append(MAX_PRINTED).append(")")
                    .append("\nNOTE: diagnostic only; extraction rules are unchanged.");
            return report.toString();
        }
    }

    private static class DiagnosticStripper
            extends PDFTextStripper {

        private static final int LIMIT = 180;
        private final StringBuilder report = new StringBuilder();
        private int count = 0;

        DiagnosticStripper() throws IOException {
            super();
        }

        @Override
        protected void processTextPosition(TextPosition position) {
            super.processTextPosition(position);

            if (count >= LIMIT) {
                return;
            }

            String unicode = position.getUnicode();
            StringBuilder codes = new StringBuilder();

            if (unicode == null || unicode.isEmpty()) {
                codes.append("(empty)");
            } else {
                unicode.codePoints().forEach(cp -> {
                    if (codes.length() > 0) {
                        codes.append(",");
                    }
                    codes.append(String.format(
                            Locale.US, "U+%04X", cp));
                });
            }

            String fontName = position.getFont() == null
                    ? "(null)"
                    : position.getFont().getName();

            report.append(String.format(
                    Locale.US,
                    "#%03d %s x=%.1f y=%.1f w=%.1f h=%.1f size=%.1f font=%s\n",
                    count,
                    codes,
                    position.getXDirAdj(),
                    position.getYDirAdj(),
                    position.getWidthDirAdj(),
                    position.getHeightDir(),
                    position.getFontSizeInPt(),
                    fontName
            ));

            count++;
        }

        String report() {
            report.append("\nPositions shown: ").append(count);

            if (count == LIMIT) {
                report.append(" (limited to first ")
                      .append(LIMIT).append(" positions)");
            }

            return report.toString();
        }
    }
}
