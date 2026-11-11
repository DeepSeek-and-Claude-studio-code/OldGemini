package com.example.geminikitkat;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import org.conscrypt.Conscrypt;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionSpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.TlsVersion;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_FILE = 101;
    private static final int REQ_SPEECH = 102;

    private DrawerLayout drawerLayout;
    private LinearLayout chatContainer;
    private ScrollView scrollView;
    private EditText inputPrompt;
    private Spinner modelSpinner;
    private TextView tvAttached;
    private CheckBox cbSearch, cbCode, cbTts;
    private ListView historyListView;

    private SharedPreferences prefs;
    private OkHttpClient httpClient;
    private ChatDbHelper dbHelper;
    private TextToSpeech tts;

    private long currentSessionId = -1;
    private JSONArray currentChatHistory = new JSONArray();

    // Данные прикрепленного файла (картинка, видео, аудио или документ)
    private String attachedBase64 = null;
    private String attachedMimeType = null;
    private String attachedName = null;

    // Для записи голосовых сообщений напрямую в Gemini
    private MediaRecorder mediaRecorder;
    private File audioFile;
    private boolean isRecording = false;

    private ArrayList<Long> sessionIds = new ArrayList<>();
    private ArrayList<String> sessionTitles = new ArrayList<>();
    private ArrayAdapter<String> historyAdapter;

    private final String[] MODELS = {
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-1.5-pro",
            "gemini-1.5-flash"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Включаем современный TLS 1.2 / 1.3 на Android 4.4.2 KitKat
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Exception e) {
            e.printStackTrace();
        }

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("GeminiKitKatPrefs", MODE_PRIVATE);
        dbHelper = new ChatDbHelper(this);

        // Инициализация синтезатора речи (озвучка ответов)
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("ru", "RU"));
            }
        });

        initViews();
        initHttpClient();
        loadSessionsList();

        if (sessionIds.isEmpty()) {
            startNewChat();
        } else {
            loadChatSession(sessionIds.get(0));
        }

        if (getApiKey().isEmpty()) {
            showSettingsDialog();
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        chatContainer = findViewById(R.id.chatContainer);
        scrollView = findViewById(R.id.scrollView);
        inputPrompt = findViewById(R.id.inputPrompt);
        modelSpinner = findViewById(R.id.modelSpinner);
        tvAttached = findViewById(R.id.tvAttached);
        cbSearch = findViewById(R.id.cbSearch);
        cbCode = findViewById(R.id.cbCode);
        cbTts = findViewById(R.id.cbTts);
        historyListView = findViewById(R.id.historyListView);

        // Делаем нижнюю панель скругленной капсулой (как в оригинальном Gemini)
        LinearLayout inputBar = findViewById(R.id.inputBar);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.parseColor("#1E1F20"));
        barBg.setCornerRadius(60f);
        inputBar.setBackground(barBg);

        // Настраиваем список моделей с белым текстом
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, MODELS) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.parseColor("#E3E3E3"));
                tv.setTextSize(15f);
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setBackgroundColor(Color.parseColor("#1E1F20"));
                tv.setTextColor(Color.parseColor("#E3E3E3"));
                tv.setPadding(24, 24, 24, 24);
                return tv;
            }
        };
        modelSpinner.setAdapter(modelAdapter);

        // Кнопки шапки и меню
        findViewById(R.id.btnMenu).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btnNewChat).setOnClickListener(v -> {
            startNewChat();
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        // Сброс прикрепленного файла по клику на плашку
        tvAttached.setOnClickListener(v -> clearAttachment());

        // Кнопка "+" (Выбор фото, видео или любого файла)
        findViewById(R.id.btnAttach).setOnClickListener(v -> pickFile());

        // Кнопка "🎤" (Голосовой ввод или запись аудио для Gemini)
        findViewById(R.id.btnVoice).setOnClickListener(v -> showVoiceOptions());

        // Кнопка отправки "➤"
        findViewById(R.id.btnSend).setOnClickListener(v -> {
            String text = inputPrompt.getText().toString().trim();
            if (!text.isEmpty() || attachedBase64 != null) {
                inputPrompt.setText("");
                sendMessage(text);
            }
        });

        // Список истории чатов
        historyAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, sessionTitles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.parseColor("#E3E3E3"));
                return tv;
            }
        };
        historyListView.setAdapter(historyAdapter);

        historyListView.setOnItemClickListener((parent, view, position, id) -> {
            loadChatSession(sessionIds.get(position));
            drawerLayout.closeDrawer(GravityCompat.START);
        });

        historyListView.setOnItemLongClickListener((parent, view, position, id) -> {
            long delId = sessionIds.get(position);
            dbHelper.deleteSession(delId);
            loadSessionsList();
            if (delId == currentSessionId) {
                startNewChat();
            }
            Toast.makeText(this, "Чат удален", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void initHttpClient() {
        try {
            ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                    .build();

            httpClient = new OkHttpClient.Builder()
                    .connectionSpecs(Collections.singletonList(spec))
                    .connectTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(90, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            httpClient = new OkHttpClient();
        }
    }

    // ================== ФАЙЛЫ, КАРТИНКИ И ВИДЕО ==================

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Выберите фото, видео или файл"), REQ_FILE);
    }

    private void clearAttachment() {
        attachedBase64 = null;
        attachedMimeType = null;
        attachedName = null;
        tvAttached.setVisibility(View.GONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_FILE && data.getData() != null) {
            Uri uri = data.getData();
            processSelectedUri(uri);
        } else if (requestCode == REQ_SPEECH) {
            ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (res != null && !res.isEmpty()) {
                inputPrompt.setText(res.get(0));
            }
        }
    }

    private void processSelectedUri(Uri uri) {
        try {
            String mime = getContentResolver().getType(uri);
            if (mime == null) mime = "image/jpeg";

            InputStream is = getContentResolver().openInputStream(uri);
            byte[] bytes = readBytes(is);
            is.close();

            // Защита от переполнения памяти на Android 4.4 (лимит ~15 МБ)
            if (bytes.length > 15 * 1024 * 1024) {
                Toast.makeText(this, "Файл слишком большой для памяти Android 4.4 (макс 15 МБ)", Toast.LENGTH_LONG).show();
                return;
            }

            // Если это картинка, слегка оптимизируем размер
            if (mime.startsWith("image/")) {
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int quality = bytes.length > 2 * 1024 * 1024 ? 70 : 90;
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                    bytes = baos.toByteArray();
                    mime = "image/jpeg";
                }
            }

            attachedBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            attachedMimeType = mime;
            attachedName = uri.getLastPathSegment();

            tvAttached.setText("📎 Прикреплено: " + mime + " (" + (bytes.length / 1024) + " КБ) — нажмите для отмены");
            tvAttached.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка чтения файла: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private byte[] readBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
        return byteBuffer.toByteArray();
    }

    // ================== ГОЛОСОВОЙ ВВОД И ЗАПИСЬ АУДИО ==================

    private void showVoiceOptions() {
        if (isRecording) {
            stopAudioRecording();
            return;
        }
        String[] options = {
                "🎙 Записать голосовое напрямую в Gemini (работает везде)",
                "🗣 Распознавание речи Google (системная диктовка)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Голосовой ввод")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        startAudioRecording();
                    } else {
                        startSystemSpeechRecognizer();
                    }
                })
                .show();
    }

    private void startSystemSpeechRecognizer() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "Системный голосовой ввод недоступен, используйте запись аудио!", Toast.LENGTH_LONG).show();
        }
    }

    private void startAudioRecording() {
        try {
            audioFile = new File(getCacheDir(), "voice_msg.3gp");
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;

            Button btnVoice = findViewById(R.id.btnVoice);
            btnVoice.setText("⏹");
            Toast.makeText(this, "Идет запись... Нажмите ⏹ чтобы остановить", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка микрофона: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopAudioRecording() {
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;

            Button btnVoice = findViewById(R.id.btnVoice);
            btnVoice.setText("🎤");

            FileInputStream fis = new FileInputStream(audioFile);
            byte[] bytes = readBytes(fis);
            fis.close();

            attachedBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            attachedMimeType = "audio/3gpp";
            attachedName = "Голосовое сообщение";

            tvAttached.setText("🎤 Голосовое сообщение записано (" + (bytes.length / 1024) + " КБ)");
            tvAttached.setVisibility(View.VISIBLE);

            if (inputPrompt.getText().toString().trim().isEmpty()) {
                inputPrompt.setText("Прослушай это голосовое сообщение и ответь на него.");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка остановки записи: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ================== ОТПРАВКА ЗАПРОСА В GEMINI API ==================

    private void sendMessage(String promptText) {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Сначала введите API ключ в настройках!", Toast.LENGTH_SHORT).show();
            showSettingsDialog();
            return;
        }

        if (promptText.isEmpty() && attachedBase64 != null) {
            promptText = "Проанализируй этот файл";
        }

        // Сохраняем локальные копии вложения перед очисткой
        final String sendBase64 = attachedBase64;
        final String sendMime = attachedMimeType;
        clearAttachment();

        // Отображаем в UI
        String displayUserText = sendMime != null ? "[📎 " + sendMime + "]\n" + promptText : promptText;
        addBubbleToUi(displayUserText, true, sendMime != null && sendMime.startsWith("image/") ? sendBase64 : null);

        try {
            JSONArray parts = new JSONArray();
            if (sendBase64 != null && sendMime != null) {
                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", sendMime);
                inlineData.put("data", sendBase64);
                parts.put(new JSONObject().put("inline_data", inlineData));
            }
            parts.put(new JSONObject().put("text", promptText));

            JSONObject userContent = new JSONObject();
            userContent.put("role", "user");
            userContent.put("parts", parts);

            currentChatHistory.put(userContent);

            // Обновляем название чата по первому сообщению и сохраняем в SQLite
            if (currentChatHistory.length() == 1) {
                String title = promptText.length() > 30 ? promptText.substring(0, 30) + "..." : promptText;
                dbHelper.updateSessionTitle(currentSessionId, title);
                loadSessionsList();
            }
            dbHelper.updateSessionJson(currentSessionId, currentChatHistory.toString());

            // Собираем полный запрос
            JSONObject requestJson = new JSONObject();
            requestJson.put("contents", currentChatHistory);

            // Системная инструкция (если задана)
            String sysInst = getSystemInstruction();
            if (!sysInst.isEmpty()) {
                JSONObject sysObj = new JSONObject();
                sysObj.put("parts", new JSONArray().put(new JSONObject().put("text", sysInst)));
                requestJson.put("system_instruction", sysObj);
            }

            // Инструменты оригинального Gemini (Google Search и выполнение Python кода)
            JSONArray tools = new JSONArray();
            if (cbSearch.isChecked()) {
                tools.put(new JSONObject().put("google_search", new JSONObject()));
            }
            if (cbCode.isChecked()) {
                tools.put(new JSONObject().put("code_execution", new JSONObject()));
            }
            if (tools.length() > 0) {
                requestJson.put("tools", tools);
            }

            String model = modelSpinner.getSelectedItem().toString();
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), requestJson.toString());
            Request request = new Request.Builder().url(url).post(body).build();

            final TextView loadingBubble = addBubbleToUi("✨ Gemini думает...", false, null);

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> loadingBubble.setText("❌ Ошибка сети: " + e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String respStr = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        try {
                            JSONObject json = new JSONObject(respStr);
                            JSONObject candidate = json.getJSONArray("candidates").getJSONObject(0);
                            JSONObject content = candidate.getJSONObject("content");
                            JSONArray respParts = content.getJSONArray("parts");

                            StringBuilder fullReply = new StringBuilder();
                            for (int i = 0; i < respParts.length(); i++) {
                                JSONObject p = respParts.getJSONObject(i);
                                if (p.has("text")) {
                                    fullReply.append(p.getString("text")).append("\n");
                                }
                                if (p.has("executableCode")) {
                                    String code = p.getJSONObject("executableCode").getString("code");
                                    fullReply.append("\n```python\n").append(code).append("\n```\n");
                                }
                                if (p.has("codeExecutionResult")) {
                                    String out = p.getJSONObject("codeExecutionResult").getString("output");
                                    fullReply.append("\n[Результат кода]:\n").append(out).append("\n");
                                }
                            }

                            final String finalAnswer = fullReply.toString().trim();

                            // Сохраняем ответ модели в историю и БД
                            JSONObject cleanModelContent = new JSONObject();
                            cleanModelContent.put("role", "model");
                            cleanModelContent.put("parts", new JSONArray().put(new JSONObject().put("text", finalAnswer)));
                            currentChatHistory.put(cleanModelContent);
                            dbHelper.updateSessionJson(currentSessionId, currentChatHistory.toString());

                            runOnUiThread(() -> {
                                loadingBubble.setText("✨ " + finalAnswer);
                                if (cbTts.isChecked() && tts != null) {
                                    tts.speak(finalAnswer, TextToSpeech.QUEUE_FLUSH, null);
                                }
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> loadingBubble.setText("❌ Ошибка разбора ответа: " + e.getMessage()));
                        }
                    } else {
                        runOnUiThread(() -> loadingBubble.setText("❌ Ошибка API (" + response.code() + "):\n" + respStr));
                    }
                }
            });

        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ================== ИНТЕРФЕЙС ЧАТА И ИСТОРИЯ (SQLite) ==================

    private TextView addBubbleToUi(String text, boolean isUser, String imageBase64) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        wrapParams.setMargins(0, 12, 0, 12);
        wrapParams.gravity = isUser ? Gravity.END : Gravity.START;
        wrapper.setLayoutParams(wrapParams);

        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setCornerRadius(36f);
        bubbleBg.setColor(Color.parseColor(isUser ? "#282A2C" : "#1E1F20"));
        wrapper.setBackground(bubbleBg);
        wrapper.setPadding(32, 24, 32, 24);

        if (imageBase64 != null) {
            try {
                byte[] decoded = Base64.decode(imageBase64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                if (bmp != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setAdjustViewBounds(true);
                    iv.setMaxHeight(450);
                    wrapper.addView(iv);
                }
            } catch (Exception ignored) {}
        }

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#E3E3E3"));
        tv.setTextSize(15f);
        tv.setTextIsSelectable(true);
        wrapper.addView(tv);

        chatContainer.addView(wrapper);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        return tv;
    }

    private void startNewChat() {
        currentSessionId = dbHelper.createSession("Новый чат");
        currentChatHistory = new JSONArray();
        chatContainer.removeAllViews();
        addBubbleToUi("✨ Привет! Я Gemini. Чем могу помочь сегодня?", false, null);
        loadSessionsList();
    }

    private void loadChatSession(long sessionId) {
        currentSessionId = sessionId;
        chatContainer.removeAllViews();
        String jsonStr = dbHelper.getSessionJson(sessionId);
        try {
            currentChatHistory = new JSONArray(jsonStr);
            if (currentChatHistory.length() == 0) {
                addBubbleToUi("✨ Привет! Я Gemini. Чем могу помочь сегодня?", false, null);
            }
            for (int i = 0; i < currentChatHistory.length(); i++) {
                JSONObject item = currentChatHistory.getJSONObject(i);
                boolean isUser = "user".equals(item.optString("role"));
                JSONArray parts = item.optJSONArray("parts");
                StringBuilder sb = new StringBuilder();
                String imgB64 = null;
                if (parts != null) {
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject p = parts.getJSONObject(j);
                        if (p.has("text")) sb.append(p.getString("text"));
                        if (p.has("inline_data")) {
                            JSONObject in = p.getJSONObject("inline_data");
                            String mime = in.optString("mime_type");
                            sb.insert(0, "[📎 " + mime + "]\n");
                            if (mime.startsWith("image/")) imgB64 = in.optString("data");
                        }
                    }
                }
                addBubbleToUi((isUser ? "" : "✨ ") + sb.toString(), isUser, imgB64);
            }
        } catch (Exception e) {
            currentChatHistory = new JSONArray();
        }
    }

    private void loadSessionsList() {
        sessionIds.clear();
        sessionTitles.clear();
        Cursor c = dbHelper.getAllSessions();
        while (c.moveToNext()) {
            sessionIds.add(c.getLong(0));
            sessionTitles.add(c.getString(1));
        }
        c.close();
        if (historyAdapter != null) historyAdapter.notifyDataSetChanged();
    }

    // ================== НАСТРОЙКИ (API КЛЮЧ + СИСТЕМНЫЙ ПРОМПТ) ==================

    private String getApiKey() {
        return prefs.getString("api_key", "");
    }

    private String getSystemInstruction() {
        return prefs.getString("sys_inst", "Отвечай подробно, понятно и на русском языке.");
    }

    private void showSettingsDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        TextView tv1 = new TextView(this);
        tv1.setText("Ваш Gemini API Ключ (из Google AI Studio):");
        layout.addView(tv1);

        final EditText etKey = new EditText(this);
        etKey.setHint("AIzaSy...");
        etKey.setText(getApiKey());
        layout.addView(etKey);

        TextView tv2 = new TextView(this);
        tv2.setText("\nСистемная инструкция (характер ИИ):");
        layout.addView(tv2);

        final EditText etSys = new EditText(this);
        etSys.setHint("Например: Ты опытный программист...");
        etSys.setText(getSystemInstruction());
        layout.addView(etSys);

        new AlertDialog.Builder(this)
                .setTitle("⚙ Настройки Gemini")
                .setView(layout)
                .setPositiveButton("Сохранить", (d, w) -> {
                    prefs.edit()
                            .putString("api_key", etKey.getText().toString().trim())
                            .putString("sys_inst", etSys.getText().toString().trim())
                            .apply();
                    Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ================== БАЗА ДАННЫХ SQLITE ДЛЯ ИСТОРИИ ЧАТОВ ==================

    private static class ChatDbHelper extends SQLiteOpenHelper {
        public ChatDbHelper(Context context) {
            super(context, "gemini_chats.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, json TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

        public long createSession(String title) {
            ContentValues cv = new ContentValues();
            cv.put("title", title);
            cv.put("json", "[]");
            return getWritableDatabase().insert("sessions", null, cv);
        }

        public void updateSessionJson(long id, String json) {
            ContentValues cv = new ContentValues();
            cv.put("json", json);
            getWritableDatabase().update("sessions", cv, "_id=?", new String[]{String.valueOf(id)});
        }

        public void updateSessionTitle(long id, String title) {
            ContentValues cv = new ContentValues();
            cv.put("title", title);
            getWritableDatabase().update("sessions", cv, "_id=?", new String[]{String.valueOf(id)});
        }

        public String getSessionJson(long id) {
            Cursor c = getReadableDatabase().rawQuery("SELECT json FROM sessions WHERE _id=?", new String[]{String.valueOf(id)});
            String res = "[]";
            if (c.moveToFirst()) res = c.getString(0);
            c.close();
            return res;
        }

        public Cursor getAllSessions() {
            return getReadableDatabase().rawQuery("SELECT _id, title FROM sessions ORDER BY _id DESC", null);
        }

        public void deleteSession(long id) {
            getWritableDatabase().delete("sessions", "_id=?", new String[]{String.valueOf(id)});
        }
    }
}
