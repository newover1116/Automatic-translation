package com.sawoo.gametranslator.core;

import java.util.Locale;
import java.util.function.UnaryOperator;

/** 티라노빌더/KAG 시나리오(.ks): [태그]는 지키고 대사 줄만 번역한다. */
public final class TyranoHandler implements FormatHandler {
    @Override public String label() { return "티라노 시나리오"; }

    @Override
    public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
        Encoding enc = Encoding.decode(data, ctx.lang);
        StringBuilder out = new StringBuilder(enc.text.length() + 64);
        String block = null;
        for (String raw : enc.text.split("(?<=\\n)", -1)) {
            String eol = raw.endsWith("\r\n") ? "\r\n" : raw.endsWith("\n") ? "\n" : "";
            String line = raw.substring(0, raw.length() - eol.length());
            String t = line.trim();
            String low = t.toLowerCase(Locale.ROOT);
            if (block != null) {
                if (low.contains("[end" + block) || low.startsWith("@end" + block)) block = null;
                out.append(raw);
                continue;
            }
            if (low.startsWith("[iscript") || low.startsWith("@iscript")) { block = "script"; out.append(raw); continue; }
            if (low.startsWith("[html") || low.startsWith("@html")) { block = "html"; out.append(raw); continue; }
            if (t.isEmpty() || t.startsWith(";") || t.startsWith("*") || t.startsWith("@") || t.startsWith("#")
                    || !TextRules.isDialogue(t, ctx.lang)) {
                out.append(raw);
                continue;
            }
            int lead = line.indexOf(t);
            String tr = fn.apply(t);
            out.append(line, 0, lead).append(tr == null ? t : tr).append(line.substring(lead + t.length())).append(eol);
        }
        return enc.encode(out.toString());
    }
}
