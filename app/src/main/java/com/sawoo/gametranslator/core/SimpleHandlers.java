package com.sawoo.gametranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSON, CSV, XML, 키=값, 일반 텍스트 처리기 모음. */
public final class SimpleHandlers {
    private SimpleHandlers() {}

    private static String tr(String s, FormatHandler.Context ctx, UnaryOperator<String> fn) {
        return TextRules.isLikelyText(s, ctx.lang) ? fn.apply(s) : s;
    }

    // ---------------------------------------------------------------- JSON
    public static final class JsonHandler implements FormatHandler {
        private static final Pattern SKIP_KEY = Pattern.compile(
                "(?i)^(?:.*[_-])?(?:id|key|guid|uuid|path|file|filename|dir|folder|image|img|icon|sprite|texture|atlas|sound|audio|bgm|bgs|se|sfx|voice|music|url|uri|href|src|type|class|classname|font|color|colour|anim|animation|tag|ref|code|version|hash|format|locale|lang|language|script|plugin|shader|prefab|scene|event|action|trigger|method|function|callback)$");

        @Override public String label() { return "JSON"; }

        @Override
        public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
            Encoding enc = Encoding.decode(data, ctx.lang);
            Object root = Json.parse(enc.text);
            return enc.encode(Json.write(walk(root, ctx, fn)));
        }

        @SuppressWarnings("unchecked")
        private Object walk(Object v, Context ctx, UnaryOperator<String> fn) {
            if (v instanceof String) return tr((String) v, ctx, fn);
            if (v instanceof List) {
                List<Object> l = (List<Object>) v;
                for (int i = 0; i < l.size(); i++) l.set(i, walk(l.get(i), ctx, fn));
            } else if (v instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    if (!SKIP_KEY.matcher(e.getKey()).matches()) e.setValue(walk(e.getValue(), ctx, fn));
                }
            }
            return v;
        }
    }

    // ---------------------------------------------------------------- CSV
    public static final class CsvHandler implements FormatHandler {
        private static final Pattern SKIP_COLUMN = Pattern.compile(
                "(?i)^\\s*(?:id|key|keys|name_?id|string_?id|text_?id|type|tag|path|file|comment|note|notes|context)\\s*$");

        @Override public String label() { return "CSV"; }

        private static final class Cell { String text; boolean quoted; }
        private static final class Row { List<Cell> cells = new ArrayList<>(); String eol = ""; }

        @Override
        public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
            Encoding enc = Encoding.decode(data, ctx.lang);
            char sep = detect(enc.text, ctx.path);
            List<Row> rows = parse(enc.text, sep);
            if (rows.isEmpty()) return data;

            List<Cell> head = rows.get(0).cells;
            int src = -1, ko = -1;
            for (int i = 0; i < head.size(); i++) {
                String h = head.get(i).text.trim().toLowerCase(Locale.ROOT);
                if (isLangColumn(h, ctx.lang)) src = i;
                if (h.equals("ko") || h.equals("kr") || h.equals("korean") || h.equals("ko-kr") || h.equals("ko_kr") || h.equals("한국어")) ko = i;
            }
            for (int r = 1; r < rows.size(); r++) {
                List<Cell> cells = rows.get(r).cells;
                if (src >= 0) {
                    // 언어별 열이 있는 번역표: 원문 열을 읽어 한국어 열을 채운다
                    if (src >= cells.size()) continue;
                    String out = tr(cells.get(src).text, ctx, fn);
                    int target = ko >= 0 ? ko : src;
                    while (cells.size() <= target) cells.add(blank());
                    if (!out.equals(cells.get(src).text)) cells.get(target).text = out;
                } else {
                    for (int c = 0; c < cells.size(); c++) {
                        if (c < head.size() && SKIP_COLUMN.matcher(head.get(c).text).matches()) continue;
                        cells.get(c).text = tr(cells.get(c).text, ctx, fn);
                    }
                }
            }
            return enc.encode(write(rows, sep));
        }

        private static Cell blank() { Cell c = new Cell(); c.text = ""; return c; }

        private static boolean isLangColumn(String h, Lang lang) {
            switch (lang) {
                case JAPANESE: return h.equals("ja") || h.equals("jp") || h.equals("ja-jp") || h.equals("ja_jp") || h.equals("japanese") || h.equals("日本語");
                case CHINESE: return h.equals("zh") || h.equals("cn") || h.equals("zh-cn") || h.equals("zh_cn") || h.equals("zh-hans") || h.equals("chinese") || h.equals("中文");
                default: return h.equals("en") || h.equals("eng") || h.equals("en-us") || h.equals("en_us") || h.equals("english");
            }
        }

        private static char detect(String s, String path) {
            if (path.toLowerCase(Locale.ROOT).endsWith(".tsv")) return '\t';
            int nl = s.indexOf('\n');
            String first = nl >= 0 ? s.substring(0, nl) : s;
            char best = ',';
            int max = -1;
            for (char c : new char[]{',', ';', '\t'}) {
                int n = 0;
                for (int i = 0; i < first.length(); i++) if (first.charAt(i) == c) n++;
                if (n > max) { max = n; best = c; }
            }
            return best;
        }

        private static List<Row> parse(String s, char sep) {
            List<Row> rows = new ArrayList<>();
            Row row = new Row();
            StringBuilder cur = new StringBuilder();
            boolean inQ = false, wasQ = false;
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inQ) {
                    if (c == '"') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cur.append('"'); i += 2; continue; }
                        inQ = false;
                    } else cur.append(c);
                    i++;
                } else if (c == '"' && cur.length() == 0) {
                    inQ = true; wasQ = true; i++;
                } else if (c == sep) {
                    addCell(row, cur, wasQ); wasQ = false; i++;
                } else if (c == '\r' || c == '\n') {
                    String eol = c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n' ? "\r\n" : String.valueOf(c);
                    addCell(row, cur, wasQ); wasQ = false;
                    row.eol = eol;
                    rows.add(row);
                    row = new Row();
                    i += eol.length();
                } else { cur.append(c); i++; }
            }
            if (cur.length() > 0 || !row.cells.isEmpty() || wasQ) { addCell(row, cur, wasQ); rows.add(row); }
            return rows;
        }

        private static void addCell(Row r, StringBuilder cur, boolean q) {
            Cell c = new Cell();
            c.text = cur.toString();
            c.quoted = q;
            r.cells.add(c);
            cur.setLength(0);
        }

        private static String write(List<Row> rows, char sep) {
            StringBuilder sb = new StringBuilder();
            for (Row r : rows) {
                for (int i = 0; i < r.cells.size(); i++) {
                    if (i > 0) sb.append(sep);
                    Cell c = r.cells.get(i);
                    boolean q = c.quoted || c.text.indexOf(sep) >= 0 || c.text.indexOf('"') >= 0
                            || c.text.indexOf('\n') >= 0 || c.text.indexOf('\r') >= 0;
                    if (q) sb.append('"').append(c.text.replace("\"", "\"\"")).append('"');
                    else sb.append(c.text);
                }
                sb.append(r.eol);
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- XML
    public static final class XmlHandler implements FormatHandler {
        @Override public String label() { return "XML"; }

        @Override
        public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
            Encoding enc = Encoding.decode(data, ctx.lang);
            String s = enc.text;
            StringBuilder out = new StringBuilder(s.length() + 64);
            int i = 0;
            while (i < s.length()) {
                if (s.startsWith("<!--", i)) i = copyUntil(s, i, "-->", out);
                else if (s.startsWith("<![CDATA[", i)) {
                    int end = s.indexOf("]]>", i);
                    if (end < 0) { out.append(s.substring(i)); break; }
                    out.append("<![CDATA[").append(padded(s.substring(i + 9, end), ctx, fn, false)).append("]]>");
                    i = end + 3;
                } else if (s.startsWith("<?", i)) i = copyUntil(s, i, "?>", out);
                else if (s.startsWith("<!", i)) i = copyUntil(s, i, ">", out);
                else if (s.charAt(i) == '<') {
                    int end = tagEnd(s, i);
                    String tag = s.substring(i, end);
                    out.append(tag);
                    i = end;
                    String low = tag.toLowerCase(Locale.ROOT);
                    if ((low.startsWith("<script") || low.startsWith("<style")) && !tag.endsWith("/>")) {
                        String close = low.startsWith("<script") ? "</script>" : "</style>";
                        int c = s.toLowerCase(Locale.ROOT).indexOf(close, i);
                        if (c < 0) c = s.length();
                        out.append(s, i, c);
                        i = c;
                    }
                } else {
                    int end = s.indexOf('<', i);
                    if (end < 0) end = s.length();
                    out.append(padded(s.substring(i, end), ctx, fn, true));
                    i = end;
                }
            }
            return enc.encode(out.toString());
        }

        private static int copyUntil(String s, int i, String end, StringBuilder out) {
            int e = s.indexOf(end, i);
            e = e < 0 ? s.length() : e + end.length();
            out.append(s, i, e);
            return e;
        }

        private static int tagEnd(String s, int i) {
            char q = 0;
            for (int p = i + 1; p < s.length(); p++) {
                char c = s.charAt(p);
                if (q != 0) { if (c == q) q = 0; }
                else if (c == '"' || c == '\'') q = c;
                else if (c == '>') return p + 1;
            }
            return s.length();
        }

        private static String padded(String raw, Context ctx, UnaryOperator<String> fn, boolean entities) {
            String plain = entities ? unescape(raw) : raw;
            String out = tr(plain, ctx, fn);
            if (out.equals(plain)) return raw;
            return entities ? escape(out) : out.replace("]]>", "]]&gt;");
        }

        private static String unescape(String s) {
            Matcher m = Pattern.compile("&(#\\d+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos);").matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String e = m.group(1);
                String r;
                switch (e) {
                    case "amp": r = "&"; break;
                    case "lt": r = "<"; break;
                    case "gt": r = ">"; break;
                    case "quot": r = "\""; break;
                    case "apos": r = "'"; break;
                    default:
                        int cp = e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        r = new String(Character.toChars(cp));
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(r));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    // ---------------------------------------------------------------- key=value
    public static final class KeyValueHandler implements FormatHandler {
        private static final Pattern LINE = Pattern.compile("^(\\s*[^#;!\\[\\s][^=:]*?\\s*[=:]\\s*)(.*?)(\\s*)$");

        @Override public String label() { return "언어 파일"; }

        @Override
        public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
            Encoding enc = Encoding.decode(data, ctx.lang);
            boolean props = ctx.path.toLowerCase(Locale.ROOT).endsWith(".properties");
            StringBuilder out = new StringBuilder(enc.text.length() + 64);
            for (String raw : enc.text.split("(?<=\\n)", -1)) {
                String eol = raw.endsWith("\r\n") ? "\r\n" : raw.endsWith("\n") ? "\n" : "";
                String line = raw.substring(0, raw.length() - eol.length());
                Matcher m = LINE.matcher(line);
                if (!m.matches()) { out.append(raw); continue; }
                String v = m.group(2);
                String q = "";
                if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) {
                    q = v.substring(0, 1);
                    v = v.substring(1, v.length() - 1);
                }
                String plain = props ? fromProps(v) : v;
                String tr = tr(plain, ctx, fn);
                String result = tr.equals(plain) ? v : props ? toProps(tr) : tr;
                out.append(m.group(1)).append(q).append(result).append(q).append(m.group(3)).append(eol);
            }
            return enc.encode(out.toString());
        }

        private static String fromProps(String s) {
            Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String toProps(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c > 0x7E) sb.append(String.format("\\u%04x", (int) c));
                else sb.append(c);
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 일반 텍스트
    public static final class PlainTextHandler implements FormatHandler {
        @Override public String label() { return "텍스트"; }

        @Override
        public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
            Encoding enc = Encoding.decode(data, ctx.lang);
            StringBuilder out = new StringBuilder(enc.text.length() + 64);
            for (String raw : enc.text.split("(?<=\\n)", -1)) {
                String eol = raw.endsWith("\r\n") ? "\r\n" : raw.endsWith("\n") ? "\n" : "";
                String line = raw.substring(0, raw.length() - eol.length());
                out.append(tr(line, ctx, fn)).append(eol);
            }
            return enc.encode(out.toString());
        }
    }
}
