package com.abusobhi.pdfvoicereader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private TextView status;
    private TextView fileName;
    private TextView pageCounter;
    private ImageView pageImage;
    private Button readButton;
    private Button pauseButton;
    private Button stopButton;
    private Button previousPageButton;
    private Button nextPageButton;
    private SeekBar speedBar;
    private TextToSpeech tts;
    private String[] chunks = new String[0];
    private String[][] pageChunks = new String[0][];
    private int chunkIndex = 0;
    private int currentPage = 0;
    private Uri currentUri;
    private ParcelFileDescriptor pdfFileDescriptor;
    private PdfRenderer pdfRenderer;

    private final ActivityResultLauncher<String[]> openPdf =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                currentUri = uri;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Some document providers do not offer persistable permissions.
                }
                String name = uri.getLastPathSegment();
                fileName.setText(name != null ? name : "PDF");
                status.setText(R.string.loading);
                new Thread(() -> loadPdf(uri)).start();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();
        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                int ar = tts.setLanguage(new Locale("ar"));
                if (ar == TextToSpeech.LANG_MISSING_DATA || ar == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
            }
        });
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 24, 32, 32);
        scroll.addView(root);

        fileName = new TextView(this);
        fileName.setText(R.string.no_file);
        fileName.setTextSize(22);
        fileName.setContentDescription("اسم ملف PDF");
        root.addView(fileName, new LinearLayout.LayoutParams(-1, -2));

        Button open = new Button(this);
        open.setText(R.string.open_pdf);
        open.setContentDescription("فتح ملف PDF");
        open.setOnClickListener(v -> openPdf.launch(new String[]{"application/pdf"}));
        root.addView(open);

        status = new TextView(this);
        status.setText("جاهز");
        status.setTextSize(18);
        root.addView(status);

        pageCounter = new TextView(this);
        pageCounter.setText(R.string.page_not_loaded);
        pageCounter.setTextSize(18);
        pageCounter.setGravity(android.view.Gravity.CENTER);
        root.addView(pageCounter, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pageControls = new LinearLayout(this);
        pageControls.setOrientation(LinearLayout.HORIZONTAL);
        previousPageButton = makeButton(R.string.previous_page, v -> showPage(currentPage - 1));
        nextPageButton = makeButton(R.string.next_page, v -> showPage(currentPage + 1));
        pageControls.addView(previousPageButton, new LinearLayout.LayoutParams(0, -2, 1));
        pageControls.addView(nextPageButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(pageControls);

        pageImage = new ImageView(this);
        pageImage.setAdjustViewBounds(true);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pageImage.setBackgroundColor(Color.LTGRAY);
        pageImage.setContentDescription("معاينة صفحة PDF");
        root.addView(pageImage, new LinearLayout.LayoutParams(-1, -2));

        readButton = makeButton(R.string.read_aloud, v -> speakCurrent());
        pauseButton = makeButton(R.string.resume, v -> {
            if (tts != null && chunks.length > 0) {
                // Android TextToSpeech has no true pause API; resume re-reads the current chunk.
                speakCurrent();
            }
        });
        stopButton = makeButton(R.string.stop, v -> {
            if (tts != null) tts.stop();
            chunkIndex = 0;
            status.setText("تم الإيقاف");
        });
        Button prev = makeButton(R.string.previous, v -> previousChunk());
        Button next = makeButton(R.string.next, v -> nextChunk());

        root.addView(readButton);
        root.addView(pauseButton);
        root.addView(stopButton);
        root.addView(prev);
        root.addView(next);

        TextView speedLabel = new TextView(this);
        speedLabel.setText(R.string.speed);
        speedLabel.setTextSize(18);
        root.addView(speedLabel);

        speedBar = new SeekBar(this);
        speedBar.setMax(150);
        speedBar.setProgress(50);
        speedBar.setContentDescription("سرعة القراءة");
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                if (tts != null) tts.setSpeechRate(0.5f + (p / 100f));
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        root.addView(speedBar);
        setContentView(scroll);
    }

    private Button makeButton(int text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setOnClickListener(listener);
        return b;
    }

    private void loadPdf(Uri uri) {
        ParcelFileDescriptor newDescriptor = null;
        PdfRenderer newRenderer = null;
        try {
            newDescriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (newDescriptor == null) throw new IllegalStateException("تعذر فتح ملف PDF");
            newRenderer = new PdfRenderer(newDescriptor);

            String[][] extractedPages = new String[0][];
            String extractionNote = "";
            try (InputStream in = getContentResolver().openInputStream(uri);
                 PDDocument doc = in == null ? null : PDDocument.load(in)) {
                if (doc != null) {
                    extractedPages = new String[doc.getNumberOfPages()][];
                    boolean hasText = false;
                    for (int i = 0; i < doc.getNumberOfPages(); i++) {
                        PDFTextStripper stripper = new PDFTextStripper();
                        stripper.setStartPage(i + 1);
                        stripper.setEndPage(i + 1);
                        String pageText = stripper.getText(doc).trim();
                        if (!pageText.isEmpty()) {
                            extractedPages[i] = splitText(pageText, 3500);
                            hasText = true;
                        } else {
                            extractedPages[i] = new String[0];
                        }
                    }
                    if (!hasText) extractionNote = getString(R.string.no_extractable_text);
                } else {
                    extractionNote = getString(R.string.no_extractable_text);
                }
            } catch (Exception textError) {
                extractionNote = getString(R.string.no_extractable_text);
            }

            final ParcelFileDescriptor readyDescriptor = newDescriptor;
            final PdfRenderer readyRenderer = newRenderer;
            final String[][] readyPages = extractedPages;
            final String readyNote = extractionNote;
            newDescriptor = null;
            newRenderer = null;
            runOnUiThread(() -> {
                if (tts != null) tts.stop();
                closePdfRenderer();
                pdfFileDescriptor = readyDescriptor;
                pdfRenderer = readyRenderer;
                pageChunks = readyPages;
                chunks = pageChunks.length > 0 ? pageChunks[0] : new String[0];
                chunkIndex = 0;
                currentPage = 0;
                showPage(0);
                if (readyNote.isEmpty()) {
                    int totalChunks = 0;
                    for (String[] pageParts : pageChunks) totalChunks += pageParts.length;
                    status.setText(getString(R.string.pdf_loaded, pdfRenderer.getPageCount(), totalChunks));
                } else {
                    status.setText(getString(R.string.pdf_loaded_no_text, pdfRenderer.getPageCount()));
                    Toast.makeText(this, readyNote, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            if (newRenderer != null) newRenderer.close();
            if (newDescriptor != null) {
                try { newDescriptor.close(); } catch (Exception ignored) {}
            }
            runOnUiThread(() -> {
                status.setText(R.string.error);
                Toast.makeText(this, e.getMessage() == null ? getString(R.string.error) : e.getMessage(),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private String[] splitText(String text, int max) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + max, text.length());
            if (end < text.length()) {
                int p = text.lastIndexOf('\n', end);
                if (p > start + 500) end = p;
            }
            list.add(text.substring(start, end).trim());
            start = end;
        }
        return list.toArray(new String[0]);
    }

    private void showPage(int index) {
        if (pdfRenderer == null) return;
        if (index < 0 || index >= pdfRenderer.getPageCount()) return;
        if (index != currentPage && tts != null) tts.stop();
        try (PdfRenderer.Page page = pdfRenderer.openPage(index)) {
            int targetWidth = Math.min(1200, Math.max(600, getResources().getDisplayMetrics().widthPixels));
            int targetHeight = Math.max(1, (int) ((double) page.getHeight() * targetWidth / page.getWidth()));
            Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pageImage.setImageBitmap(bitmap);
            currentPage = index;
            chunks = index < pageChunks.length ? pageChunks[index] : new String[0];
            chunkIndex = 0;
            pageCounter.setText(getString(R.string.page_counter, currentPage + 1, pdfRenderer.getPageCount()));
            previousPageButton.setEnabled(currentPage > 0);
            nextPageButton.setEnabled(currentPage + 1 < pdfRenderer.getPageCount());
        } catch (Exception e) {
            Toast.makeText(this, R.string.page_render_error, Toast.LENGTH_LONG).show();
        }
    }

    private void closePdfRenderer() {
        if (pdfRenderer != null) {
            pdfRenderer.close();
            pdfRenderer = null;
        }
        if (pdfFileDescriptor != null) {
            try { pdfFileDescriptor.close(); } catch (Exception ignored) {}
            pdfFileDescriptor = null;
        }
    }

    private void speakCurrent() {
        if (tts == null || chunks.length == 0) {
            Toast.makeText(this, R.string.no_text_to_read, Toast.LENGTH_SHORT).show();
            return;
        }
        tts.speak(chunks[chunkIndex], TextToSpeech.QUEUE_FLUSH, null, "pdf_" + chunkIndex);
        status.setText("قراءة المقطع " + (chunkIndex + 1) + " من " + chunks.length);
    }

    private void nextChunk() {
        if (chunkIndex + 1 < chunks.length) { chunkIndex++; speakCurrent(); }
    }

    private void previousChunk() {
        if (chunkIndex > 0) { chunkIndex--; speakCurrent(); }
    }

    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        closePdfRenderer();
        super.onDestroy();
    }
}
