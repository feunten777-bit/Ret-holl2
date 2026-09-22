package fr.retholl.voice;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int AUDIO_PERMISSION = 77;

    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private EditText editor;
    private TextView status;
    private Button startButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final ArrayDeque<String> redo = new ArrayDeque<>();
    private boolean listening = false;
    private boolean userStopped = false;
    private String textBeforePartial = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildInterface();
        prepareRecognizer();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        }
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(Color.rgb(247, 247, 247));

        TextView title = new TextView(this);
        title.setText("RH Bloc-note vocal");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(30, 30, 30));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("Préparation du micro…");
        status.setTextSize(16);
        status.setTextColor(Color.rgb(210, 95, 20));
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        editor = new EditText(this);
        editor.setTextSize(19);
        editor.setGravity(Gravity.TOP);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setBackgroundColor(Color.WHITE);
        editor.setHint("Parlez : le texte apparaît ici…");
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        scroll.addView(editor, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, 0);

        startButton = button("Arrêter");
        startButton.setOnClickListener(v -> {
            if (listening) stopByUser(); else {
                userStopped = false;
                startListening();
            }
        });
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button clear = button("Effacer");
        clear.setOnClickListener(v -> clearAll());
        buttons.addView(clear, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button copy = button("Copier");
        copy.setOnClickListener(v -> copyText());
        buttons.addView(copy, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button share = button("Partager");
        share.setOnClickListener(v -> shareText());
        buttons.addView(share, new LinearLayout.LayoutParams(0, dp(52), 1f));

        root.addView(buttons);
        setContentView(root);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }

    private void prepareRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Reconnaissance vocale indisponible sur ce téléphone.");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
    }

    private void startListening() {
        if (recognizer == null || recognizerIntent == null || isFinishing()) return;
        handler.removeCallbacksAndMessages(null);
        textBeforePartial = editor.getText().toString();
        try {
            recognizer.startListening(recognizerIntent);
            listening = true;
            startButton.setText("Arrêter");
            status.setText("🎤 Micro actif — parlez");
        } catch (Exception e) {
            listening = false;
            status.setText("Impossible de démarrer le micro.");
            scheduleRestart(900);
        }
    }

    private void stopByUser() {
        userStopped = true;
        listening = false;
        if (recognizer != null) recognizer.cancel();
        editor.setText(textBeforePartial);
        editor.setSelection(editor.length());
        startButton.setText("Démarrer");
        status.setText("Micro arrêté");
    }

    private void scheduleRestart(long delay) {
        listening = false;
        if (!userStopped && !isFinishing()) {
            handler.postDelayed(this::startListening, delay);
        }
    }

    private String firstResult(Bundle results) {
        if (results == null) return "";
        ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() ? "" : values.get(0).trim();
    }

    private void showPartial(String words) {
        editor.setText(textBeforePartial + (textBeforePartial.isEmpty() ? "" : " ") + words);
        editor.setSelection(editor.length());
    }

    private void acceptFinal(String words) {
        editor.setText(textBeforePartial);
        editor.setSelection(editor.length());
        if (words.isEmpty()) return;

        if (!executeCommand(words)) {
            remember();
            append(words, false);
            redo.clear();
        }
    }

    private boolean executeCommand(String spoken) {
        String n = normalize(spoken);
        if (!(n.startsWith("rh ") || n.startsWith("air h ") || n.startsWith("air ache ") ||
                n.startsWith("er ache "))) return false;

        String command = n.replaceFirst("^(rh|air h|air ache|er ache)\\s+", "").trim();

        if (command.startsWith("titre 1") || command.startsWith("titre un")) {
            String rest = command.replaceFirst("^titre (1|un)", "").trim();
            remember();
            append("\n\n" + (rest.isEmpty() ? "TITRE 1" : rest.toUpperCase(Locale.FRANCE)) + "\n", true);
        } else if (command.startsWith("titre 2") || command.startsWith("titre deux")) {
            String rest = command.replaceFirst("^titre (2|deux)", "").trim();
            remember();
            append("\n\n" + (rest.isEmpty() ? "Titre 2" : capitalize(rest)) + "\n", true);
        } else if (command.startsWith("titre 3") || command.startsWith("titre trois")) {
            String rest = command.replaceFirst("^titre (3|trois)", "").trim();
            remember();
            append("\n" + (rest.isEmpty() ? "Titre 3" : capitalize(rest)) + "\n", true);
        } else if (command.startsWith("paragraphe") || command.startsWith("nouveau paragraphe")) {
            remember();
            append("\n\n", true);
        } else if (command.startsWith("nouvelle ligne") || command.equals("ligne")) {
            remember();
            append("\n", true);
        } else if (command.startsWith("tableau")) {
            remember();
            append("\n| Colonne 1 | Colonne 2 |\n|---|---|\n|   |   |\n", true);
        } else if (command.startsWith("efface tout") || command.startsWith("nouveau document")) {
            clearAll();
        } else if (command.startsWith("efface") || command.startsWith("annule")) {
            undo();
        } else if (command.startsWith("refait") || command.startsWith("retablit")) {
            redo();
        } else if (command.startsWith("colle")) {
            pasteClipboard();
        } else if (command.startsWith("copie")) {
            copyText();
        } else if (command.startsWith("partage")) {
            shareText();
        } else {
            return false;
        }
        return true;
    }

    private String normalize(String value) {
        String s = Normalizer.normalize(value.toLowerCase(Locale.FRANCE), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return s.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.FRANCE) + s.substring(1);
    }

    private void append(String value, boolean exact) {
        String old = editor.getText().toString();
        String separator = (!exact && !old.isEmpty() && !old.endsWith("\n") && !old.endsWith(" ")) ? " " : "";
        editor.append(separator + value);
        editor.setSelection(editor.length());
        textBeforePartial = editor.getText().toString();
    }

    private void remember() {
        history.push(editor.getText().toString());
        while (history.size() > 50) history.removeLast();
    }

    private void undo() {
        if (history.isEmpty()) {
            Toast.makeText(this, "Rien à effacer", Toast.LENGTH_SHORT).show();
            return;
        }
        redo.push(editor.getText().toString());
        editor.setText(history.pop());
        editor.setSelection(editor.length());
        textBeforePartial = editor.getText().toString();
    }

    private void redo() {
        if (redo.isEmpty()) {
            Toast.makeText(this, "Rien à refaire", Toast.LENGTH_SHORT).show();
            return;
        }
        history.push(editor.getText().toString());
        editor.setText(redo.pop());
        editor.setSelection(editor.length());
        textBeforePartial = editor.getText().toString();
    }

    private void clearAll() {
        remember();
        editor.setText("");
        textBeforePartial = "";
        redo.clear();
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence value = clip.getItemAt(0).coerceToText(this);
                if (value != null) {
                    remember();
                    append(value.toString(), false);
                }
            }
        }
    }

    private void copyText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("RH Bloc-note", editor.getText()));
            Toast.makeText(this, "Texte copié", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, editor.getText().toString());
        startActivity(Intent.createChooser(send, "Partager le texte"));
    }

    @Override public void onReadyForSpeech(Bundle params) { status.setText("🎤 Je vous écoute…"); }
    @Override public void onBeginningOfSpeech() { status.setText("✍️ Écriture en cours…"); }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { status.setText("Traitement…"); }

    @Override
    public void onError(int error) {
        if (userStopped) return;
        editor.setText(textBeforePartial);
        editor.setSelection(editor.length());
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            status.setText("Autorisez le microphone dans les paramètres.");
            return;
        }
        status.setText("🎤 Reprise de l’écoute…");
        scheduleRestart(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1200 : 500);
    }

    @Override
    public void onResults(Bundle results) {
        String words = firstResult(results);
        acceptFinal(words);
        textBeforePartial = editor.getText().toString();
        status.setText("Texte ajouté — reprise du micro…");
        scheduleRestart(400);
    }

    @Override public void onPartialResults(Bundle partialResults) {
        String words = firstResult(partialResults);
        if (!words.isEmpty()) showPartial(words);
    }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == AUDIO_PERMISSION && grants.length > 0 &&
                grants[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            status.setText("Le microphone est nécessaire pour écrire.");
            startButton.setText("Démarrer");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
