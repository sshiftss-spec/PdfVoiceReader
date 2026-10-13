
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

    private String diagnose(PDDocument doc) throws Exception {
        DiagnosticStripper stripper = new DiagnosticStripper();
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(false);
        stripper.getText(doc);
        return stripper.report();
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
