package com.sawoo.gametranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;

import com.sawoo.gametranslator.core.GameProject;
import com.sawoo.gametranslator.core.GeminiTranslator;
import com.sawoo.gametranslator.core.Lang;
import com.sawoo.gametranslator.core.TranslationMemory;
import com.sawoo.gametranslator.core.Translator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** 화면을 꺼도 번역이 멈추지 않도록 알림과 함께 작업을 돌린다. */
public final class TranslateService extends Service {
    static final String ACTION_ANALYZE = "analyze";
    static final String ACTION_TRANSLATE = "translate";
    private static final String CHANNEL = "translate";
    private static final int NOTIFICATION = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!Job.busy()) return;
            notifyProgress();
            main.postDelayed(this, 1500);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification n = build("게임 번역 준비 중", 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION, n);
        }
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String action = intent.getAction();
        final Uri uri = intent.getData();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameTranslator:job");
        wake.acquire(6 * 60 * 60 * 1000L);

        Job.cancel = false;
        Job.error = "";
        Job.stage = ACTION_ANALYZE.equals(action) ? Job.Stage.ANALYZING : Job.Stage.TRANSLATING;
        main.post(ticker);

        new Thread(() -> {
            try {
                if (ACTION_ANALYZE.equals(action)) analyze(uri);
                else translate();
            } catch (InterruptedException e) {
                Job.stage = Job.project != null ? Job.Stage.READY : Job.Stage.IDLE;
            } catch (Throwable t) {
                String m = t.getMessage();
                Job.error = m != null && !m.isEmpty() ? m : t.getClass().getSimpleName();
                Job.stage = Job.Stage.ERROR;
            } finally {
                finish();
            }
        }, "translate-job").start();
        return START_NOT_STICKY;
    }

    /** 안드로이드 15의 백그라운드 작업 시간 제한. 지금까지 번역한 내용은 저장돼 있다. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Job.cancel = true;
    }

    private void analyze(Uri uri) throws Exception {
        Job.project = null;
        Job.output = null;
        File dir = jobDir();
        for (File f : dir.listFiles()) f.delete();

        String name = displayName(uri);
        File input = new File(dir, "input.bin");
        Job.PROGRESS.update(0, 0, "파일 불러오는 중");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(input)) {
            if (in == null) throw new IOException("파일을 열 수 없어요.");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (Job.cancel) throw new InterruptedException();
                out.write(buf, 0, n);
            }
        }
        Prefs prefs = new Prefs(this);
        Job.PROGRESS.update(0, 0, "게임 구조 분석 중");
        Job.project = GameProject.analyze(input, name, prefs.lang(), Job.PROGRESS);
        Job.stage = Job.Stage.READY;
    }

    private void translate() throws Exception {
        GameProject project = Job.project;
        Prefs prefs = new Prefs(this);
        Map<String, String> glossary = GameProject.parseGlossary(prefs.glossary());

        Job.PROGRESS.update(0, 1, "번역 엔진 준비 중");
        Translator translator = createTranslator(prefs, project.lang, glossary);
        TranslationMemory memory = new TranslationMemory(memoryFile(project, prefs));
        GameProject.Result result;
        try {
            result = project.translate(translator, memory, glossary, Job.PROGRESS);
        } finally {
            translator.close();
        }

        Job.stage = Job.Stage.BUILDING;
        Job.PROGRESS.update(0, 1, "결과 파일 만드는 중");
        File dir = jobDir();
        File built = new File(dir, "built.tmp");
        project.build(built, memory, loadFont(), Job.PROGRESS);

        File output = new File(dir, "output.bin");
        output.delete();
        if (project.kind == GameProject.Kind.APK) {
            Job.PROGRESS.update(0, 1, "APK 서명 중");
            ApkSign.sign(built, output);
            built.delete();
        } else if (!built.renameTo(output)) {
            throw new IOException("결과 파일을 저장하지 못했어요.");
        }
        Job.output = output;
        Job.translated = result.translated;
        Job.failed = result.failed;
        Job.stage = Job.Stage.DONE;
    }

    private static Translator createTranslator(Prefs prefs, Lang lang, Map<String, String> glossary) throws Exception {
        if (Prefs.ENGINE_GEMINI.equals(prefs.engine())) {
            if (prefs.apiKey().isEmpty()) throw new IllegalArgumentException("Gemini API 키를 입력해 주세요.");
            return new GeminiTranslator(prefs.apiKey(), prefs.model(), lang, glossary);
        }
        return new MlKitTranslator(lang);
    }

    /** 게임과 엔진 조합마다 번역 기록을 따로 둬서 이어하기에 쓴다. */
    private File memoryFile(GameProject p, Prefs prefs) throws Exception {
        String id = p.displayName + "|" + p.input.length() + "|" + p.lang + "|" + prefs.engine()
                + "|" + prefs.model() + "|" + prefs.glossary();
        byte[] h = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
        File dir = new File(getFilesDir(), "memory");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("저장 공간을 만들 수 없어요.");
        return new File(dir, new BigInteger(1, h).toString(16) + ".jsonl");
    }

    private byte[] loadFont() {
        try (InputStream in = getAssets().open("NanumGothic-Regular.ttf")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private File jobDir() throws IOException {
        File d = new File(getFilesDir(), "job");
        if (!d.exists() && !d.mkdirs()) throw new IOException("저장 공간을 만들 수 없어요.");
        return d;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {
            // 이름을 못 읽는 파일 제공 앱
        }
        String last = uri.getLastPathSegment();
        return last != null ? last.substring(last.lastIndexOf('/') + 1) : "game";
    }

    private void finish() {
        main.post(() -> {
            main.removeCallbacks(ticker);
            if (wake != null && wake.isHeld()) wake.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Job.stage == Job.Stage.DONE) {
                nm.notify(2, build("번역이 끝났어요. 눌러서 저장하세요", 0, 0));
            } else if (Job.stage == Job.Stage.ERROR) {
                nm.notify(2, build("번역이 중단됐어요: " + Job.error, 0, 0));
            }
            stopSelf();
        });
    }

    private void notifyProgress() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION, build(Job.message, Job.done, Job.total));
    }

    private Notification build(String text, int done, int total) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("게임 번역기")
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true);
        if (total > 0) b.setProgress(total, Math.min(done, total), false);
        return b.build();
    }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CHANNEL, "번역 진행 상황", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
}
