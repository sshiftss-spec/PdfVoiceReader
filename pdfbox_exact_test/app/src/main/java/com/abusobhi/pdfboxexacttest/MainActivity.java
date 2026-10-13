
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
