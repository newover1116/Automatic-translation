package com.sawoo.gametranslator.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 렌파이 .rpy 스크립트: 대사, 선택지, 캐릭터 이름, 화면 문구. */
public final class RenpyHandler implements FormatHandler {
    private static final String STR = "\"((?:[^\"\\\\\\n]|\\\\.)*)\"";

    private static final Pattern BLOCK = Pattern.compile(
            "^\\s*(?:(?:init(?:\\s+-?\\d+)?\\s+)?python(?:\\s+\\w+)*|translate\\s+\\w+\\s+\\w+|style\\s+\\w+.*|transform\\s+\\w+.*|image\\s+[^=]*|testcase\\s+\\w+)\\s*:\\s*(?:#.*)?$");
    private static final Pattern SAY = Pattern.compile(
            "^\\s*(?:([A-Za-z_]\\w*)(?:\\s+[A-Za-z_]\\w*)*\\s+)?" + STR
            + "\\s*(?:\\([^)]*\\)\\s*)?(?:with\\s+\\w+\\s*)?(?:id\\s+\\w+\\s*)?(?:nointeract\\s*)?(?:if\\s+.*?)?:?\\s*(?:#.*)?$");
    private static final Pattern SCREEN_TEXT = Pattern.compile(
            "^\\s*(?:text|textbutton|label|tooltip)\\s+" + STR);
    private static final Pattern CHARACTER = Pattern.compile("\\bCharacter\\(\\s*(?:_\\(\\s*)?" + STR);
    private static final Pattern MARKED = Pattern.compile("(?<![\\w.])_{1,2}\\(\\s*" + STR + "\\s*\\)");
    private static final Pattern GUI_FONT = Pattern.compile(
            "^(\\s*define\\s+gui\\.\\w*font\\s*=\\s*)\"[^\"]*\"(.*)$");

    /** 이 단어로 시작하는 줄은 대사가 아니라 렌파이 명령이다. */
    private static final Set<String> NOT_SPEAKER = new HashSet<>(Arrays.asList(
            "define", "default", "image", "play", "queue", "stop", "scene", "show", "hide", "jump", "call",
            "return", "label", "voice", "sound", "music", "audio", "with", "window", "pause", "style", "transform",
            "init", "python", "screen", "use", "add", "font", "key", "action", "at", "as", "behind", "onlayer",
            "zorder", "background", "foreground", "idle", "hover", "ground", "insensitive", "selected", "text",
            "textbutton", "imagebutton", "tooltip", "hotspot", "bar", "vbar", "input", "timer", "translate",
            "old", "new", "layout", "include", "import", "from", "pass", "if", "elif", "else", "while", "for",
            "properties", "variant", "modal", "tag", "ground", "xfill", "yfill", "null", "frame", "grid", "viewport"));

    @Override public String label() { return "렌파이 스크립트"; }

    @Override
    public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
        Encoding enc = Encoding.decode(data, ctx.lang);
        String[] lines = enc.text.split("(?<=\\n)", -1);
        StringBuilder out = new StringBuilder(enc.text.length() + 64);
        int skipIndent = -1;
        boolean triple = false;
        boolean guiFile = ctx.path.endsWith("gui.rpy");

        for (String raw : lines) {
            String eol = raw.endsWith("\r\n") ? "\r\n" : raw.endsWith("\n") ? "\n" : "";
            String line = raw.substring(0, raw.length() - eol.length());
            String trimmed = line.trim();

            int quotes = countTriple(line);
            if (triple || quotes % 2 == 1) {
                if (quotes % 2 == 1) triple = !triple;
                out.append(raw);
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { out.append(raw); continue; }

            int indent = indentOf(line);
            if (skipIndent >= 0) {
                if (indent > skipIndent) { out.append(markedOnly(line, fn, ctx)).append(eol); continue; }
                skipIndent = -1;
            }
            if (BLOCK.matcher(line).matches()) {
                skipIndent = indent;
                out.append(raw);
                continue;
            }
            if (guiFile && ctx.koreanFont != null) {
                Matcher g = GUI_FONT.matcher(line);
                if (g.matches()) {
                    out.append(g.group(1)).append('"').append(ctx.koreanFont).append('"').append(g.group(2)).append(eol);
                    continue;
                }
            }
            out.append(processLine(line, ctx, fn)).append(eol);
        }
        return enc.encode(out.toString());
    }

    private String processLine(String line, Context ctx, UnaryOperator<String> fn) {
        String t = line.trim();
        if (t.startsWith("$") || t.startsWith("define ") || t.startsWith("default ")) {
            Matcher c = CHARACTER.matcher(line);
            if (c.find()) line = replaceGroup(line, c, fn, ctx);
            return markedOnly(line, fn, ctx);
        }
        Matcher s = SCREEN_TEXT.matcher(line);
        if (s.find()) return replaceGroup(line, s, fn, ctx);
        Matcher say = SAY.matcher(line);
        if (say.matches() && (say.group(1) == null || !NOT_SPEAKER.contains(say.group(1)))) {
            return replaceGroup(line, say, fn, ctx);
        }
        return markedOnly(line, fn, ctx);
    }

    private String markedOnly(String line, UnaryOperator<String> fn, Context ctx) {
        Matcher m = MARKED.matcher(line);
        List<int[]> spans = new ArrayList<>();
        while (m.find()) spans.add(new int[]{m.start(1), m.end(1)});
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] sp = spans.get(i);
            line = line.substring(0, sp[0]) + apply(line.substring(sp[0], sp[1]), fn, ctx) + line.substring(sp[1]);
        }
        return line;
    }

    private String replaceGroup(String line, Matcher m, UnaryOperator<String> fn, Context ctx) {
        int g = m.groupCount();
        return line.substring(0, m.start(g)) + apply(m.group(g), fn, ctx) + line.substring(m.end(g));
    }

    private String apply(String escaped, UnaryOperator<String> fn, Context ctx) {
        String plain = unescape(escaped);
        if (!TextRules.isDialogue(plain, ctx.lang)) return escaped;
        String out = fn.apply(plain);
        return out.equals(plain) ? escaped : escape(out);
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == '"') sb.append('"');
                else if (n == '\\') sb.append('\\');
                else sb.append(c).append(n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n");
    }

    private static int indentOf(String l) {
        int n = 0;
        while (n < l.length() && (l.charAt(n) == ' ' || l.charAt(n) == '\t')) n++;
        return n;
    }

    private static int countTriple(String l) {
        int n = 0, i = 0;
        while ((i = l.indexOf("\"\"\"", i)) >= 0) { n++; i += 3; }
        return n;
    }

    /** 게임에 함께 넣는 한글 글꼴 설정 스크립트. */
    public static String fontScript(String fontFile) {
        return "# 게임 번역기가 추가한 한글 글꼴 설정\n"
                + "init 1500 python:\n"
                + "    style.default.font = \"" + fontFile + "\"\n"
                + "    try:\n"
                + "        gui.text_font = \"" + fontFile + "\"\n"
                + "        gui.name_text_font = \"" + fontFile + "\"\n"
                + "        gui.interface_text_font = \"" + fontFile + "\"\n"
                + "    except Exception:\n"
                + "        pass\n";
    }
}
