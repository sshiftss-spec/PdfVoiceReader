package com.abusobhi.pdfvoicereader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;
import android.os.Build;
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
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

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
    private volatile boolean ocrLoading = false;
    private File pendingPhotoFile;
    private final ActivityResultLauncher<String[]> openImage = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException ignored) {}
                    new Thread(() -> {
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            Bitmap bitmap = BitmapFactory.decodeStream(in);
                            if (bitmap == null) throw new IllegalStateException("تعذر فتح الصورة");
                            runOnUiThread(() -> {
                                fileName.setText("صورة للقراءة");
                                pageImage.setImageBitmap(bitmap);
                                resetPageZoom();
                                status.setText("تم فتح الصورة؛ جارٍ تجهيز قارئ النص العربي...");
                            });
                            String text = runOcr(bitmap);
                            setStandaloneText(text, "استخراج النص من الصورة");
                        } catch (Exception e) { showOcrError(e); }
                    }).start();
                }
            });
    private final ActivityResultLauncher<Uri> takePhoto = registerForActivityResult(
            new ActivityResultContracts.TakePicture(), saved -> {
                if (!saved || pendingPhotoFile == null || !pendingPhotoFile.exists()) {
                    runOnUiThread(() -> status.setText("لم يتم التقاط صورة"));
                    return;
                }
                new Thread(() -> {
                    Bitmap bitmap = null;
                    try {
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(pendingPhotoFile.getAbsolutePath(), bounds);
                        int sample = 1;
                        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 3000) sample *= 2;
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = sample;
                        bitmap = BitmapFactory.decodeFile(pendingPhotoFile.getAbsolutePath(), options);
                        if (bitmap == null) throw new IllegalStateException("تعذر فتح الصورة الملتقطة");
                        bitmap = correctImageOrientation(pendingPhotoFile, bitmap);
                        Bitmap captured = bitmap;
                        runOnUiThread(() -> {
                            fileName.setText("صورة ملتقطة بالكاميرا");
                            pageImage.setImageBitmap(captured);
                            resetPageZoom();
                            status.setText("تم التقاط الصورة؛ جارٍ استخراج النص...");
                        });
                        setStandaloneText(runOcr(bitmap), "استخراج النص من الصورة الملتقطة");
                    } catch (Exception e) {
                        showOcrError(e);
                    } finally {
                        // The ImageView keeps this bitmap for display; do not recycle it here.
                        if (pendingPhotoFile != null) pendingPhotoFile.delete();
                        pendingPhotoFile = null;
                    }
                }).start();
            });

    private Bitmap correctImageOrientation(File imageFile, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.postRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.postRotate(270); matrix.postScale(-1, 1); break;
                default: return bitmap;
            }
            Bitmap corrected = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (corrected != bitmap) bitmap.recycle();
            return corrected;
        } catch (Exception ignored) {
            // Keep the decoded image if orientation metadata is unavailable or malformed.
            return bitmap;
        }
    }

    private void startCameraCapture() {
        try {
            File cameraDir = new File(getCacheDir(), "camera");
            if (!cameraDir.exists() && !cameraDir.mkdirs()) {
                throw new IllegalStateException("تعذر إنشاء مجلد الصورة");
            }
            pendingPhotoFile = File.createTempFile("pdfvoicereader_", ".jpg", cameraDir);
            Uri photoUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", pendingPhotoFile);
            takePhoto.launch(photoUri);
        } catch (Exception e) {
            showOcrError(e);
        }
    }

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

        Button openImageButton = makeButton(R.string.open_image_read, v -> openImage.launch(new String[]{"image/*"}));
        openImageButton.setContentDescription("فتح صورة لاستخراج النص العربي وقراءته");
        root.addView(openImageButton);
        Button cameraButton = makeButton(R.string.take_photo_read, v -> startCameraCapture());
        cameraButton.setContentDescription("التقاط صورة لصفحة وقراءة النص العربي");
        root.addView(cameraButton);

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
                        if (!pageText.isEmpty()) {
                            extractedPages[i] = splitText(pageText, 1600);
                            hasText = true;
                        } else {
                            // Scanned pages are OCRed on demand, not while the whole PDF is opening.
                            extractedPages[i] = new String[0];
                        }
                    }
                    // Empty text pages may be scanned pages; OCR will run when the user asks to read them.
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

    private void setStandaloneText(String text, String label) {
        String normalized = normalizeTextForSpeech(text);
        runOnUiThread(() -> {
            if (tts != null) tts.stop();
            closePdfRenderer();
            pageChunks = new String[0][];
            chunks = normalized.isEmpty() ? new String[0] : splitText(normalized, 1600);
            chunkIndex = 0; resumeCharIndex = 0; speechPaused = false; currentPage = 0;
            pageCounter.setText("نص مستخرج من صورة");
            previousPageButton.setEnabled(false); nextPageButton.setEnabled(false);
            status.setText(normalized.isEmpty() ? "لم يُعثر على نص عربي واضح في الصورة" : "تم استخراج النص من الصورة؛ اضغط «قراءة بصوت عالٍ»");
            if (normalized.isEmpty()) Toast.makeText(this, "لم يتمكن OCR من قراءة نص واضح. جرّب صورة أوضح وإضاءة أفضل.", Toast.LENGTH_LONG).show();
        });
    }

    private void showOcrError(Exception e) {
        runOnUiThread(() -> {
            status.setText("تعذر استخراج النص من الصورة");
            Toast.makeText(this, e.getMessage() == null ? "تعذر تشغيل التعرف الضوئي على الحروف العربية" : e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private String runOcr(Bitmap bitmap) throws Exception {
        ensureArabicOcrReady();
        runOnUiThread(() -> status.setText("تم تجهيز OCR العربي؛ جارٍ استخراج النص ومراجعة الأرقام..."));
        synchronized (this) {
            if (tessBaseAPI == null) throw new IllegalStateException("محرك OCR العربي غير جاهز");
            Bitmap enlarged = null;
            try {
                // First pass prioritizes the original image so Arabic text remains natural.
                tessBaseAPI.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                tessBaseAPI.setImage(bitmap);
                String original = tessBaseAPI.getUTF8Text();
                original = original == null ? "" : original;
                tessBaseAPI.clear();

                // A 2x filtered image can help Tesseract distinguish small digits,
                // decimal/separator marks, and date/time punctuation.
                int maxDimension = Math.max(bitmap.getWidth(), bitmap.getHeight());
                float scale = maxDimension < 1800 ? 2.0f : (maxDimension < 2800 ? 1.5f : 1.0f);
                if (scale <= 1.0f) return original;
                int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
                int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
                enlarged = Bitmap.createScaledBitmap(bitmap, width, height, true);
                tessBaseAPI.setImage(enlarged);
                String enlargedText = tessBaseAPI.getUTF8Text();
                enlargedText = enlargedText == null ? "" : enlargedText;
                tessBaseAPI.clear();

                // Keep the original Arabic pass unless the enlarged pass provides
                // materially stronger numeric/date-time evidence.
                return numericOcrScore(enlargedText) > numericOcrScore(original) + 1
                        ? enlargedText : original;
            } finally {
                tessBaseAPI.clear();
                if (enlarged != null && enlarged != bitmap && !enlarged.isRecycled()) enlarged.recycle();
            }
        }
    }

    private int numericOcrScore(String text) {
        if (text == null || text.isEmpty()) return 0;
        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 1632 && c <= 1641)
                    || (c >= 1776 && c <= 1785)) score++;
        }
        // Favor candidates retaining date/time separators alongside their digits.
        if (text.matches("(?s).*\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}.*")) score += 5;
        if (text.matches("(?s).*\\d{1,2}:\\d{2}(:\\d{2})?.*")) score += 4;
        return score;
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

            // Reuse a model only if Tesseract can actually initialize it.
            TessBaseAPI api = new TessBaseAPI();
            if (model.isFile() && model.length() > 1000000
                    && api.init(dataDir.getAbsolutePath(), "ara", TessBaseAPI.OEM_LSTM_ONLY)) {
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
            String[] modelUrls = new String[] {
                    "https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@main/ara.traineddata",
                    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/ara.traineddata"
            };
            String lastDownloadError = "لا يوجد اتصال متاح بمصادر بيانات OCR";
            boolean downloaded = false;
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
                    if (temporaryModel.isFile() && temporaryModel.length() >= 1000000) {
                        downloaded = true;
                        break;
                    }
                    lastDownloadError = "الملف الذي وصل غير مكتمل";
                } catch (Exception downloadError) {
                    lastDownloadError = downloadError.getMessage() == null
                            ? downloadError.getClass().getSimpleName()
                            : downloadError.getMessage();
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            if (!downloaded) {
                temporaryModel.delete();
                throw new IllegalStateException(
                        "تعذر تنزيل نموذج OCR العربي من المصدرين. تحقق من الإنترنت ثم أعد المحاولة. التفاصيل: "
                                + lastDownloadError);
            }

            if (!temporaryModel.isFile() || temporaryModel.length() < 1000000) {
                temporaryModel.delete();
                throw new IllegalStateException("نموذج OCR العربي الذي تم تنزيله غير مكتمل؛ أعد المحاولة مع اتصال مستقر.");
            }
            if (!temporaryModel.renameTo(model)) {
                temporaryModel.delete();
                throw new IllegalStateException("تعذر حفظ نموذج OCR العربي على الهاتف.");
            }

            api = new TessBaseAPI();
            if (!api.init(dataDir.getAbsolutePath(), "ara", TessBaseAPI.OEM_LSTM_ONLY)) {
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
        if (chunks.length == 0 && pdfRenderer != null
                && currentPage >= 0 && currentPage < pageChunks.length) {
            final int requestedPage = currentPage;
            status.setText("الصفحة ممسوحة ضوئيًا؛ جارٍ استخراج النص...");
            new Thread(() -> {
                Bitmap bitmap = null;
                try (PdfRenderer.Page page = pdfRenderer.openPage(requestedPage)) {
                    int width = Math.min(2400, Math.max(1400,
                            getResources().getDisplayMetrics().widthPixels * 3));
                    int height = Math.max(1, (int) ((double) page.getHeight() * width / page.getWidth()));
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    String text = normalizeTextForSpeech(runOcr(bitmap));
                    String[] result = text.isEmpty() ? new String[0] : splitText(text, 1600);
                    runOnUiThread(() -> {
                        if (pdfRenderer == null || currentPage != requestedPage) return;
                        pageChunks[requestedPage] = result;
                        chunks = result;
                        chunkIndex = 0;
                        resumeCharIndex = 0;
                        if (chunks.length == 0) {
                            status.setText("لم يُعثر على نص واضح في هذه الصفحة");
                            Toast.makeText(this, "تعذر استخراج النص من الصفحة؛ جرّب صورة أوضح.", Toast.LENGTH_LONG).show();
                        } else {
                            status.setText("تم استخراج النص؛ جارٍ بدء القراءة");
                            speakRemaining();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (currentPage == requestedPage) {
                            status.setText("تعذر استخراج النص من الصفحة");
                            Toast.makeText(this, e.getMessage() == null ? "حدث خطأ أثناء OCR" : e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
            }).start();
            return;
        }
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
