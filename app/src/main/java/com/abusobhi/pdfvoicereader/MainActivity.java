package com.abusobhi.pdfvoicereader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
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

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import com.googlecode.tesseract.android.TessBaseAPI;
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
    private TessBaseAPI tessBaseAPI;
    private volatile boolean ocrReady = false;

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

    private final ActivityResultLauncher<String[]> openImage = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {}
                status.setText("تم اختيار الصورة؛ جارٍ استخراج النص العربي...");
                new Thread(() -> {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IllegalStateException("تعذر فتح الصورة المختارة");
                        Bitmap bitmap = BitmapFactory.decodeStream(in);
                        if (bitmap == null) throw new IllegalStateException("تعذر فك ترميز الصورة");
                        runOnUiThread(() -> {
                            fileName.setText("صورة للقراءة");
                            pageImage.setImageBitmap(bitmap);
                            resetPageZoom();
                        });
                        String extracted = runOcr(bitmap);
                        setStandaloneText(extracted);
                    } catch (Exception e) {
                        showOcrError(e);
                    }
                }).start();
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

        Button openImageButton = makeButton(R.string.open_image_read,
                v -> openImage.launch(new String[]{"image/*"}));
        openImageButton.setContentDescription("فتح صورة من المعرض لاستخراج النص العربي وقراءته");
        root.addView(openImageButton);

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
                        String pageText = normalizeTextForSpeech(
                                ArabicPdfTextStripper.extractPage(doc, i + 1));

                        // PDFs can contain text that is technically extractable but
                        // unusable (for example, Arabic letters separated one by one).
                        // Fall back to OCR on the rendered page when extraction is empty
                        // or looks corrupted. Keep valid embedded text untouched.
                        if (isPoorPdfText(pageText)) {
                            final int pageNumber = i + 1;
                            final int totalPages = doc.getNumberOfPages();
                            runOnUiThread(() -> status.setText(
                                    "جارٍ تحسين قراءة الصفحة " + pageNumber + " من " + totalPages));
                            String ocrText = normalizeTextForSpeech(
                                    runOcr(renderPdfPageForOcr(newRenderer, i)));
                            if (!ocrText.isEmpty()) {
                                pageText = ocrText;
                            }
                        }

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


    // Treat empty pages and text with many isolated one-letter tokens as
    // extraction failures. This catches common Arabic PDF font-map/OCR issues.
    private boolean isPoorPdfText(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        String cleaned = text.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", " ").trim();
        String[] tokens = cleaned.split("\\s+");
        int arabicWords = 0;
        int singleLetterWords = 0;
        int arabicLetters = 0;
        int allLetters = 0;
        for (String token : tokens) {
            String word = token.replaceAll("[^\\u0621-\\u064A\\u066E-\\u06D3\\u0750-\\u077F]", "");
            if (!word.isEmpty()) {
                arabicWords++;
                if (word.length() == 1) singleLetterWords++;
                arabicLetters += word.length();
            }
            for (int i = 0; i < token.length(); i++) {
                if (Character.isLetter(token.charAt(i))) allLetters++;
            }
        }
        if (arabicLetters < 12) return true;
        double isolatedRatio = arabicWords == 0 ? 1.0 : (double) singleLetterWords / arabicWords;
        double arabicRatio = allLetters == 0 ? 0.0 : (double) arabicLetters / allLetters;
        return (arabicWords >= 12 && isolatedRatio >= 0.38)
                || (allLetters >= 30 && arabicRatio < 0.45);
    }

    // Render a PDF page at a useful OCR resolution. The caller owns and recycles
    // the bitmap returned by PdfRenderer; OCR itself remains on-device.
    private Bitmap renderPdfPageForOcr(PdfRenderer renderer, int pageIndex) {
        try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            float scale = Math.min(2.5f, 1800f / Math.max(1, page.getWidth()));
            int width = Math.max(1, Math.round(page.getWidth() * scale));
            int height = Math.max(1, Math.round(page.getHeight() * scale));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }

    private void setStandaloneText(String rawText) {
        String normalized = normalizeTextForSpeech(rawText);
        runOnUiThread(() -> {
            if (tts != null) tts.stop();
            closePdfRenderer();
            pageChunks = new String[0][];
            chunks = normalized.isEmpty() ? new String[0] : splitText(normalized, 1600);
            chunkIndex = 0;
            resumeCharIndex = 0;
            speechPaused = false;
            currentPage = 0;
            pageCounter.setText("نص مستخرج من صورة");
            previousPageButton.setEnabled(false);
            nextPageButton.setEnabled(false);
            status.setText(normalized.isEmpty()
                    ? "لم يُعثر على نص واضح في الصورة"
                    : "تم استخراج النص من الصورة؛ اضغط «قراءة بصوت عالٍ»");
            if (normalized.isEmpty()) {
                Toast.makeText(this, "لم يتمكن OCR من قراءة نص واضح. جرّب صورة أوضح وإضاءة أفضل.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showOcrError(Exception e) {
        runOnUiThread(() -> {
            status.setText("تعذر استخراج النص من الصورة");
            String message = e.getMessage();
            Toast.makeText(this,
                    message == null ? "تعذر تشغيل التعرف الضوئي على الحروف العربية" : message,
                    Toast.LENGTH_LONG).show();
        });
    }

    private String runOcr(Bitmap bitmap) throws Exception {
        ensureArabicOcrReady();
        runOnUiThread(() -> status.setText("جارٍ تحليل تخطيط الصورة واختيار أفضل قراءة..."));
        synchronized (this) {
            if (tessBaseAPI == null) throw new IllegalStateException("محرك OCR العربي غير جاهز");

            // Try multiple page-layout modes. Receipts and transfer notices often
            // have labels on one side and values on the other; a single layout
            // mode can incorrectly read the two columns separately.
            String bestText = "";
            int bestScore = Integer.MIN_VALUE;
            Bitmap enhanced = prepareOcrBitmap(bitmap);
            Bitmap[] candidates = enhanced == bitmap
                    ? new Bitmap[]{bitmap}
                    : new Bitmap[]{bitmap, enhanced};
            try {
                for (Bitmap candidate : candidates) {
                    int[] modes = {
                            TessBaseAPI.PageSegMode.PSM_AUTO,
                            TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                            TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                    };
                    for (int mode : modes) {
                        tessBaseAPI.setPageSegMode(mode);
                        String text = recognizeBitmap(candidate);
                        int score = ocrTextScore(text) + receiptLayoutBonus(text);
                        if (score > bestScore) {
                            bestScore = score;
                            bestText = text;
                        }
                    }
                }
            } finally {
                tessBaseAPI.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                if (enhanced != bitmap && !enhanced.isRecycled()) enhanced.recycle();
            }
            return bestText;
        }
    }

    // Prefer OCR outputs that keep receipt labels and their values together
    // on individual lines, without rewriting or guessing any extracted data.
    private int receiptLayoutBonus(String text) {
        if (text == null || text.isEmpty()) return 0;
        String[] labels = {
                "اسم المرسل", "اسم المستلم", "حساب المرسل", "حساب المستلم",
                "رقم العملية", "تاريخ العملية", "المبلغ", "الملاحظة", "العملية"
        };
        int bonus = 0;
        for (String line : text.split("\\n")) {
            String compact = line.replaceAll("[\\\\t ]+", " ").trim();
            int found = 0;
            for (String label : labels) {
                if (compact.contains(label)) found++;
            }
            // A line with a field label and other content is more useful than
            // a label-only line, as long as we do not invent a value.
            if (found == 1 && compact.length() > 14) bonus += 12;
            else if (found > 1) bonus -= 8;
        }
        return bonus;
    }

    private String recognizeBitmap(Bitmap bitmap) {
        try {
            tessBaseAPI.setImage(bitmap);
            String result = tessBaseAPI.getUTF8Text();
            return result == null ? "" : result;
        } finally {
            tessBaseAPI.clear();
        }
    }

    private Bitmap prepareOcrBitmap(Bitmap source) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int longestSide = Math.max(sourceWidth, sourceHeight);
        float scale = 1f;
        if (longestSide < 1800) {
            scale = Math.min(2f, 1800f / longestSide);
        } else if (longestSide > 2800) {
            scale = 2400f / longestSide;
        }

        int border = 16;
        int contentWidth = Math.max(1, Math.round(sourceWidth * scale));
        int contentHeight = Math.max(1, Math.round(sourceHeight * scale));
        Bitmap output = Bitmap.createBitmap(
                contentWidth + border * 2, contentHeight + border * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        // Grayscale with a mild contrast boost; avoid hard thresholding, which
        // can erase Arabic dots and tashkeel in faint scans.
        float contrast = 1.15f;
        float offset = 128f * (1f - contrast);
        ColorMatrix grayscale = new ColorMatrix();
        grayscale.setSaturation(0f);
        ColorMatrix contrastMatrix = new ColorMatrix(new float[] {
                contrast, 0, 0, 0, offset,
                0, contrast, 0, 0, offset,
                0, 0, contrast, 0, offset,
                0, 0, 0, 1, 0
        });
        grayscale.postConcat(contrastMatrix);
        paint.setColorFilter(new ColorMatrixColorFilter(grayscale));
        Rect destination = new Rect(border, border, border + contentWidth, border + contentHeight);
        canvas.drawBitmap(source, null, destination, paint);
        return output;
    }

    private int ocrTextScore(String text) {
        if (text == null || text.isEmpty()) return 0;
        int arabicLetters = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean arabic = (c >= '\u0621' && c <= '\u064A')
                    || (c >= '\u066E' && c <= '\u06D3')
                    || (c >= '\u0750' && c <= '\u077F');
            if (arabic && Character.isLetter(c)) arabicLetters++;
            if (Character.isLetterOrDigit(c)) {
                if (!inWord) words++;
                inWord = true;
            } else {
                inWord = false;
            }
        }
        return arabicLetters + words * 2;
    }

    private void ensureArabicOcrReady() throws Exception {
        if (ocrReady) return;
        synchronized (this) {
            if (ocrReady) return;
            File dataDir = new File(getFilesDir(), "tesseract");
            File tessdata = new File(dataDir, "tessdata");
            if (!tessdata.exists() && !tessdata.mkdirs()) {
                throw new IllegalStateException("تعذر إنشاء مجلد بيانات OCR");
            }
            File model = new File(tessdata, "ara.traineddata");
            TessBaseAPI api = new TessBaseAPI();

            // Reuse a valid local Arabic model first. This avoids downloading it
            // again on later image reads and keeps OCR fully on-device.
            if (model.isFile() && model.length() > 1000000
                    && api.init(dataDir.getAbsolutePath(), "ara")) {
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                tessBaseAPI = api;
                ocrReady = true;
                return;
            }
            api.recycle();
            if (model.exists()) model.delete();

            runOnUiThread(() -> status.setText("جارٍ تنزيل بيانات OCR العربية لأول مرة…"));
            File temporaryModel = new File(tessdata, "ara.traineddata.download");
            if (temporaryModel.exists()) temporaryModel.delete();

            // Some mobile networks cannot reach raw.githubusercontent.com.
            // Try jsDelivr as a second source before reporting a download error.
            String[] modelUrls = {
                "https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@main/ara.traineddata",
                "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/ara.traineddata"
            };
            Exception lastDownloadError = null;
            for (String modelUrl : modelUrls) {
                HttpURLConnection connection = null;
                try {
                    if (temporaryModel.exists()) temporaryModel.delete();
                    connection = (HttpURLConnection) new URL(modelUrl).openConnection();
                    connection.setInstanceFollowRedirects(true);
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(60000);
                    connection.setRequestProperty("User-Agent", "PdfVoiceReader-Android");
                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new java.io.IOException("HTTP " + responseCode);
                    }
                    try (InputStream in = connection.getInputStream();
                         FileOutputStream out = new FileOutputStream(temporaryModel)) {
                        byte[] buffer = new byte[16384];
                        int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                        out.getFD().sync();
                    }
                    if (temporaryModel.isFile() && temporaryModel.length() > 1000000) {
                        lastDownloadError = null;
                        break;
                    }
                    throw new java.io.IOException("ملف نموذج OCR الذي تم تنزيله غير مكتمل");
                } catch (Exception downloadError) {
                    lastDownloadError = downloadError;
                    temporaryModel.delete();
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }

            if (lastDownloadError != null || !temporaryModel.isFile()
                    || temporaryModel.length() < 1000000) {
                temporaryModel.delete();
                throw new IllegalStateException(
                        "تعذر تنزيل نموذج OCR العربي. تحقق من اتصال الإنترنت ثم أعد المحاولة. التفاصيل: "
                                + (lastDownloadError == null ? "نموذج غير مكتمل" : lastDownloadError.getMessage()),
                        lastDownloadError);
            }
            if (!temporaryModel.renameTo(model)) {
                temporaryModel.delete();
                throw new IllegalStateException("تعذر حفظ نموذج OCR العربي على الهاتف.");
            }

            api = new TessBaseAPI();
            if (!api.init(dataDir.getAbsolutePath(), "ara")) {
                api.recycle();
                model.delete();
                throw new IllegalStateException("تم تنزيل نموذج OCR لكن تعذرت تهيئته؛ أعد المحاولة.");
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            tessBaseAPI = api;
            ocrReady = true;
            runOnUiThread(() -> status.setText("تم تجهيز OCR العربي"));
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
            if (isPdfFooterLine(cleaned)) continue;
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
        // ArabicPdfTextStripper places rotated margin headings before the body.
        return normalized.toString().replaceAll(" *\\n\\n *", "\\n\\n").trim();
    }


    private boolean isPdfFooterLine(String line) {
        if (line == null || line.isEmpty()) return false;
        // Compare without Arabic vowel marks or tatweel so both printed and
        // extracted variants of the footer are recognized.
        String key = line
                .replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "")
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replaceAll("[\\t ]+", " ")
                .trim();
        boolean hasIndexPrompt = key.contains("اضغط للعودة للفهرس");
        boolean hasAuthorCredit = key.contains("بواسطة")
                && key.contains("المقداد حمدي");
        boolean hasPublishingFooter = key.contains("النشر والتوزيع");
        boolean hasFortyHadithTitle = key.contains("الاربعين النووية");
        // The title appears in this PDF's page footer. Drop it when it is a
        // footer by itself or alongside either of the other footer labels.
        return hasIndexPrompt || hasAuthorCredit || hasPublishingFooter
                || (hasFortyHadithTitle
                    && (key.length() < 45 || hasIndexPrompt || key.contains("بواسطة")));
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
        if (tessBaseAPI != null) { tessBaseAPI.recycle(); tessBaseAPI = null; }
        super.onDestroy();
    }
}
