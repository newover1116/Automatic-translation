package com.sawoo.gametranslator.core;

import java.util.regex.Pattern;

/** 문자열이 사람이 읽는 문장인지, 게임 내부 식별자인지 가려낸다. */
public final class TextRules {
    private TextRules() {}

    private static final Pattern URL = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://\\S*$|^www\\.\\S+$");
    private static final Pattern PATH = Pattern.compile(
            "(?i)^[\\w ./\\\\:-]*[/\\\\][\\w ./\\\\-]*$|^[\\w .-]+\\.(png|jpe?g|gif|webp|bmp|ogg|mp3|wav|m4a|mp4|webm|ttf|otf|woff2?|json|js|css|html?|xml|txt|csv|rpy|rpyc|lua|atlas|skel|prefab|asset|bin|dat)$");
    private static final Pattern ASSET = Pattern.compile(
            "(?i)^\\S+\\.(png|jpe?g|gif|webp|bmp|ogg|opus|mp3|wav|m4a|mp4|webm|ttf|otf|woff2?|json|js|css|html?|xml|rpy|rpyc)$");
    private static final Pattern IDENT = Pattern.compile(
            "^[a-z_$][\\w$]*$|^[A-Z][A-Z0-9_]*$|^[a-zA-Z_$][\\w$]*(?:[._:-][\\w$]+)+$|^[a-z]+(?:[A-Z][a-z0-9]*)+$|^(?:[A-Z][a-z0-9]+){2,}$");
    private static final Pattern NUMERIC = Pattern.compile("^[\\s\\d.,:;+\\-*/%#()\\[\\]\\{\\}<>=!?\\&|\\^~_\"'`]*$");
    private static final Pattern HEX = Pattern.compile("(?i)^#?[0-9a-f]{3,8}$|^0x[0-9a-f]+$|^[0-9a-f]{8}-[0-9a-f-]{27}$");
    private static final Pattern CODE = Pattern.compile(
            "^\\s*(?:function\\b|var\\s|let\\s|const\\s|return\\b|if\\s*\\(|for\\s*\\(|import\\s|class\\s+\\w+\\s*[\\{:(]|\\$\\w+\\.|this\\.)|[;\\{\\}]\\s*$|=>|&&|\\|\\||==");

    private static boolean hasHangul(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x318E)) return true;
        }
        return false;
    }

    private static boolean hasKana(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3041 && c <= 0x30FF) || (c >= 0xFF66 && c <= 0xFF9D)) return true;
        }
        return false;
    }

    private static boolean hasHan(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static boolean hasLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= 0xC0 && c <= 0x24F)) return true;
        }
        return false;
    }

    /** 대사처럼 형식상 문장임이 확실한 곳: 원문 언어의 글자만 있으면 번역한다. */
    public static boolean isDialogue(String s, Lang lang) {
        if (s == null || s.trim().isEmpty() || hasHangul(s) || ASSET.matcher(s.trim()).matches()) return false;
        String bare = Protector.stripTags(s);
        switch (lang) {
            case JAPANESE: return hasKana(bare) || hasHan(bare) || hasLatin(bare);
            case CHINESE: return hasHan(bare) || hasLatin(bare);
            default: return hasLatin(bare) || hasKana(bare) || hasHan(bare);
        }
    }

    /** JSON, CSV 처럼 설정값이 섞일 수 있는 곳: 문장일 가능성이 높을 때만 번역한다. */
    public static boolean isLikelyText(String s, Lang lang) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2 || t.length() > 20000 || hasHangul(t)) return false;
        if (NUMERIC.matcher(t).matches() || HEX.matcher(t).matches() || URL.matcher(t).matches()) return false;
        if (PATH.matcher(t).matches() || CODE.matcher(t).find()) return false;
        String bare = Protector.stripTags(t).trim();
        if (bare.isEmpty()) return false;
        switch (lang) {
            case JAPANESE:
                return hasKana(bare) || hasHan(bare);
            case CHINESE:
                return hasHan(bare) && !hasKana(bare);
            default:
                if (!hasLatin(bare) || IDENT.matcher(bare).matches()) return false;
                if (bare.indexOf(' ') >= 0) return true;
                // 한 단어는 "Start", "Inventory" 처럼 대문자로 시작하는 일반 단어만 인정
                return bare.length() >= 3 && Character.isUpperCase(bare.charAt(0))
                        && bare.substring(1).chars().allMatch(c -> Character.isLowerCase(c) || c == '\'' || c == '!' || c == '?' || c == '.');
        }
    }
}
