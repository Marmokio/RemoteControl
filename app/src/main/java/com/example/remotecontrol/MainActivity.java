package com.example.remotecontrol;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

public class MainActivity extends AppCompatActivity {
    private ImageView screenView;
    private EditText ipInput;
    private Button connectBtn, scanBtn, menuBtn;
    private SeekBar qualityBar;
    private OkHttpClient client;
    private String baseUrl = null;
    private volatile boolean streaming = false;
    private Thread streamThread;
    private GestureDetector gestureDetector;
    private RectF imageRect = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float downX, downY; private long downTime; private boolean moved = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        screenView = findViewById(R.id.screenView);
        ipInput = findViewById(R.id.ipInput);
        connectBtn = findViewById(R.id.connectBtn);
        scanBtn = findViewById(R.id.scanBtn);
        menuBtn = findViewById(R.id.menuBtn);
        qualityBar = findViewById(R.id.qualityBar);

        client = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();

        connectBtn.setOnClickListener(v -> {
            if (streaming) disconnect();
            else {
                String ip = ipInput.getText().toString().trim();
                if (ip.isEmpty()) return;
                baseUrl = "http://" + ip + ":5000";
                startStream();
            }
        });

        // 3. Сканер сети
        scanBtn.setOnClickListener(v -> {
            Toast.makeText(this, "Поиск сервера в сети...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String subnet = "192.168.0."; // если у тебя 192.168.1.x - поменяй тут
                for (int i = 1; i < 255; i++) {
                    String testIp = subnet + i;
                    try {
                        Request req = new Request.Builder().url("http://" + testIp + ":5000/discover").build();
                        Response resp = client.newCall(req).execute();
                        if (resp.isSuccessful()) {
                            String res = resp.body().string();
                            if (res.contains("REMOTE_CONTROL_SERVER")) {
                                final String foundIp = testIp;
                                ui.post(() -> { ipInput.setText(foundIp); Toast.makeText(this, "Найден ПК: " + foundIp, Toast.LENGTH_LONG).show(); });
                                return;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                ui.post(() -> Toast.makeText(this, "ПК не найден", Toast.LENGTH_SHORT).show());
            }).start();
        });

        // Меню с новыми фишками
        menuBtn.setOnClickListener(v -> showMenu());

        // Ползунок качества (4)
        qualityBar.setMax(60); qualityBar.setProgress(35);
        qualityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                if (baseUrl != null) send("/set_quality", "q=" + (p + 30));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        setupGestures();
        findViewById(R.id.btnEnter).setOnClickListener(v -> send("/key", "key=enter"));
        findViewById(R.id.btnKeyboard).setOnClickListener(v -> {
            final EditText in = new EditText(this);
            new AlertDialog.Builder(this).setTitle("Текст").setView(in)
                .setPositiveButton("Ввод", (d, w) -> {
                    try { send("/type", "text=" + URLEncoder.encode(in.getText().toString(), "UTF-8")); } catch (Exception ignored) {}
                }).show();
        });
    }

    private void showMenu() {
        String[] items = {
            "Разрешение 720p", "Разрешение 480p", "Разрешение 360p",
            "Сделать скриншот с ПК",
            "Отправить файл с телефона на ПК",
            "Открыть файл/программу на ПК",
            "Громкость +", "Громкость -", "Mute", "Заблокировать ПК"
        };
        new AlertDialog.Builder(this).setTitle("Меню").setItems(items, (d, w) -> {
            switch (w) {
                case 0: send("/set_res", "res=720"); break;
                case 1: send("/set_res", "res=480"); break;
                case 2: send("/set_res", "res=360"); break;
                case 3: downloadScreenshot(); break;
                case 4: pickFileToUpload(); break;
                case 5: send("/open_file", ""); break;
                case 6: send("/key", "key=volumeup"); break;
                case 7: send("/key", "key=volumedown"); break;
                case 8: send("/key", "key=volumemute"); break;
                case 9: send("/hotkey", "keys=win%2Bl"); break;
            }
        }).show();
    }

    private void pickFileToUpload() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, 101);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 101 && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                byte[] bytes = new byte[is.available()];
                is.read(bytes);
                is.close();
                Toast.makeText(this, "Отправка файла...", Toast.LENGTH_SHORT).show();
                Request req2 = new Request.Builder().url(baseUrl + "/upload")
                    .header("X-Filename", "phone_file_" + System.currentTimeMillis() + ".bin")
                    .post(RequestBody.create(bytes, MediaType.parse("*/*"))).build();
                client.newCall(req2).enqueue(new Callback() {
                    public void onFailure(Call c, java.io.IOException e) { ui.post(() -> Toast.makeText(MainActivity.this, "Ошибка", Toast.LENGTH_SHORT).show()); }
                    public void onResponse(Call c, Response r) { ui.post(() -> Toast.makeText(MainActivity.this, "Файл на ПК!", Toast.LENGTH_SHORT).show()); }
                });
            } catch (Exception e) {}
        }
    }

    private void downloadScreenshot() {
        Toast.makeText(this, "Делаем скриншот...", Toast.LENGTH_SHORT).show();
        Request req = new Request.Builder().url(baseUrl + "/screenshot").post(RequestBody.create("", null)).build();
        client.newCall(req).enqueue(new Callback() {
            public void onFailure(Call c, java.io.IOException e) {}
            public void onResponse(Call c, Response r) {
                try {
                    byte[] imgBytes = Base64.decode(r.body().string(), Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                    ui.post(() -> {
                        ImageView iv = new ImageView(MainActivity.this);
                        iv.setImageBitmap(bmp);
                        new AlertDialog.Builder(MainActivity.this).setTitle("Скриншот ПК").setView(iv).setPositiveButton("ОК", null).show();
                    });
                } catch (Exception e) {}
            }
        });
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { send("/doubleclick", ""); return true; }
        });
        screenView.setOnTouchListener((v, e) -> {
            gestureDetector.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); downTime = System.currentTimeMillis(); moved = false;
                    float[] p0 = toNorm(e.getX(), e.getY()); if (p0 != null) send("/move", "x=" + p0[0] + "&y=" + p0[1]);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX()-downX) > 15 || Math.abs(e.getY()-downY) > 15) moved = true;
                    float[] p1 = toNorm(e.getX(), e.getY()); if (p1 != null) send("/move", "x=" + p1[0] + "&y=" + p1[1]);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && System.currentTimeMillis()-downTime < 300) send("/click", "");
                    return true;
            }
            return false;
        });
    }

    private float[] toNorm(float px, float py) {
        if (imageRect.width() <= 0 || !imageRect.contains(px, py)) return null;
        return new float[]{ Math.max(0f, Math.min(1f, (px - imageRect.left) / imageRect.width())),
                            Math.max(0f, Math.min(1f, (py - imageRect.top) / imageRect.height())) };
    }
    private void updateImageRect() {
        if (screenView.getDrawable() == null) return;
        int dw = screenView.getDrawable().getIntrinsicWidth(), dh = screenView.getDrawable().getIntrinsicHeight();
        int vw = screenView.getWidth(), vh = screenView.getHeight();
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return;
        float s = Math.min((float) vw / dw, (float) vh / dh);
        float w = dw * s, h = dh * s;
        imageRect.set((vw-w)/2f, (vh-h)/2f, (vw+w)/2f, (vh+h)/2f);
    }
    private void send(String path, String body) {
        if (baseUrl == null) return;
        Request req = new Request.Builder().url(baseUrl + path).post(RequestBody.create(body, MediaType.parse("application/x-www-form-urlencoded"))).build();
        client.newCall(req).enqueue(new Callback() {
            public void onFailure(Call c, java.io.IOException e) {}
            public void onResponse(Call c, Response r) { r.close(); }
        });
    }
    private void startStream() {
        streaming = true; connectBtn.setText("Stop");
        streamThread = new Thread(this::streamLoop); streamThread.start();
    }
    private void disconnect() {
        streaming = false; connectBtn.setText("Go"); baseUrl = null; screenView.setImageDrawable(null);
    }
    private void streamLoop() {
        while (streaming) {
            try {
                Request req = new Request.Builder().url(baseUrl + "/stream").build();
                Response resp = client.newCall(req).execute();
                InputStream is = resp.body().byteStream();
                java.io.ByteArrayOutputStream hdr = new java.io.ByteArrayOutputStream();
                int contentLength = -1, b;
                while (streaming && (b = is.read()) != -1) {
                    if (b == '\n') {
                        String line = hdr.toString("UTF-8").trim(); hdr.reset();
                        if (line.startsWith("Content-Length:")) contentLength = Integer.parseInt(line.substring(15).trim());
                        if (line.isEmpty() && contentLength > 0) {
                            byte[] data = new byte[contentLength]; int read = 0;
                            while (read < contentLength) { int r = is.read(data, read, contentLength - read); if (r < 0) break; read += r; }
                            final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, read);
                            if (bmp != null) ui.post(() -> { screenView.setImageBitmap(bmp); updateImageRect(); });
                            contentLength = -1;
                        }
                    } else if (b != '\r') hdr.write(b);
                }
                resp.close();
            } catch (Exception e) { try { Thread.sleep(1000); } catch (Exception ignored) {} }
        }
    }
}
