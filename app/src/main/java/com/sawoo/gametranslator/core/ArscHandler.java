package com.sawoo.gametranslator.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 일반 안드로이드 앱 방식 게임의 문자열(resources.arsc).
 * 전역 문자열 표만 다시 쓰고, 나머지 리소스 데이터는 그대로 둔다.
 */
public final class ArscHandler implements FormatHandler {
    private static final int RES_TABLE = 0x0002;
    private static final int RES_STRING_POOL = 0x0001;
    private static final int SORTED = 1;
    private static final int UTF8 = 1 << 8;

    @Override public String label() { return "안드로이드 앱 문자열"; }

    @Override
    public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 12 || (b.getShort(0) & 0xFFFF) != RES_TABLE) return data;
        int tableHeader = b.getShort(2) & 0xFFFF;
        int pool = tableHeader;
        if (pool + 28 > data.length || (b.getShort(pool) & 0xFFFF) != RES_STRING_POOL) return data;

        int poolHeader = b.getShort(pool + 2) & 0xFFFF;
        int poolSize = b.getInt(pool + 4);
        int count = b.getInt(pool + 8);
        int styleCount = b.getInt(pool + 12);
        int flags = b.getInt(pool + 16);
        int stringsStart = b.getInt(pool + 20);
        int stylesStart = b.getInt(pool + 24);
        if ((flags & SORTED) != 0 || poolSize <= 0 || pool + poolSize > data.length) return data;
        boolean utf8 = (flags & UTF8) != 0;

        List<String> strings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int off = pool + stringsStart + b.getInt(pool + poolHeader + i * 4);
            strings.add(utf8 ? readUtf8(data, off) : readUtf16(b, off));
        }

        boolean changed = false;
        List<String> out = new ArrayList<>(strings);
        for (int i = styleCount; i < count; i++) {  // 굵게 같은 서식이 있는 문자열은 글자 위치가 기록돼 있어 건드리지 않는다
            String s = strings.get(i);
            if (s == null || s.length() > 4000 || !TextRules.isLikelyText(s, ctx.lang)) continue;
            String t = fn.apply(s);
            if (t != null && !t.equals(s) && t.length() <= 0x7FFF) {
                out.set(i, t);
                changed = true;
            }
        }
        if (!changed) return data;

        // 새 문자열 영역
        ByteArrayOutputStream body = new ByteArrayOutputStream(poolSize + 1024);
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = body.size();
            byte[] enc = utf8 ? writeUtf8(out.get(i)) : writeUtf16(out.get(i));
            body.write(enc, 0, enc.length);
        }
        while (body.size() % 4 != 0) body.write(0);

        byte[] styles = new byte[0];
        if (styleCount > 0 && stylesStart > 0) {
            styles = new byte[poolSize - stylesStart];
            System.arraycopy(data, pool + stylesStart, styles, 0, styles.length);
        }
        int newStringsStart = poolHeader + count * 4 + styleCount * 4;
        int newStylesStart = styles.length > 0 ? newStringsStart + body.size() : 0;
        int newPoolSize = newStringsStart + body.size() + styles.length;

        ByteBuffer np = ByteBuffer.allocate(newPoolSize).order(ByteOrder.LITTLE_ENDIAN);
        np.put(data, pool, poolHeader);
        np.putInt(4, newPoolSize);
        np.putInt(20, newStringsStart);
        np.putInt(24, newStylesStart);
        np.position(poolHeader);
        for (int o : offsets) np.putInt(o);
        np.put(data, pool + poolHeader + count * 4, styleCount * 4);
        np.put(body.toByteArray());
        np.put(styles);

        int rest = data.length - (pool + poolSize);
        ByteBuffer t = ByteBuffer.allocate(tableHeader + newPoolSize + rest).order(ByteOrder.LITTLE_ENDIAN);
        t.put(data, 0, tableHeader);
        t.put(np.array());
        t.put(data, pool + poolSize, rest);
        t.putInt(4, t.capacity());
        return t.array();
    }

    // ------------------------------------------------------------ UTF-8 문자열
    private static String readUtf8(byte[] d, int off) {
        int p = off;
        p += (d[p] & 0x80) != 0 ? 2 : 1;               // UTF-16 기준 글자 수
        int len = d[p] & 0xFF;
        if ((len & 0x80) != 0) { len = ((len & 0x7F) << 8) | (d[p + 1] & 0xFF); p += 2; } else p++;
        return new String(d, p, len, StandardCharsets.UTF_8);
    }

    private static byte[] writeUtf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream(bytes.length + 5);
        len8(o, s.length());
        len8(o, bytes.length);
        o.write(bytes, 0, bytes.length);
        o.write(0);
        return o.toByteArray();
    }

    private static void len8(ByteArrayOutputStream o, int n) {
        if (n > 0x7F) { o.write(((n >> 8) & 0x7F) | 0x80); o.write(n & 0xFF); }
        else o.write(n);
    }

    // ------------------------------------------------------------ UTF-16 문자열
    private static String readUtf16(ByteBuffer b, int off) {
        int len = b.getShort(off) & 0xFFFF;
        int p = off + 2;
        if ((len & 0x8000) != 0) { len = ((len & 0x7FFF) << 16) | (b.getShort(p) & 0xFFFF); p += 2; }
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = b.getChar(p + i * 2);
        return new String(c);
    }

    private static byte[] writeUtf16(String s) {
        ByteBuffer o = ByteBuffer.allocate(4 + s.length() * 2 + 2).order(ByteOrder.LITTLE_ENDIAN);
        o.putShort((short) s.length());
        for (int i = 0; i < s.length(); i++) o.putChar(s.charAt(i));
        o.putShort((short) 0);
        byte[] r = new byte[o.position()];
        System.arraycopy(o.array(), 0, r, 0, r.length);
        return r;
    }

    /** 테스트용: 전역 문자열 표를 읽는다. */
    public static List<String> readPool(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pool = b.getShort(2) & 0xFFFF;
        int poolHeader = b.getShort(pool + 2) & 0xFFFF;
        int count = b.getInt(pool + 8);
        boolean utf8 = (b.getInt(pool + 16) & UTF8) != 0;
        int stringsStart = b.getInt(pool + 20);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int off = pool + stringsStart + b.getInt(pool + poolHeader + i * 4);
            out.add(utf8 ? readUtf8(data, off) : readUtf16(b, off));
        }
        return out;
    }
}
