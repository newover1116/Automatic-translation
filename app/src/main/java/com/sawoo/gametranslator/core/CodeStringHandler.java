package com.sawoo.gametranslator.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 자바스크립트(HTML5 게임)와 Lua 코드 속 문자열 중 문장만 번역한다.
 * 비교, 객체 키, 함수 인자처럼 코드가 쓰는 값은 건드리지 않는다.
 */
public final class CodeStringHandler implements FormatHandler {
    private final boolean lua;

    public CodeStringHandler(boolean lua) { this.lua = lua; }

    private static final Set<String> CODE_CALLS = new HashSet<>(Arrays.asList(
            "require", "import", "getElementById", "querySelector", "querySelectorAll", "getElementsByClassName",
            "getElementsByTagName", "createElement", "addEventListener", "removeEventListener", "on", "off", "once",
            "emit", "trigger", "dispatchEvent", "getItem", "setItem", "removeItem", "indexOf", "lastIndexOf",
            "includes", "startsWith", "endsWith", "split", "replace", "replaceAll", "match", "search", "test",
            "RegExp", "fetch", "open", "load", "loadImage", "loadSound", "loadJSON", "get", "set", "has", "getAttribute",
            "setAttribute", "hasOwnProperty", "postMessage", "find", "findChild", "getChild", "getComponent",
            "playSound", "playMusic", "play", "stop", "sound", "Audio", "Image", "attr", "css", "prop", "data",
            "dofile", "loadfile", "loadstring", "newImage", "newFont", "newSource", "newShader", "rawget", "rawset"));
    private static final Pattern CSS = Pattern.compile("^[\\w-]+\\s*:\\s*[^;]+;");
    private static final String VALID_ESCAPES = "ntr'\"\\`";

    @Override public String label() { return lua ? "Lua 스크립트" : "자바스크립트"; }

    @Override
    public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
        Encoding enc = Encoding.decode(data, ctx.lang);
        String s = enc.text;
        int n = s.length();
        StringBuilder out = new StringBuilder(n + 256);
        char prev = 0;
        int i = 0;
        boolean changed = false;
        while (i < n) {
            char c = s.charAt(i);
            char nx = i + 1 < n ? s.charAt(i + 1) : 0;
            if (!lua && c == '/' && nx == '/') { i = copyTo(s, i, "\n", false, out); continue; }
            if (!lua && c == '/' && nx == '*') { i = copyTo(s, i, "*/", true, out); continue; }
            if (lua && c == '-' && nx == '-') {
                int lvl = longBracket(s, i + 2);
                if (lvl >= 0) i = copyTo(s, i, "]" + repeat('=', lvl) + "]", true, out);
                else i = copyTo(s, i, "\n", false, out);
                continue;
            }
            if (lua && c == '[') {
                int lvl = longBracket(s, i);
                if (lvl >= 0) { i = copyTo(s, i, "]" + repeat('=', lvl) + "]", true, out); prev = ']'; continue; }
            }
            if (c == '"' || c == '\'' || (!lua && c == '`')) {
                int j = i + 1;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == c || (d == '\n' && c != '`')) break;
                    j++;
                }
                if (j >= n || s.charAt(j) != c) { out.append(s.substring(i)); break; }
                String raw = s.substring(i + 1, j);
                String res = literal(raw, c, out, s, j + 1, ctx, fn);
                if (!res.equals(raw)) changed = true;
                out.append(c).append(res).append(c);
                prev = c;
                i = j + 1;
                continue;
            }
            if (!lua && c == '/' && regexAllowed(prev)) {
                int j = i + 1;
                boolean cls = false;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == '\n') break;
                    if (d == '[') cls = true;
                    else if (d == ']') cls = false;
                    else if (d == '/' && !cls) break;
                    j++;
                }
                if (j < n && s.charAt(j) == '/') {
                    j++;
                    while (j < n && Character.isLetter(s.charAt(j))) j++;
                    out.append(s, i, j);
                    prev = 'a';
                    i = j;
                    continue;
                }
            }
            out.append(c);
            if (!Character.isWhitespace(c)) prev = c;
            i++;
        }
        return changed ? enc.encode(out.toString()) : data;
    }

    private String literal(String raw, char quote, StringBuilder before, String src, int after,
                           Context ctx, UnaryOperator<String> fn) {
        if (quote == '`' && raw.contains("${")) return raw;
        String plain = unescape(raw);
        if (plain == null || !sentence(plain, ctx.lang) || usedAsCode(before, src, after)) return raw;
        String t = fn.apply(plain);
        if (t == null || t.equals(plain)) return raw;
        return escape(t, quote);
    }

    private static boolean sentence(String s, Lang lang) {
        if (!TextRules.isLikelyText(s, lang)) return false;
        String t = s.trim();
        if (CSS.matcher(t).find() || t.startsWith("<") && t.endsWith(">") && !t.contains(" ")) return false;
        // 영어 한 단어는 코드 값일 가능성이 커서 문장(띄어쓰기 포함)만 고른다
        return lang != Lang.ENGLISH || t.indexOf(' ') > 0;
    }

    private static boolean usedAsCode(StringBuilder before, String src, int after) {
        int b = before.length() - 1;
        while (b >= 0 && Character.isWhitespace(before.charAt(b))) b--;
        String tail = before.substring(Math.max(0, b - 40), b + 1);
        if (tail.endsWith("==") || tail.endsWith("!=") || tail.endsWith("~=") || tail.endsWith("[")
                || tail.matches("(?s).*\\b(?:case|in|of|typeof|instanceof|import|from|require)$")) return true;
        if (tail.endsWith("(")) {
            String fnName = tail.substring(0, tail.length() - 1).replaceAll("(?s).*?([A-Za-z_$][\\w$]*)\\s*$", "$1");
            if (CODE_CALLS.contains(fnName)) return true;
        }
        int a = after;
        while (a < src.length() && Character.isWhitespace(src.charAt(a)) && src.charAt(a) != '\n') a++;
        if (src.startsWith("==", a) || src.startsWith("!=", a) || src.startsWith("~=", a) || src.startsWith("]", a)) return true;
        // { "키": 값 } 형태의 객체 키
        char last = b >= 0 ? before.charAt(b) : 0;
        return src.startsWith(":", a) && (last == '{' || last == ',');
    }

    private static boolean regexAllowed(char prev) {
        return prev == 0 || "(,=:[!&|?{};+-*%<>~^".indexOf(prev) >= 0;
    }

    private static String unescape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') { sb.append(c); continue; }
            if (++i >= raw.length()) return null;
            char e = raw.charAt(i);
            if (VALID_ESCAPES.indexOf(e) < 0) return null;  // 모르는 이스케이프는 원문 유지
            sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e);
        }
        return sb.toString();
    }

    private static String escape(String s, char quote) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == quote) sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (quote == '`' && c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '{') sb.append("\\$");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static int copyTo(String s, int i, String end, boolean include, StringBuilder out) {
        int e = s.indexOf(end, i + 2);
        int stop = e < 0 ? s.length() : include ? e + end.length() : e;
        out.append(s, i, stop);
        return stop;
    }

    /** Lua 긴 괄호 [[ 또는 [==[ 의 = 개수. 아니면 -1. */
    private static int longBracket(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') return -1;
        int j = i + 1, lvl = 0;
        while (j < s.length() && s.charAt(j) == '=') { lvl++; j++; }
        return j < s.length() && s.charAt(j) == '[' ? lvl : -1;
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    /** 게임 문장이 들어 있지 않은 유명 라이브러리 파일. */
    public static boolean isLibrary(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.matches("(?:phaser|pixi|jquery|three|howler|lodash|underscore|createjs|easeljs|soundjs|preloadjs|tweenjs|"
                + "fpsmeter|lz-string|iphone-inline-video|cordova|cordova_plugins|polyfill|polyfills|effekseer|localforage|"
                + "pako|vorbis.*|stats|matter|p2|box2d|planck|socket\\.io|firebase.*|gsap|anime|hammer|zepto|"
                + "rpg_core|rpg_managers|rpg_objects|rpg_scenes|rpg_sprites|rpg_windows|rmmz_.*|c3runtime|"
                + "c2runtime|vendor|vendors|runtime|webfont|fontfaceobserver|crypto-js|md5|sha\\d*|json\\d*)"
                + "(?:[.-][\\w.-]*)?\\.(?:js|lua)$");
    }
}
