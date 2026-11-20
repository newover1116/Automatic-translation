package com.sawoo.gametranslator.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 한글 글꼴에 원래 게임 글꼴의 이름표('name' 테이블)를 붙인다.
 * 게임은 글꼴을 이름으로 찾기 때문에, 파일만 바꿔 끼우면 이름이 같은 한글 글꼴을 쓰게 된다.
 */
final class FontPatch {
    private FontPatch() {}

    private static final class Table {
        final String tag;
        final byte[] data;
        Table(String tag, byte[] data) { this.tag = tag; this.data = data; }
    }

    /** 실패하면 null (원래 글꼴을 그대로 둔다). */
    static byte[] withNameOf(byte[] korean, byte[] original) {
        try {
            byte[] name = table(original, "name");
            if (name == null) return null;
            int version = ByteBuffer.wrap(korean).getInt(0);
            List<Table> tables = tables(korean);
            boolean found = false;
            for (int i = 0; i < tables.size(); i++) {
                if (tables.get(i).tag.equals("name")) {
                    tables.set(i, new Table("name", name));
                    found = true;
                }
            }
            if (!found) return null;
            return assemble(version, tables);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int fontOffset(ByteBuffer b) {
        // 글꼴 모음(.ttc)이면 첫 글꼴을 쓴다
        if (b.getInt(0) == 0x74746366) return b.getInt(12);
        return 0;
    }

    static byte[] table(byte[] font, String tag) {
        ByteBuffer b = ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN);
        int base = fontOffset(b);
        int n = b.getShort(base + 4) & 0xffff;
        for (int i = 0; i < n; i++) {
            int r = base + 12 + i * 16;
            if (tagAt(font, r).equals(tag)) {
                int off = b.getInt(r + 8), len = b.getInt(r + 12);
                if (off < 0 || len < 0 || off + len > font.length) return null;
                byte[] out = new byte[len];
                System.arraycopy(font, off, out, 0, len);
                return out;
            }
        }
        return null;
    }

    private static List<Table> tables(byte[] font) {
        ByteBuffer b = ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN);
        int n = b.getShort(4) & 0xffff;
        List<Table> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int r = 12 + i * 16;
            int off = b.getInt(r + 8), len = b.getInt(r + 12);
            byte[] data = new byte[len];
            System.arraycopy(font, off, data, 0, len);
            out.add(new Table(tagAt(font, r), data));
        }
        return out;
    }

    private static byte[] assemble(int version, List<Table> tables) {
        int n = tables.size();
        int pow = 1, log = 0;
        while (pow * 2 <= n) { pow *= 2; log++; }
        int size = 12 + 16 * n;
        for (Table t : tables) size += (t.data.length + 3) & ~3;
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        out.putInt(version).putShort((short) n).putShort((short) (pow * 16))
           .putShort((short) log).putShort((short) (n * 16 - pow * 16));
        int offset = 12 + 16 * n;
        int headOffset = -1;
        for (Table t : tables) {
            byte[] data = t.data;
            if (t.tag.equals("head") && data.length >= 12) {
                data = data.clone();
                data[8] = data[9] = data[10] = data[11] = 0;   // checkSumAdjustment
            }
            int r = out.position();
            for (int k = 0; k < 4; k++) out.put((byte) t.tag.charAt(k));
            out.putInt((int) checksum(data, 0, data.length)).putInt(offset).putInt(data.length);
            int keep = out.position();
            out.position(offset);
            out.put(data);
            if (t.tag.equals("head")) headOffset = offset;
            offset += (data.length + 3) & ~3;
            out.position(keep);
            if (r < 0) throw new IllegalStateException();
        }
        byte[] font = out.array();
        if (headOffset >= 0) {
            long adj = (0xB1B0AFBAL - checksum(font, 0, font.length)) & 0xffffffffL;
            ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN).putInt(headOffset + 8, (int) adj);
        }
        return font;
    }

    private static long checksum(byte[] d, int off, int len) {
        long sum = 0;
        int end = off + len;
        for (int i = off; i < end; i += 4) {
            long v = 0;
            for (int k = 0; k < 4; k++) v = (v << 8) | (i + k < end ? d[i + k] & 0xff : 0);
            sum = (sum + v) & 0xffffffffL;
        }
        return sum;
    }

    private static String tagAt(byte[] d, int r) {
        return new String(new char[]{(char) (d[r] & 0xff), (char) (d[r + 1] & 0xff), (char) (d[r + 2] & 0xff), (char) (d[r + 3] & 0xff)});
    }
}
