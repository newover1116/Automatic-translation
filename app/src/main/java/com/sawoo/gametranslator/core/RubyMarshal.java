package com.sawoo.gametranslator.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 루비 Marshal(쯔꾸르 XP/VX/VX Ace의 .rxdata/.rvdata/.rvdata2) 읽기.
 * 파일을 다시 만들지 않고 바꿀 문자열 자리만 갈아 끼우므로 나머지 바이트는 원본 그대로 남는다.
 */
final class RubyMarshal {
    static class Node {}

    static final class Str extends Node {
        final int start;   // 길이 숫자가 시작하는 위치
        final int end;     // 문자열 바이트가 끝나는 위치
        final String value;
        String replacement;
        Str(int start, int end, String value) { this.start = start; this.end = end; this.value = value; }
    }

    static final class Int extends Node {
        final long value;
        Int(long value) { this.value = value; }
    }

    static final class Arr extends Node {
        final List<Node> items = new ArrayList<>();
    }

    static final class Obj extends Node {
        final String cls;
        final Map<String, Node> ivars = new LinkedHashMap<>();
        Obj(String cls) { this.cls = cls; }
        Node get(String name) { return ivars.get(name); }
    }

    static final class Hash extends Node {
        final List<Node> keys = new ArrayList<>();
        final List<Node> values = new ArrayList<>();
    }

    static final class Other extends Node {}

    private static final Other OTHER = new Other();

    private final byte[] d;
    private int pos;
    private final List<String> symbols = new ArrayList<>();
    final List<Str> strings = new ArrayList<>();
    final Node root;

    RubyMarshal(byte[] data) {
        this.d = data;
        if (data.length < 3 || data[0] != 4 || data[1] != 8) throw new IllegalArgumentException("Marshal 형식이 아님");
        pos = 2;
        root = read();
    }

    /** 바뀐 문자열만 갈아 끼운 새 바이트. 바뀐 게 없으면 원본을 그대로 돌려준다. */
    byte[] write() {
        ByteArrayOutputStream out = null;
        int last = 0;
        for (Str s : strings) {
            if (s.replacement == null || s.replacement.equals(s.value)) continue;
            if (out == null) out = new ByteArrayOutputStream(d.length + 4096);
            out.write(d, last, s.start - last);
            byte[] b = s.replacement.getBytes(StandardCharsets.UTF_8);
            writeLong(out, b.length);
            out.write(b, 0, b.length);
            last = s.end;
        }
        if (out == null) return d;
        out.write(d, last, d.length - last);
        return out.toByteArray();
    }

    private Node read() {
        int type = d[pos++] & 0xff;
        switch (type) {
            case '0': case 'T': case 'F':
                return OTHER;
            case 'i':
                return new Int(readLong());
            case ':':
                symbols.add(readBytesAsString());
                return OTHER;
            case ';':
                readLong();
                return OTHER;
            case '@':
                readLong();
                return OTHER;
            case '"': {
                int start = pos;
                byte[] b = readBytes();
                Str s = new Str(start, pos, new String(b, StandardCharsets.UTF_8));
                strings.add(s);
                return s;
            }
            case 'I': {
                Node inner = read();
                long n = readLong();
                for (long k = 0; k < n; k++) { readSymbol(); read(); }
                return inner;
            }
            case '[': {
                Arr a = new Arr();
                long n = readLong();
                for (long k = 0; k < n; k++) a.items.add(read());
                return a;
            }
            case '{': case '}': {
                Hash h = new Hash();
                long n = readLong();
                for (long k = 0; k < n; k++) { h.keys.add(read()); h.values.add(read()); }
                if (type == '}') read();
                return h;
            }
            case 'o': {
                Obj o = new Obj(readSymbol());
                long n = readLong();
                for (long k = 0; k < n; k++) {
                    String name = readSymbol();
                    o.ivars.put(name, read());
                }
                return o;
            }
            case 'S': {
                Obj o = new Obj(readSymbol());
                long n = readLong();
                for (long k = 0; k < n; k++) {
                    String name = readSymbol();
                    o.ivars.put(name, read());
                }
                return o;
            }
            case 'f': case 'c': case 'm': case 'M':
                readBytes();
                return OTHER;
            case '/':
                readBytes();
                pos++;
                return OTHER;
            case 'l': {
                pos++; // 부호
                long n = readLong();
                pos += (int) (n * 2);
                return OTHER;
            }
            case 'u':
                readSymbol();
                readBytes();
                return OTHER;
            case 'U': case 'e': case 'C': case 'd':
                readSymbol();
                read();
                return OTHER;
            default:
                throw new IllegalArgumentException("알 수 없는 Marshal 형식: " + (char) type + " @" + (pos - 1));
        }
    }

    private String readSymbol() {
        int type = d[pos++] & 0xff;
        if (type == ':') {
            String s = readBytesAsString();
            symbols.add(s);
            return s;
        }
        if (type == ';') {
            long i = readLong();
            return i >= 0 && i < symbols.size() ? symbols.get((int) i) : "";
        }
        if (type == 'I') {
            String s = readSymbol();
            long n = readLong();
            for (long k = 0; k < n; k++) { readSymbol(); read(); }
            return s;
        }
        throw new IllegalArgumentException("심볼 자리에 " + (char) type);
    }

    private long readLong() {
        int c = d[pos++];
        if (c == 0) return 0;
        if (c > 0) {
            if (c > 4) return c - 5;
            long x = 0;
            for (int i = 0; i < c; i++) x |= (long) (d[pos++] & 0xff) << (8 * i);
            return x;
        }
        if (c < -4) return c + 5;
        int n = -c;
        long x = -1;
        for (int i = 0; i < n; i++) {
            x &= ~(0xffL << (8 * i));
            x |= (long) (d[pos++] & 0xff) << (8 * i);
        }
        return x;
    }

    private byte[] readBytes() {
        int n = (int) readLong();
        if (n < 0 || pos + n > d.length) throw new IllegalArgumentException("문자열 길이가 이상함");
        byte[] b = new byte[n];
        System.arraycopy(d, pos, b, 0, n);
        pos += n;
        return b;
    }

    private String readBytesAsString() {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    static void writeLong(ByteArrayOutputStream out, long x) {
        if (x == 0) { out.write(0); return; }
        if (x > 0 && x < 123) { out.write((int) (x + 5)); return; }
        if (x < 0 && x > -124) { out.write((int) ((x - 5) & 0xff)); return; }
        byte[] buf = new byte[8];
        int i = 0;
        for (; i < 4; i++) {
            buf[i] = (byte) (x & 0xff);
            x >>= 8;
            if (x == 0) { i++; break; }
            if (x == -1) { i++; break; }
        }
        out.write(x < 0 ? -i : i);
        out.write(buf, 0, i);
    }
}
