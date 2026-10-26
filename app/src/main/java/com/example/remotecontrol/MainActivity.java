package com.example.remotecontrol;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private ImageView screenView;
    private EditText ipInput;
    private Button connectBtn;
    private OkHttpClient client;
    private String baseUrl = null;
    private volatile boolean streaming = false;
    private Thread streamThread;
    private GestureDetector gestureDetector;
    private RectF imageRect = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float downX, downY;
    private long downTime;
    private boolean moved = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        screenView = findViewById(R.id.screenView);
        ipInput = findViewById(R.id.ipInput);
        connectBtn = findViewById(R.id.connectBtn);

        client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();

        connectBtn.setOnClickListener(v -> {
            if (streaming) disconnect();
            else {
                String ip = ipInput.getText().toString().trim();
                if (ip.isEmpty()) { Toast.makeText(this, "Введите IP", Toast.LENGTH_SHORT).show(); return; }
                baseUrl = "http://" + ip + ":5000";
                startStream();
            }
        });

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

        findViewById(R.id.btnEsc).setOnClickListener(v -> send("/key", "key=esc"));
        findViewById(R.id.btnEnter).setOnClickListener(v -> send("/key", "key=enter"));
        findViewById(R.id.btnKeyboard).setOnClickListener(v -> {
            final EditText in = new EditText(this);
            new AlertDialog.Builder(this).setTitle("Ввод текста").setView(in)
                .setPositiveButton("Отправить", (d, w) -> {
                    try { send("/type", "text=" + URLEncoder.encode(in.getText().toString(), "UTF-8")); } catch (Exception ignored) {}
                }).setNegativeButton("Отмена", null).show();
        });
    }

    private float[] toNorm(float px, float py) {
        if (imageRect.width() <= 0 || !imageRect.contains(px, py)) return null;
        return new float[]{
            Math.max(0f, Math.min(1f, (px - imageRect.left) / imageRect.width())),
            Math.max(0f, Math.min(1f, (py - imageRect.top) / imageRect.height()))
        };
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
        Request req = new Request.Builder().url(baseUrl + path)
            .post(RequestBody.create(body, MediaType.parse("application/x-www-form-urlencoded"))).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call c, java.io.IOException e) {}
            @Override public void onResponse(Call c, Response r) { r.close(); }
        });
    }

    private void startStream() {
        streaming = true; connectBtn.setText("Disconnect");
        streamThread = new Thread(this::streamLoop);
        streamThread.setDaemon(true); streamThread.start();
    }

    private void disconnect() {
        streaming = false; connectBtn.setText("Connect"); baseUrl = null; screenView.setImageDrawable(null);
    }

    private void streamLoop() {
        while (streaming) {
            try {
                Request req = new Request.Builder().url(baseUrl + "/stream").build();
                Response resp = client.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());
                InputStream is = resp.body().byteStream();
                java.io.ByteArrayOutputStream hdr = new java.io.ByteArrayOutputStream();
                int contentLength = -1, b;
                while (streaming && (b = is.read()) != -1) {
                    if (b == '\n') {
                        String line = hdr.toString("UTF-8").trim(); hdr.reset();
                        if (line.startsWith("Content-Length:")) contentLength = Integer.parseInt(line.substring(15).trim());
                        if (line.isEmpty() && contentLength > 0) {
                            byte[] data = new byte[contentLength];
                            int read = 0;
                            while (read < contentLength) {
                                int r = is.read(data, read, contentLength - read); if (r < 0) break; read += r;
                            }
                            final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, read);
                            if (bmp != null) ui.post(() -> { screenView.setImageBitmap(bmp); updateImageRect(); });
                            contentLength = -1;
                        }
                    } else if (b != '\r') hdr.write(b);
                }
                resp.close();
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Стрим: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            }
        }
    }
    @Override protected void onDestroy() { super.onDestroy(); streaming = false; }
}
