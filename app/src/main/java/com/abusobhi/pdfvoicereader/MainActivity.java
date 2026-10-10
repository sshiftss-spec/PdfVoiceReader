package com.abusobhi.pdfvoicereader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
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
    private Button readButton;
    private Button pauseButton;
    private Button stopButton;
    private SeekBar speedBar;
    private TextToSpeech tts;
    private String[] chunks = new String[0];
    private int chunkIndex = 0;
    private Uri currentUri;

    private final ActivityResultLauncher<String[]> openPdf =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                currentUri = uri;
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Some document providers do not offer persistable permissions.
                    // The URI is still readable for this Activity session.
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

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

        readButton = makeButton(R.string.read_aloud, v -> speakCurrent());
        pauseButton = makeButton(R.string.resume, v -> {
            if (tts != null && chunks.length > 0) {
                // Android TextToSpeech has no true pause API. Re-read the current chunk.
                speakCurrent();
            }
        });
        stopButton = makeButton(R.string.stop, v -> { if (tts != null) tts.stop(); chunkIndex = 0; status.setText("تم الإيقاف"); });
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
        setContentView(root);
    }

    private Button makeButton(int text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setOnClickListener(listener);
        return b;
    }

    private void loadPdf(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            PDDocument doc = PDDocument.load(in);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc).trim();
            doc.close();
            if (text.isEmpty()) throw new IllegalStateException("NO_TEXT");
            chunks = splitText(text, 3500);
            chunkIndex = 0;
            runOnUiThread(() -> status.setText("تم تجهيز " + chunks.length + " مقطعًا"));
        } catch (Exception e) {
            runOnUiThread(() -> {
                status.setText("تعذر استخراج النص. في الإصدار القادم سنضيف OCR للصفحات المصورة.");
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
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

    private void speakCurrent() {
        if (tts == null || chunks.length == 0) return;
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
        super.onDestroy();
    }
}
