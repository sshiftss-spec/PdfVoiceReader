package com.abusobhi.pdfvoicereader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.tts.TextToSpeech;
import android.provider.Settings;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Button;
import android.widget.FrameLayout;
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
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private Button speechSettingsButton;
    private TextToSpeech tts;
    private String[] chunks = new String[0];
    private String[][] pageChunks = new String[0][];
    private int chunkIndex = 0;
    private int resumeCharIndex = 0;
    private int utteranceBaseOffset = 0;
    private long utteranceCounter = 0;
    private String currentUtteranceId = "";
    private boolean speechPaused = false;
    private int currentPage = 0;
    private Uri currentUri;
    private ParcelFileDescriptor pdfFileDescriptor;
    private PdfRenderer pdfRenderer;
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    private float pageZoom = 1f;
    private float lastTouchX;
    private float lastTouchY;

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
                configureArabicSpeech();
            } else {
                runOnUiThread(() -> {
                    status.setText("تعذر تشغيل محرك الصوت");
                    Toast.makeText(this, "تحقق من تثبيت خدمات النطق من Google.", Toast.LENGTH_LONG).show();
                });
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}

            @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                if (utteranceId.equals(currentUtteranceId) && !speechPaused
                        && chunks.length > 0 && chunkIndex < chunks.length) {
                    resumeCharIndex = Math.min(chunks[chunkIndex].length(), utteranceBaseOffset + start);
                }
            }

            @Override public void onDone(String utteranceId) {
                if (utteranceId.equals(currentUtteranceId)) {
                    runOnUiThread(() -> {
                        // Ignore completion callbacks from an utterance that was stopped or replaced.
                        if (!utteranceId.equals(currentUtteranceId) || speechPaused) return;
                        resumeCharIndex = 0;
                        if (chunkIndex + 1 < chunks.length) {
                            chunkIndex++;
                            speakRemaining();
                        } else {
                            status.setText("اكتملت قراءة مقاطع هذه الصفحة");
                        }
                    });
                }
            }

            @Override public void onError(String utteranceId) {
                showSpeechError(utteranceId);
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
        setupPageZoom();
        FrameLayout pageFrame = new FrameLayout(this);
        pageFrame.setClipChildren(true);
        pageFrame.setClipToPadding(true);
        pageFrame.setBackgroundColor(Color.LTGRAY);
        pageFrame.addView(pageImage, new FrameLayout.LayoutParams(-1, -2));
        root.addView(pageFrame, new LinearLayout.LayoutParams(-1, -2));

        readButton = makeButton(R.string.read_aloud, v -> startCurrentFromBeginning());
        pauseButton = makeButton(R.string.resume, v -> resumeCurrent());
        stopButton = makeButton(R.string.stop, v -> pauseCurrent());
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

        speechSettingsButton = makeButton(R.string.speed, v -> openSpeechSettings());
        speechSettingsButton.setText("إعدادات الصوت العربي");
        speechSettingsButton.setContentDescription("فتح إعدادات محرك النطق والأصوات العربية");
        root.addView(speechSettingsButton);
        setContentView(scroll);
    }

    private void configureArabicSpeech() {
        int result = tts.setLanguage(new Locale("ar", "SA"));
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = tts.setLanguage(new Locale("ar"));
        }
        if (result == TextToSpeech.LANG_MISSING_DATA) {
            status.setText("بيانات الصوت العربي غير مثبتة");
            Toast.makeText(this, "افتح إعدادات الصوت العربي وثبّت بيانات اللغة العربية.", Toast.LENGTH_LONG).show();
            return;
        }
        if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.setText("محرك الصوت الحالي لا يدعم العربية");
            Toast.makeText(this, "اختر صوتًا عربيًا في إعدادات تحويل النص إلى كلام.", Toast.LENGTH_LONG).show();
            return;
        }
        tts.setSpeechRate(0.5f + (speedBar.getProgress() / 100f));
        status.setText("الصوت العربي جاهز");
    }

    private void openSpeechSettings() {
        Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this,
                    "في الإعدادات ابحث عن «تحويل النص إلى كلام»، ثم اختر خدمات النطق من Google والصوت العربي.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void setupPageZoom() {
        scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        pageZoom = Math.max(1f, Math.min(5f, pageZoom * detector.getScaleFactor()));
                        pageImage.setScaleX(pageZoom);
                        pageImage.setScaleY(pageZoom);
                        if (pageZoom <= 1f) {
                            pageImage.setTranslationX(0f);
                            pageImage.setTranslationY(0f);
                        }
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                pageZoom = pageZoom > 1f ? 1f : 2.5f;
                pageImage.setScaleX(pageZoom);
                pageImage.setScaleY(pageZoom);
                pageImage.setTranslationX(0f);
                pageImage.setTranslationY(0f);
                return true;
            }
        });

        pageImage.setOnTouchListener((view, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                view.getParent().requestDisallowInterceptTouchEvent(pageZoom > 1f);
            } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && pageZoom > 1f
                    && event.getPointerCount() == 1 && !scaleGestureDetector.isInProgress()) {
                float dx = event.getX() - lastTouchX;
                float dy = event.getY() - lastTouchY;
                view.setTranslationX(view.getTranslationX() + dx);
                view.setTranslationY(view.getTranslationY() + dy);
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }

            lastTouchX = event.getX();
            lastTouchY = event.getY();
            return true;
        });
    }

    private void resetPageZoom() {
        pageZoom = 1f;
        if (pageImage != null) {
            pageImage.setScaleX(1f);
            pageImage.setScaleY(1f);
            pageImage.setTranslationX(0f);
            pageImage.setTranslationY(0f);
        }
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
                        PositionRtlStripper stripper = new PositionRtlStripper();
                        stripper.setStartPage(i + 1);
                        stripper.setEndPage(i + 1);
                        stripper.setSortByPosition(true);
                        stripper.getText(doc);
                        String pageText = normalizeTextForSpeech(stripper.result());
                        if (!pageText.isEmpty()) {
                            extractedPages[i] = splitText(pageText, 1600);
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
                speechPaused = false;
                resumeCharIndex = 0;
                currentUtteranceId = "";
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


    /*
     * Experimental Arabic PDF reconstruction, revision 2.
     *
     * PDFTextStripper calls writeString once per detected word, not once per
     * complete line. Therefore, do not infer spaces between individual glyphs
     * and do not insert a line break after every writeString call. Keep the
     * detected word boundaries, reorder glyph positions inside each word for
     * RTL Arabic, and let PDFTextStripper's word/line separators delimit text.
     */
    private static class PositionRtlStripper extends PDFTextStripper {
        private final StringBuilder out = new StringBuilder();

        PositionRtlStripper() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(
                String text, List<TextPosition> textPositions)
                throws IOException {
            if (textPositions == null || textPositions.isEmpty()) {
                if (text != null) out.append(text);
                return;
            }

            List<TextPosition> positions = new ArrayList<>(textPositions);
            Collections.sort(positions,
                    (a, b) -> Float.compare(b.getXDirAdj(), a.getXDirAdj()));

            for (TextPosition p : positions) {
                String unicode = p.getUnicode();
                if (unicode != null && !unicode.isEmpty()) {
                    out.append(unicode);
                }
            }
        }

        @Override
        protected void writeWordSeparator() throws IOException {
            if (out.length() > 0) {
                char last = out.charAt(out.length() - 1);
                if (last != ' ' && last != '\n') out.append(' ');
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
                out.setLength(out.length() - 1);
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        }

        String result() {
            return out.toString();
        }
    }

    private String normalizeTextForSpeech(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return "";
        String text = rawText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n");
        StringBuilder normalized = new StringBuilder();
        boolean previousLineWasBlank = false;
        for (String line : lines) {
            String cleaned = line.replaceAll("[\\t ]+", " ").trim();
            if (cleaned.isEmpty()) {
                if (normalized.length() > 0 && !previousLineWasBlank) {
                    normalized.append("\n\n");
                }
                previousLineWasBlank = true;
            } else {
                if (normalized.length() > 0
                        && !normalized.toString().endsWith("\n\n")
                        && normalized.charAt(normalized.length() - 1) != ' ') {
                    normalized.append(' ');
                }
                normalized.append(cleaned);
                previousLineWasBlank = false;
            }
        }
        return normalized.toString().replaceAll(" *\n\n *", "\n\n").trim();
    }

    private String[] splitText(String text, int max) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + max, text.length());
            if (end < text.length()) {
                int boundary = -1;
                for (int i = end; i > start + 500; i--) {
                    char c = text.charAt(i - 1);
                    if (c == '.' || c == '!' || c == '?' || c == '؟'
                            || c == '؛' || c == ':' || c == '۔' || c == '،' || c == '\n') {
                        boundary = i;
                        break;
                    }
                }
                if (boundary > start + 500) {
                    end = boundary;
                } else {
                    int space = text.lastIndexOf(' ', end);
                    if (space > start + 500) end = space;
                }
            }
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) list.add(part);
            start = end;
        }
        return list.toArray(new String[0]);
    }

    private void showPage(int index) {
        if (pdfRenderer == null) return;
        if (index < 0 || index >= pdfRenderer.getPageCount()) return;
        if (index != currentPage && tts != null) {
            tts.stop();
            speechPaused = false;
            resumeCharIndex = 0;
            currentUtteranceId = "";
        }
        try (PdfRenderer.Page page = pdfRenderer.openPage(index)) {
            int targetWidth = Math.min(1200, Math.max(600, getResources().getDisplayMetrics().widthPixels));
            int targetHeight = Math.max(1, (int) ((double) page.getHeight() * targetWidth / page.getWidth()));
            Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pageImage.setImageBitmap(bitmap);
            resetPageZoom();
            currentPage = index;
            chunks = index < pageChunks.length ? pageChunks[index] : new String[0];
            chunkIndex = 0;
            resumeCharIndex = 0;
            speechPaused = false;
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

    private void startCurrentFromBeginning() {
        resumeCharIndex = 0;
        speechPaused = false;
        speakRemaining();
    }

    private void pauseCurrent() {
        if (tts == null || chunks.length == 0) return;
        speechPaused = true;
        tts.stop();
        status.setText("تم الإيقاف مؤقتًا");
    }

    private void resumeCurrent() {
        if (tts == null || chunks.length == 0) {
            Toast.makeText(this, R.string.no_text_to_read, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!speechPaused && tts.isSpeaking()) return;
        speechPaused = false;
        speakRemaining();
    }

    private void showSpeechError(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(currentUtteranceId)) return;
        runOnUiThread(() -> {
            if (!utteranceId.equals(currentUtteranceId)) return;
            speechPaused = true;
            status.setText("تعذر نطق المقطع الحالي");
            Toast.makeText(this,
                    "حدث خطأ أثناء النطق. اضغط «استئناف» للمحاولة مجددًا أو تحقق من إعدادات الصوت العربي.",
                    Toast.LENGTH_LONG).show();
        });
    }

    private void speakRemaining() {
        if (tts == null || chunks.length == 0 || chunkIndex < 0 || chunkIndex >= chunks.length) {
            Toast.makeText(this, R.string.no_text_to_read, Toast.LENGTH_SHORT).show();
            return;
        }
        String currentText = chunks[chunkIndex];
        if (resumeCharIndex >= currentText.length()) resumeCharIndex = 0;
        utteranceBaseOffset = resumeCharIndex;
        String remainingText = currentText.substring(resumeCharIndex);
        if (remainingText.trim().isEmpty()) {
            resumeCharIndex = 0;
            return;
        }
        currentUtteranceId = "pdf_" + chunkIndex + "_" + (++utteranceCounter);
        tts.speak(remainingText, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
        status.setText("قراءة المقطع " + (chunkIndex + 1) + " من " + chunks.length);
    }

    private void nextChunk() {
        if (chunkIndex + 1 < chunks.length) {
            if (tts != null) tts.stop();
            chunkIndex++;
            resumeCharIndex = 0;
            speechPaused = false;
            startCurrentFromBeginning();
        }
    }

    private void previousChunk() {
        if (chunkIndex > 0) {
            if (tts != null) tts.stop();
            chunkIndex--;
            resumeCharIndex = 0;
            speechPaused = false;
            startCurrentFromBeginning();
        }
    }

    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        closePdfRenderer();
        super.onDestroy();
    }
}
