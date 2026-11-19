package com.sawoo.gametranslator;

import android.content.Context;
import android.content.SharedPreferences;

import com.sawoo.gametranslator.core.Lang;

/** 앱 설정. API 키는 이 앱만 읽을 수 있는 저장소에 보관한다. */
final class Prefs {
    static final String ENGINE_FREE = "mlkit";
    static final String ENGINE_GEMINI = "gemini";
    static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    Lang lang() {
        try { return Lang.valueOf(sp.getString("lang", Lang.ENGLISH.name())); }
        catch (IllegalArgumentException e) { return Lang.ENGLISH; }
    }
    String engine() { return sp.getString("engine", ENGINE_FREE); }
    String apiKey() { return sp.getString("apiKey", ""); }
    String model() { return sp.getString("model", DEFAULT_MODEL); }
    String glossary() { return sp.getString("glossary", ""); }

    void setLang(Lang l) { sp.edit().putString("lang", l.name()).apply(); }
    void setEngine(String e) { sp.edit().putString("engine", e).apply(); }
    void setApiKey(String k) { sp.edit().putString("apiKey", k.trim()).apply(); }
    void setModel(String m) { sp.edit().putString("model", m.trim().isEmpty() ? DEFAULT_MODEL : m.trim()).apply(); }
    void setGlossary(String g) { sp.edit().putString("glossary", g).apply(); }
}
