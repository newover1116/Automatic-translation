package com.sawoo.gametranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sawoo.gametranslator.core.GameProject;
import com.sawoo.gametranslator.core.Lang;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final int SAVE = 2;
    private static final int MAX_LISTED = 150;

    private static final int BG = Color.rgb(16, 19, 28);
    private static final int CARD = Color.rgb(27, 32, 45);
    private static final int FIELD = Color.rgb(36, 42, 58);
    private static final int TEXT = Color.rgb(238, 241, 247);
    private static final int MUTED = Color.rgb(160, 169, 188);
    private static final int ACCENT = Color.rgb(108, 140, 255);
    private static final int WARN = Color.rgb(255, 196, 92);
    private static final int ERROR = Color.rgb(255, 120, 120);
    private static final int OK = Color.rgb(112, 220, 160);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private LinearLayout content;
    private Job.Stage shown;
    private ProgressBar bar;
    private TextView status;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (Job.stage != shown) render();
            else updateProgress();
            handler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top;
                bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(dp(16), top + dp(16), dp(16), bottom + dp(24));
            return insets;
        });
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        shown = null;
        handler.post(poll);
    }

    @Override protected void onPause() {
        super.onPause();
        if (pendingSave != null) pendingSave.run();
        handler.removeCallbacks(poll);
    }

    // ------------------------------------------------------------ 화면
    private void render() {
        shown = Job.stage;
        content.removeAllViews();
        bar = null;
        status = null;
        header();
        switch (shown) {
            case ANALYZING: case TRANSLATING: case BUILDING: renderBusy(); break;
            case READY: renderReady(); break;
            case DONE: renderDone(); break;
            case ERROR: renderError(); break;
            default: renderHome();
        }
    }

    private void header() {
        TextView t = text("게임 번역기", 26, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(t);
        TextView s = text("게임 파일을 넣으면 한국어로 바꿔 드려요", 14, MUTED);
        s.setPadding(0, dp(2), 0, dp(16));
        content.addView(s);
    }

    private void renderHome() {
        settings(true);
        LinearLayout pick = card();
        pick.addView(label("2. 게임 파일 선택"));
        pick.addView(body("지원 형식: APK, ZIP, RPG 쯔꾸르 MV/MZ, 렌파이 .rpy, HTML5 게임, 티라노 .ks, 안드로이드 앱 문자열, JS, Lua, JSON, CSV, XML, TXT"));
        Button b = primary("파일 고르기");
        b.setOnClickListener(v -> pickFile());
        pick.addView(b);
        content.addView(pick);
    }

    /** @param chooseLang 분석 전에만 원문 언어를 고를 수 있다. 언어에 따라 찾는 문장이 달라지기 때문이다. */
    private void settings(boolean chooseLang) {
        LinearLayout c = card();
        c.addView(label(chooseLang ? "1. 번역 설정" : "번역 설정"));
        if (chooseLang) {
            c.addView(small("원문 언어"));
            RadioGroup langs = new RadioGroup(this);
            langs.setOrientation(RadioGroup.HORIZONTAL);
            for (Lang l : Lang.values()) {
                RadioButton r = radio(l.label);
                r.setId(View.generateViewId());
                langs.addView(r);
                r.setChecked(prefs.lang() == l);
                r.setOnCheckedChangeListener((v, on) -> { if (on) prefs.setLang(l); });
            }
            c.addView(langs);
        }

        c.addView(small("번역 엔진"));
        RadioGroup engines = new RadioGroup(this);
        RadioButton free = radio("무료 기기 번역 (키 필요 없음, 품질 보통)");
        RadioButton gemini = radio("Gemini (API 키 필요, 품질 좋음)");
        free.setId(View.generateViewId());
        gemini.setId(View.generateViewId());
        engines.addView(free);
        engines.addView(gemini);
        c.addView(engines);

        LinearLayout geminiBox = new LinearLayout(this);
        geminiBox.setOrientation(LinearLayout.VERTICAL);
        EditText key = field("Gemini API 키", prefs.apiKey(), false);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = field("모델 이름", prefs.model(), false);
        geminiBox.addView(key);
        geminiBox.addView(model);
        c.addView(geminiBox);

        boolean useGemini = Prefs.ENGINE_GEMINI.equals(prefs.engine());
        free.setChecked(!useGemini);
        gemini.setChecked(useGemini);
        geminiBox.setVisibility(useGemini ? View.VISIBLE : View.GONE);
        gemini.setOnCheckedChangeListener((v, on) -> {
            prefs.setEngine(on ? Prefs.ENGINE_GEMINI : Prefs.ENGINE_FREE);
            geminiBox.setVisibility(on ? View.VISIBLE : View.GONE);
        });

        c.addView(small("용어집 (한 줄에 하나씩, 원문=번역)"));
        EditText glossary = field("Pikachu=피카츄", prefs.glossary(), true);
        c.addView(glossary);
        content.addView(c);

        saveOnChange(key, model, glossary);
    }

    private void saveOnChange(EditText key, EditText model, EditText glossary) {
        View.OnFocusChangeListener save = (v, focus) -> {
            if (focus) return;
            prefs.setApiKey(key.getText().toString());
            prefs.setModel(model.getText().toString());
            prefs.setGlossary(glossary.getText().toString());
        };
        key.setOnFocusChangeListener(save);
        model.setOnFocusChangeListener(save);
        glossary.setOnFocusChangeListener(save);
        pendingSave = () -> {
            prefs.setApiKey(key.getText().toString());
            prefs.setModel(model.getText().toString());
            prefs.setGlossary(glossary.getText().toString());
        };
    }

    private Runnable pendingSave;

    private void renderBusy() {
        LinearLayout c = card();
        c.addView(label(shown == Job.Stage.ANALYZING ? "분석 중" : shown == Job.Stage.BUILDING ? "마무리 중" : "번역 중"));
        status = body("");
        c.addView(status);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        c.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        c.addView(small("다른 앱을 쓰거나 화면을 꺼도 계속 진행돼요."));
        Button stop = secondary("중지");
        stop.setOnClickListener(v -> {
            Job.cancel = true;
            Toast.makeText(this, "멈추는 중이에요. 번역한 부분은 저장돼요.", Toast.LENGTH_SHORT).show();
        });
        c.addView(stop);
        content.addView(c);
        updateProgress();
    }

    private void updateProgress() {
        if (bar == null || status == null) return;
        int t = Job.total, d = Math.min(Job.done, t);
        boolean known = t > 1;
        bar.setIndeterminate(!known);
        if (known) {
            bar.setMax(t);
            bar.setProgress(d);
            status.setText(String.format(Locale.KOREA, "%s  %,d / %,d (%d%%)", Job.message, d, t, (int) (d * 100L / t)));
        } else {
            status.setText(Job.message);
        }
    }

    private void renderReady() {
        GameProject p = Job.project;
        if (p == null) { Job.stage = Job.Stage.IDLE; return; }

        LinearLayout info = card();
        info.addView(label(p.displayName));
        info.addView(body("게임 엔진: " + p.engine + " · 원문: " + p.lang.label));
        TextView count = body("");
        info.addView(count);
        for (String n : p.notes) {
            TextView w = text("• " + n, 13, WARN);
            w.setPadding(0, dp(6), 0, 0);
            info.addView(w);
        }
        content.addView(info);

        if (p.items.isEmpty()) {
            Button back = secondary("다른 파일 고르기");
            back.setOnClickListener(v -> reset());
            content.addView(back);
            return;
        }

        settings(false);

        LinearLayout list = card();
        list.addView(label("번역할 파일"));
        Runnable refresh = () -> count.setText(String.format(Locale.KOREA, "선택한 문장: 약 %,d개", p.selectedStrings()));
        int shownItems = Math.min(p.items.size(), MAX_LISTED);
        for (int i = 0; i < shownItems; i++) {
            GameProject.Item it = p.items.get(i);
            CheckBox box = new CheckBox(this);
            box.setText(String.format(Locale.KOREA, "%s\n%s · %,d문장", it.path, it.format, it.strings));
            box.setTextColor(TEXT);
            box.setTextSize(13);
            box.setButtonTintList(ColorStateList.valueOf(ACCENT));
            box.setChecked(it.selected);
            box.setPadding(dp(4), dp(6), 0, dp(6));
            box.setOnCheckedChangeListener((v, on) -> { it.selected = on; refresh.run(); });
            list.addView(box);
        }
        if (p.items.size() > MAX_LISTED) {
            list.addView(small("외 " + (p.items.size() - MAX_LISTED) + "개 파일은 추천 설정대로 처리돼요."));
        }
        content.addView(list);
        refresh.run();

        Button go = primary("번역 시작");
        go.setOnClickListener(v -> startTranslate());
        content.addView(go);
        Button other = secondary("다른 파일 고르기");
        other.setOnClickListener(v -> reset());
        content.addView(other);
    }

    private void renderDone() {
        GameProject p = Job.project;
        LinearLayout c = card();
        TextView t = label("번역 완료");
        t.setTextColor(OK);
        c.addView(t);
        c.addView(body(String.format(Locale.KOREA, "%,d문장을 번역했어요.", Job.translated)));
        if (Job.failed > 0) {
            TextView f = text(String.format(Locale.KOREA, "%,d문장은 번역하지 못해 원문으로 남겼어요. 다시 실행하면 그 문장만 재시도해요.", Job.failed), 13, WARN);
            f.setPadding(0, dp(6), 0, 0);
            c.addView(f);
            Button retry = secondary("실패한 문장 다시 번역");
            retry.setOnClickListener(v -> startTranslate());
            c.addView(retry);
        }
        if (p != null && p.kind == GameProject.Kind.APK) {
            c.addView(small("설치 전에 원본 게임을 먼저 지워 주세요. 기존 세이브가 사라질 수 있어요."));
        }
        Button save = primary("결과 파일 저장");
        save.setOnClickListener(v -> saveFile());
        c.addView(save);
        Button again = secondary("다른 게임 번역");
        again.setOnClickListener(v -> reset());
        c.addView(again);
        content.addView(c);
    }

    private void renderError() {
        LinearLayout c = card();
        TextView t = label("문제가 생겼어요");
        t.setTextColor(ERROR);
        c.addView(t);
        c.addView(body(Job.error));
        if (Job.project != null) {
            c.addView(small("번역한 부분은 저장돼 있어서 다시 시작하면 이어서 진행해요."));
            Button retry = primary("돌아가기");
            retry.setOnClickListener(v -> { Job.stage = Job.Stage.READY; render(); });
            c.addView(retry);
        }
        Button other = secondary("처음으로");
        other.setOnClickListener(v -> reset());
        c.addView(other);
        content.addView(c);
    }

    // ------------------------------------------------------------ 동작
    private void reset() {
        Job.project = null;
        Job.output = null;
        Job.stage = Job.Stage.IDLE;
        render();
    }

    private void pickFile() {
        if (pendingSave != null) pendingSave.run();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK);
    }

    private void startTranslate() {
        if (pendingSave != null) pendingSave.run();
        GameProject p = Job.project;
        if (p == null) return;
        if (p.selectedStrings() == 0) {
            Toast.makeText(this, "번역할 파일을 하나 이상 골라 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Prefs.ENGINE_GEMINI.equals(prefs.engine()) && prefs.apiKey().isEmpty()) {
            Toast.makeText(this, "Gemini API 키를 입력해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        Job.stage = Job.Stage.TRANSLATING;
        startForegroundService(new Intent(this, TranslateService.class).setAction(TranslateService.ACTION_TRANSLATE));
        render();
    }

    private void saveFile() {
        GameProject p = Job.project;
        if (p == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(p.kind == GameProject.Kind.APK ? "application/vnd.android.package-archive" : "application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, p.outputName());
        startActivityForResult(i, SAVE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK) {
            Job.stage = Job.Stage.ANALYZING;
            Job.PROGRESS.update(0, 0, "파일 불러오는 중");
            Intent i = new Intent(this, TranslateService.class)
                    .setAction(TranslateService.ACTION_ANALYZE)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startForegroundService(i);
            render();
        } else if (request == SAVE) {
            writeResult(uri);
        }
    }

    private void writeResult(Uri uri) {
        Toast.makeText(this, "저장하는 중이에요.", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg = "저장했어요.";
            try (InputStream in = new FileInputStream(Job.output);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("저장 위치를 열 수 없어요.");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                msg = "저장하지 못했어요: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    // ------------------------------------------------------------ 화면 부품
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 17, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView body(String s) { return text(s, 14, MUTED); }

    private TextView small(String s) {
        TextView t = text(s, 13, MUTED);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private Button button(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(bg, 12));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private Button primary(String s) { return button(s, ACCENT, Color.WHITE); }
    private Button secondary(String s) { return button(s, FIELD, TEXT); }

    private RadioButton radio(String s) {
        RadioButton r = new RadioButton(this);
        r.setText(s);
        r.setTextColor(TEXT);
        r.setTextSize(14);
        r.setButtonTintList(ColorStateList.valueOf(ACCENT));
        r.setPadding(dp(4), dp(6), dp(12), dp(6));
        return r;
    }

    private EditText field(String hint, String value, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(14);
        e.setBackground(round(FIELD, 10));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP);
        } else {
            e.setSingleLine(true);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }
}
