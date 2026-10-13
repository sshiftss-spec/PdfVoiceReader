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

import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int PICK_PDF = 41;
    private TextView output;

    @Override public void onCreate(Bundle state) {
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
        root.addView(choose, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setText("اختبار معزول لـ PDFBox Android 2.0.27.0: يقارن الاستخراج الخام بترتيب المواضع، ثم ترتيب المواضع مع تعديل حساسية المسافات. لا يغيّر التطبيق الأصلي ولا يصلح الكلمات تلقائيًا.");
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(16);
        output.setPadding(0, 20, 0, 20);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PDF || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        output.setText("جارٍ مقارنة ثلاث طرق استخراج للصفحة الرابعة...");
        new Thread(() -> {
            String result;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 PDDocument doc = in == null ? null : PDDocument.load(in)) {
                if (doc == null) throw new IllegalStateException("تعذر فتح الملف");
                if (doc.getNumberOfPages() < 4) throw new IllegalStateException("الملف يحتوي على أقل من أربع صفحات");
                StringBuilder all = new StringBuilder();
                all.append("PDFBox Android version: 2.0.27.0\nPages: ")
                   .append(doc.getNumberOfPages()).append("\n");
                all.append("\n========== 1: RAW DEFAULT ==========\n");
                all.append(extract(doc, false, 0.5f));
                all.append("\n\n========== 2: SORT BY POSITION ==========\n");
                all.append(extract(doc, true, 0.5f));
                all.append("\n\n========== 3: SORT + LOWER SPACING TOLERANCE (0.2) ==========\n");
                all.append(extract(doc, true, 0.2f));
                result = all.toString();
            } catch (Throwable e) {
                result = "TEST FAILED\n" + e.getClass().getName() + ": " + e.getMessage();
            }
            final String shown = result;
            runOnUiThread(() -> output.setText(shown));
        }).start();
    }

    private String extract(PDDocument doc, boolean sortByPosition, float spacingTolerance) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(4);
        stripper.setEndPage(4);
        stripper.setSortByPosition(sortByPosition);
        stripper.setSpacingTolerance(spacingTolerance);
        return stripper.getText(doc);
    }
}
