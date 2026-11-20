package com.sawoo.gametranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * RPG 쯔꾸르 XP(.rxdata), VX(.rvdata), VX Ace(.rvdata2)의 Data 폴더. 포켓몬 에센셜도 XP 형식이다.
 * 이벤트 대사, 선택지, 데이터베이스 이름과 설명을 번역한다. 스크립트는 건드리지 않는다.
 */
public final class RxdataHandler implements FormatHandler {
    public static final Pattern PATH = Pattern.compile(
            "(?i)(?:^|.*/)data/(Map\\d+|CommonEvents|Troops|Actors|Classes|Skills|Items|Weapons|Armors|Enemies|States|System)"
            + "\\.(rxdata|rvdata|rvdata2)$");

    private static final String CMD = "RPG::EventCommand";

    @Override public String label() { return "RPG 쯔꾸르 XP/VX/VX Ace"; }

    @Override
    public byte[] transform(byte[] data, Context ctx, UnaryOperator<String> fn) {
        boolean xp = ctx.path.toLowerCase(Locale.ROOT).endsWith(".rxdata");
        RubyMarshal m = new RubyMarshal(data);
        visit(m.root, xp, fn);
        return m.write();
    }

    private void visit(RubyMarshal.Node n, boolean xp, UnaryOperator<String> fn) {
        if (n instanceof RubyMarshal.Arr) {
            RubyMarshal.Arr a = (RubyMarshal.Arr) n;
            if (isCommandList(a)) {
                commandList(a.items, xp, fn);
                return;
            }
            for (RubyMarshal.Node c : a.items) visit(c, xp, fn);
        } else if (n instanceof RubyMarshal.Hash) {
            for (RubyMarshal.Node c : ((RubyMarshal.Hash) n).values) visit(c, xp, fn);
        } else if (n instanceof RubyMarshal.Obj) {
            RubyMarshal.Obj o = (RubyMarshal.Obj) n;
            database(o, fn);
            for (RubyMarshal.Node c : o.ivars.values()) visit(c, xp, fn);
        }
    }

    private static boolean isCommandList(RubyMarshal.Arr a) {
        for (RubyMarshal.Node c : a.items) {
            if (c instanceof RubyMarshal.Obj && CMD.equals(((RubyMarshal.Obj) c).cls)) return true;
        }
        return false;
    }

    // ── 이벤트 명령 ──────────────────────────────────────────────

    private void commandList(List<RubyMarshal.Node> list, boolean xp, UnaryOperator<String> fn) {
        int i = 0;
        while (i < list.size()) {
            RubyMarshal.Node n = list.get(i);
            int code = code(n);
            boolean starts = xp ? code == 101 : (code == 401 || code == 405);
            if (starts) {
                // XP는 101이 첫 줄이고 401이 이어지는 줄, VX/VX Ace는 401(대사)과 405(스크롤 글)만 줄
                int cont = xp ? 401 : code;
                int indent = indent(n);
                List<RubyMarshal.Str> slots = new ArrayList<>();
                int j = i;
                do {
                    RubyMarshal.Str s = param(list.get(j), 0);
                    if (s == null) break;
                    slots.add(s);
                    j++;
                } while (j < list.size() && code(list.get(j)) == cont && indent(list.get(j)) == indent);
                if (!slots.isEmpty()) textGroup(slots, code == 405, fn);
                i = Math.max(j, i + 1);
                continue;
            }
            switch (code) {
                case 102: {   // 선택지
                    RubyMarshal.Node p = params(n, 0);
                    if (p instanceof RubyMarshal.Arr) {
                        for (RubyMarshal.Node c : ((RubyMarshal.Arr) p).items) apply(c, fn);
                    }
                    break;
                }
                case 402:     // 선택지 분기 (화면에는 안 나오지만 위와 맞춘다)
                case 320:     // 이름 변경
                case 324:     // 별명 변경 (VX Ace)
                case 325:     // 프로필 변경 (VX Ace)
                    apply(params(n, 1), fn);
                    break;
                default:
                    break;
            }
            i++;
        }
    }

    /** 여러 줄로 나뉜 대사를 한 문장으로 번역한 뒤, 원래 줄 수 안에 다시 나눠 담는다. */
    private void textGroup(List<RubyMarshal.Str> slots, boolean keepBreaks, UnaryOperator<String> fn) {
        List<String> lines = new ArrayList<>();
        int maxWidth = 0;
        for (RubyMarshal.Str s : slots) {
            lines.add(s.value);
            maxWidth = Math.max(maxWidth, RpgMakerHandler.width(s.value));
        }
        String joined = String.join("\n", lines);
        if (joined.trim().isEmpty()) return;
        String tr = fn.apply(joined);
        if (tr == null || tr.equals(joined)) return;

        List<String> wrapped;
        if (keepBreaks) {
            wrapped = new ArrayList<>();
            for (String l : tr.split("\\n", -1)) wrapped.add(l);
        } else {
            int limit = Math.max(30, Math.min(60, maxWidth));
            wrapped = RpgMakerHandler.wrap(tr.replaceAll("\\s*\\n\\s*", " ").trim(), limit);
        }
        // 명령 개수를 바꾸지 않는다. 남는 줄은 비우고, 넘치는 줄은 마지막 줄에 이어 붙인다.
        // (포켓몬 에센셜은 줄을 공백으로 이어 붙여 자동 줄바꿈하므로 어느 쪽이든 자연스럽다)
        int k = slots.size();
        for (int i = 0; i < k; i++) {
            String v;
            if (i < k - 1) v = i < wrapped.size() ? wrapped.get(i) : "";
            else v = wrapped.size() >= k ? String.join(" ", wrapped.subList(k - 1, wrapped.size())) : (i < wrapped.size() ? wrapped.get(i) : "");
            slots.get(i).replacement = v;
        }
    }

    // ── 데이터베이스 ─────────────────────────────────────────────

    private void database(RubyMarshal.Obj o, UnaryOperator<String> fn) {
        switch (o.cls) {
            case "RPG::Item": case "RPG::Weapon": case "RPG::Armor":
                field(o, "@name", fn);
                field(o, "@description", fn);
                break;
            case "RPG::Skill":
                field(o, "@name", fn);
                field(o, "@description", fn);
                field(o, "@message1", fn);
                field(o, "@message2", fn);
                break;
            case "RPG::Actor":
                field(o, "@name", fn);
                field(o, "@nickname", fn);
                field(o, "@description", fn);
                break;
            case "RPG::Class": case "RPG::Enemy":
                field(o, "@name", fn);
                break;
            case "RPG::State":
                field(o, "@name", fn);
                for (int i = 1; i <= 4; i++) field(o, "@message" + i, fn);
                break;
            case "RPG::Map":
                field(o, "@display_name", fn);
                break;
            case "RPG::System":
                field(o, "@game_title", fn);
                field(o, "@currency_unit", fn);
                for (String k : new String[]{"@elements", "@skill_types", "@weapon_types", "@armor_types"}) all(o.get(k), fn);
                break;
            case "RPG::System::Words":   // XP 용어
                for (RubyMarshal.Node v : o.ivars.values()) apply(v, fn);
                break;
            case "RPG::System::Terms":   // VX Ace 용어
                for (String k : new String[]{"@basic", "@params", "@etypes", "@commands"}) all(o.get(k), fn);
                break;
            case "RPG::System::Vocab":   // VX 용어
                for (RubyMarshal.Node v : o.ivars.values()) apply(v, fn);
                break;
            default:
                break;
        }
    }

    // ── 도우미 ─────────────────────────────────────────────────

    private static void field(RubyMarshal.Obj o, String name, UnaryOperator<String> fn) {
        apply(o.get(name), fn);
    }

    private static void all(RubyMarshal.Node n, UnaryOperator<String> fn) {
        if (n instanceof RubyMarshal.Arr) for (RubyMarshal.Node c : ((RubyMarshal.Arr) n).items) apply(c, fn);
    }

    private static void apply(RubyMarshal.Node n, UnaryOperator<String> fn) {
        if (!(n instanceof RubyMarshal.Str)) return;
        RubyMarshal.Str s = (RubyMarshal.Str) n;
        if (s.value.trim().isEmpty()) return;
        String t = fn.apply(s.value);
        if (t != null && !t.equals(s.value)) s.replacement = t;
    }

    private static int code(RubyMarshal.Node n) {
        if (!(n instanceof RubyMarshal.Obj)) return -1;
        RubyMarshal.Node c = ((RubyMarshal.Obj) n).get("@code");
        return c instanceof RubyMarshal.Int ? (int) ((RubyMarshal.Int) c).value : -1;
    }

    private static int indent(RubyMarshal.Node n) {
        if (!(n instanceof RubyMarshal.Obj)) return 0;
        RubyMarshal.Node c = ((RubyMarshal.Obj) n).get("@indent");
        return c instanceof RubyMarshal.Int ? (int) ((RubyMarshal.Int) c).value : 0;
    }

    private static RubyMarshal.Node params(RubyMarshal.Node n, int i) {
        if (!(n instanceof RubyMarshal.Obj)) return null;
        RubyMarshal.Node p = ((RubyMarshal.Obj) n).get("@parameters");
        if (!(p instanceof RubyMarshal.Arr)) return null;
        List<RubyMarshal.Node> items = ((RubyMarshal.Arr) p).items;
        return i < items.size() ? items.get(i) : null;
    }

    private static RubyMarshal.Str param(RubyMarshal.Node n, int i) {
        RubyMarshal.Node p = params(n, i);
        return p instanceof RubyMarshal.Str ? (RubyMarshal.Str) p : null;
    }
}
