package com.sawoo.gametranslator.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 키 순서와 숫자 표기를 그대로 보존하는 작은 JSON 파서/작성기. */
public final class Json {
    private Json() {}

    /** 숫자를 원문 표기 그대로 들고 있어서 1.0 이 1 로 바뀌는 일을 막는다. */
    public static final class Num {
        public final String raw;
        public Num(String raw) { this.raw = raw; }
        public int intValue() {
            try { return (int) Double.parseDouble(raw); } catch (NumberFormatException e) { return 0; }
        }
        @Override public String toString() { return raw; }
    }

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("JSON 뒤에 불필요한 내용이 있음: " + p.i);
        return v;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb) {
        if (v == null) sb.append("null");
        else if (v instanceof String) quote((String) v, sb);
        else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else sb.append(v);
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int i = 0;
        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        Object value() {
            if (i >= s.length()) throw err("내용이 끝남");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw err("알 수 없는 값");
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek('}')) { i++; return m; }
            while (true) {
                ws();
                if (!peek('"')) throw err("키가 필요함");
                String k = string();
                ws();
                if (!peek(':')) throw err("':' 가 필요함");
                i++;
                ws();
                m.put(k, value());
                ws();
                if (peek(',')) { i++; continue; }
                if (peek('}')) { i++; return m; }
                throw err("',' 또는 '}' 가 필요함");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek(']')) { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (peek(',')) { i++; continue; }
                if (peek(']')) { i++; return l; }
                throw err("',' 또는 ']' 가 필요함");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw err("문자열이 닫히지 않음");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw err("잘못된 이스케이프");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("잘못된 유니코드");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw err("잘못된 이스케이프");
                }
            }
        }

        Num number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return new Num(s.substring(st, i));
        }

        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON 오류(" + i + "): " + m); }
    }
}
