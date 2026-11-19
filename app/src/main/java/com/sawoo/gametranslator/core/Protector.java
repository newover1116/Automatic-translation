package com.sawoo.gametranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 변수, 색상 코드, 태그처럼 번역되면 게임이 깨지는 부분을 보호한다. */
public final class Protector {
    private Protector() {}

    static final Pattern TAG = Pattern.compile(
            "\\\\[A-Za-z]+\\d*(?:\\[[^\\]\\n]*\\])?(?:<[^>\\n]*>)?"   // 쯔꾸르 \V[1] \C[2] \N[1]
            + "|\\\\[.!|^<>\\{\\}\\$\\\\]"                                  // 쯔꾸르 \. \! \| \^
            + "|\\{\\{|\\}\\}|\\[\\[|%%"                              // 렌파이 이스케이프
            + "|\\$\\{[^\\{\\}\\n]*\\}"                                   // ${name}
            + "|\\{[^\\{\\}\\n]*\\}"                                      // {b} {w=1} {0} {name}
            + "|\\[[^\\[\\]\\n]*\\]"                                  // [player] [color=red]
            + "|</?[A-Za-z][^<>\\n]{0,80}>"                           // <color=red> <b>
            + "|%\\([^)\\n]*\\)[-#0+]*\\d*(?:\\.\\d+)?[sdifgxX]"      // %(name)s
            + "|%\\d+\\$[-#0+]*\\d*(?:\\.\\d+)?[sdifgxX@]"            // %1$s
            + "|%[-#0+]*\\d*(?:\\.\\d+)?[sdifgxX@]"                   // %s %d %.2f
            + "|%\\d+");                                              // 쯔꾸르 %1

    private static final String OPEN = "⟦";
    private static final String CLOSE = "⟧";
    private static final Pattern TOKEN = Pattern.compile("⟦\\s*(\\d+)\\s*⟧");

    public static final class Masked {
        public final String text;
        public final List<String> tags;
        Masked(String text, List<String> tags) { this.text = text; this.tags = tags; }
    }

    public static Masked mask(String s) {
        List<String> tags = new ArrayList<>();
        Matcher m = TAG.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start()).append(OPEN).append(tags.size()).append(CLOSE);
            tags.add(m.group());
            last = m.end();
        }
        sb.append(s.substring(last));
        return new Masked(sb.toString(), tags);
    }

    /** 태그가 하나라도 빠지거나 중복되면 null 을 돌려 원문을 지키게 한다. */
    public static String unmask(String translated, List<String> tags) {
        if (translated == null) return null;
        boolean[] seen = new boolean[tags.size()];
        Matcher m = TOKEN.matcher(translated);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int n;
            try { n = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
            if (n >= tags.size() || seen[n]) return null;
            seen[n] = true;
            sb.append(translated, last, m.start()).append(tags.get(n));
            last = m.end();
        }
        sb.append(translated.substring(last));
        for (boolean b : seen) if (!b) return null;
        String out = sb.toString();
        return out.contains(OPEN) || out.contains(CLOSE) ? null : out;
    }

    /** 태그와 줄바꿈을 기준으로 잘라 번역할 조각과 그대로 둘 조각으로 나눈다. */
    public static List<Piece> split(String s) {
        List<Piece> out = new ArrayList<>();
        Matcher m = Pattern.compile(TAG.pattern() + "|\\r?\\n").matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addText(out, s.substring(last, m.start()));
            out.add(new Piece(m.group(), false));
            last = m.end();
        }
        if (last < s.length()) addText(out, s.substring(last));
        return out;
    }

    private static void addText(List<Piece> out, String t) {
        boolean translatable = false;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetter(t.charAt(i))) { translatable = true; break; }
        }
        out.add(new Piece(t, translatable));
    }

    public static final class Piece {
        public final String text;
        public final boolean translatable;
        Piece(String text, boolean translatable) { this.text = text; this.translatable = translatable; }
    }

    public static String stripTags(String s) {
        return TAG.matcher(s).replaceAll(" ");
    }
}
