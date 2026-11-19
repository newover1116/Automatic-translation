package com.sawoo.gametranslator.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** RPG 쯔꾸르 MV/MZ data 폴더의 JSON. */
public final class RpgMakerHandler implements FormatHandler {
    public static final Pattern PATH = Pattern.compile(
            "(?:^|.*/)data/(Map\\d+|CommonEvents|Troops|Actors|Classes|Skills|Items|Weapons|Armors|Enemies|States|System)\\.json$");

    private static final Pattern CODES = Pattern.compile(
            "\\\\[A-Za-z]+\\d*(?:\\[[^\\]]*\\])?|\\\\[.!|^<>\\{\\}\\$\\\\]");

    @Override public String label() { return "RPG 쯔꾸르 MV/MZ"; }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
        Encoding enc = Encoding.decode(data, ctx.lang);
        Object root = Json.parse(enc.text);
        String file = ctx.path.substring(ctx.path.lastIndexOf('/') + 1).replace(".json", "");

        if (file.startsWith("Map") && root instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) root;
            field(map, "displayName", fn);
            for (Object ev : list(map.get("events"))) {
                if (ev instanceof Map) pages((Map<String, Object>) ev, fn);
            }
        } else if (file.equals("CommonEvents")) {
            for (Object ev : list(root)) {
                if (ev instanceof Map) eventList((Map<String, Object>) ev, fn);
            }
        } else if (file.equals("Troops")) {
            for (Object t : list(root)) {
                if (t instanceof Map) pages((Map<String, Object>) t, fn);
            }
        } else if (file.equals("System") && root instanceof Map) {
            system((Map<String, Object>) root, fn);
        } else {
            String[] keys = databaseKeys(file);
            for (Object row : list(root)) {
                if (row instanceof Map) for (String k : keys) field((Map<String, Object>) row, k, fn);
            }
        }
        return enc.encode(Json.write(root));
    }

    private static String[] databaseKeys(String file) {
        switch (file) {
            case "Actors": return new String[]{"name", "nickname", "profile"};
            case "Classes": case "Enemies": return new String[]{"name"};
            case "Skills": return new String[]{"name", "description", "message1", "message2"};
            case "States": return new String[]{"name", "message1", "message2", "message3", "message4"};
            default: return new String[]{"name", "description"};
        }
    }

    @SuppressWarnings("unchecked")
    private void system(Map<String, Object> sys, UnaryOperator<String> fn) {
        field(sys, "gameTitle", fn);
        field(sys, "currencyUnit", fn);
        for (String k : new String[]{"elements", "skillTypes", "weaponTypes", "armorTypes", "equipTypes"}) {
            strings(list(sys.get(k)), fn);
        }
        Object terms = sys.get("terms");
        if (terms instanceof Map) {
            Map<String, Object> t = (Map<String, Object>) terms;
            for (String k : new String[]{"basic", "commands", "params"}) strings(list(t.get(k)), fn);
            Object msgs = t.get("messages");
            if (msgs instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) msgs;
                for (String k : new ArrayList<>(m.keySet())) field(m, k, fn);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void pages(Map<String, Object> owner, UnaryOperator<String> fn) {
        for (Object p : list(owner.get("pages"))) {
            if (p instanceof Map) eventList((Map<String, Object>) p, fn);
        }
    }

    @SuppressWarnings("unchecked")
    private void eventList(Map<String, Object> owner, UnaryOperator<String> fn) {
        Object l = owner.get("list");
        if (!(l instanceof List)) return;
        List<Object> in = (List<Object>) l;
        List<Object> out = new ArrayList<>(in.size());
        int i = 0;
        while (i < in.size()) {
            Object o = in.get(i);
            int code = codeOf(o);
            if (code == 401 || code == 405) {
                // 여러 줄로 쪼개진 대사를 한 문장으로 합쳐 번역해야 문맥이 살아난다
                int indent = indentOf(o);
                int j = i;
                List<Object> group = new ArrayList<>();
                while (j < in.size() && codeOf(in.get(j)) == code && indentOf(in.get(j)) == indent) group.add(in.get(j++));
                out.addAll(textGroup(group, code, fn));
                i = j;
                continue;
            }
            if (o instanceof Map) command((Map<String, Object>) o, code, fn);
            out.add(o);
            i++;
        }
        owner.put("list", out);
    }

    @SuppressWarnings("unchecked")
    private List<Object> textGroup(List<Object> group, int code, UnaryOperator<String> fn) {
        List<String> lines = new ArrayList<>();
        int maxWidth = 0;
        for (Object c : group) {
            List<Object> p = list(((Map<String, Object>) c).get("parameters"));
            String s = !p.isEmpty() && p.get(0) instanceof String ? (String) p.get(0) : "";
            lines.add(s);
            maxWidth = Math.max(maxWidth, width(s));
        }
        String joined = String.join("\n", lines);
        if (joined.trim().isEmpty()) return group;
        String tr = fn.apply(joined);
        if (tr == null || tr.equals(joined)) return group;

        List<String> wrapped;
        if (code == 401) {
            int limit = Math.max(30, Math.min(60, maxWidth));
            wrapped = wrap(tr.replaceAll("\\s*\\n\\s*", " ").trim(), limit);
        } else {
            wrapped = new ArrayList<>(Arrays.asList(tr.split("\\n", -1)));
        }
        List<Object> out = new ArrayList<>();
        for (String w : wrapped) {
            Map<String, Object> cmd = new LinkedHashMap<>((Map<String, Object>) group.get(0));
            List<Object> params = new ArrayList<>();
            params.add(w);
            cmd.put("parameters", params);
            out.add(cmd);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void command(Map<String, Object> cmd, int code, UnaryOperator<String> fn) {
        Object po = cmd.get("parameters");
        if (!(po instanceof List)) return;
        List<Object> p = (List<Object>) po;
        switch (code) {
            case 101: index(p, 4, fn); break;             // MZ 이름 칸
            case 102: if (!p.isEmpty()) strings(list(p.get(0)), fn); break;  // 선택지
            case 402: index(p, 1, fn); break;             // 선택지 분기
            case 320: case 324: case 325: index(p, 1, fn); break;  // 이름, 별명, 프로필 변경
            default: break;
        }
    }

    /** 한글 2칸, 영문 1칸 기준으로 창 너비에 맞게 줄을 나눈다. 제어 코드는 너비에서 뺀다. */
    static List<String> wrap(String text, int limit) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int w = 0;
        for (String word : text.split(" ")) {
            int ww = width(word);
            if (cur.length() > 0 && w + 1 + ww > limit) {
                out.add(cur.toString());
                cur.setLength(0);
                w = 0;
            }
            if (cur.length() > 0) { cur.append(' '); w++; }
            cur.append(word);
            w += ww;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static int width(String s) {
        String bare = CODES.matcher(s).replaceAll("");
        int w = 0;
        for (int i = 0; i < bare.length(); i++) w += bare.charAt(i) >= 0x1100 ? 2 : 1;
        return w;
    }

    private static void field(Map<String, Object> m, String k, UnaryOperator<String> fn) {
        Object v = m.get(k);
        if (v instanceof String && !((String) v).trim().isEmpty()) m.put(k, fn.apply((String) v));
    }

    private static void index(List<Object> p, int i, UnaryOperator<String> fn) {
        if (i < p.size() && p.get(i) instanceof String && !((String) p.get(i)).trim().isEmpty()) {
            p.set(i, fn.apply((String) p.get(i)));
        }
    }

    private static void strings(List<Object> l, UnaryOperator<String> fn) {
        for (int i = 0; i < l.size(); i++) index(l, i, fn);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static int codeOf(Object o) {
        if (!(o instanceof Map)) return -1;
        Object c = ((Map<String, Object>) o).get("code");
        return c instanceof Json.Num ? ((Json.Num) c).intValue() : -1;
    }

    @SuppressWarnings("unchecked")
    private static int indentOf(Object o) {
        if (!(o instanceof Map)) return 0;
        Object c = ((Map<String, Object>) o).get("indent");
        return c instanceof Json.Num ? ((Json.Num) c).intValue() : 0;
    }
}
