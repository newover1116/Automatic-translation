package com.sawoo.gametranslator.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 파일 인코딩을 알아내고, 저장할 때 같은 방식으로 되돌린다. */
public final class Encoding {
    public final String text;
    private final Charset charset;
    private final byte[] bom;

    private Encoding(String text, Charset charset, byte[] bom) {
        this.text = text;
        this.charset = charset;
        this.bom = bom;
    }

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    public static Encoding decode(byte[] d, Lang lang) {
        if (startsWith(d, UTF8_BOM)) return of(d, 3, StandardCharsets.UTF_8, UTF8_BOM);
        if (d.length >= 2 && d[0] == (byte) 0xFF && d[1] == (byte) 0xFE)
            return of(d, 2, StandardCharsets.UTF_16LE, new byte[]{(byte) 0xFF, (byte) 0xFE});
        if (d.length >= 2 && d[0] == (byte) 0xFE && d[1] == (byte) 0xFF)
            return of(d, 2, StandardCharsets.UTF_16BE, new byte[]{(byte) 0xFE, (byte) 0xFF});
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(d)).toString();
            return new Encoding(s, StandardCharsets.UTF_8, new byte[0]);
        } catch (CharacterCodingException e) {
            // 옛날 게임의 Shift-JIS, GBK 파일. 한글은 담을 수 없어 저장은 UTF-8로 한다.
            String name = lang == Lang.JAPANESE ? "Shift_JIS" : lang == Lang.CHINESE ? "GBK" : "windows-1252";
            Charset legacy = Charset.isSupported(name) ? Charset.forName(name) : StandardCharsets.ISO_8859_1;
            return new Encoding(new String(d, legacy), StandardCharsets.UTF_8, UTF8_BOM);
        }
    }

    private static Encoding of(byte[] d, int skip, Charset cs, byte[] bom) {
        return new Encoding(new String(d, skip, d.length - skip, cs), cs, bom);
    }

    public byte[] encode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() + 16);
        out.write(bom, 0, bom.length);
        byte[] b = s.getBytes(charset);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    private static boolean startsWith(byte[] d, byte[] p) {
        if (d.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (d[i] != p[i]) return false;
        return true;
    }

    /** 이미지나 실행 파일 같은 바이너리인지 앞부분만 보고 판단한다. */
    public static boolean looksBinary(byte[] d) {
        if (d.length >= 2 && ((d[0] == (byte) 0xFF && d[1] == (byte) 0xFE) || (d[0] == (byte) 0xFE && d[1] == (byte) 0xFF)))
            return false;
        int n = Math.min(d.length, 8192);
        for (int i = 0; i < n; i++) if (d[i] == 0) return true;
        return false;
    }
}
