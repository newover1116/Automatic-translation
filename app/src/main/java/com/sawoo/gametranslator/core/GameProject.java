package com.sawoo.gametranslator.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 게임 파일을 분석하고, 번역하고, 결과 파일을 만든다. */
public final class GameProject {
    public enum Kind { SINGLE, ZIP, APK }

    public static final String FONT_FILE = "gt_korean_font.ttf";
    private static final String FONT_SCRIPT = "zz_gt_korean_font.rpy";
    private static final long MAX_TEXT_FILE = 40L * 1024 * 1024;

    private static final Pattern EXCLUDED = Pattern.compile(
            "(?i)(?:^|/)(?:META-INF|node_modules|kotlin|okhttp3|org|com|google|firebase|fonts?|img|images?|audio|sounds?|movies?|video|effects?|shaders?|lib|libs|managed|il2cpp_data|bin/Data|renpy|__pycache__)/"
            + "|(?:^|/)(?:package(?:-lock)?|manifest|tsconfig|project|appsettings|google-services|version|config|settings|build|plugins)\\.[a-z]+$"
            + "|\\.min\\.[a-z]+$"
            + "|(?:^|/)data/(?:Animations|Tilesets|MapInfos)\\.json$");
    private static final Pattern TEXT_HINT = Pattern.compile(
            "(?i)(?:lang|locale|locali[sz]|i18n|l10n|translat|text|string|dialog|story|scenario|script|message|quest|subtitle|tutorial|talk|conversation|(?:^|[/_.-])(?:en|eng|english|ja|jp|japanese|zh|cn|chinese)(?:[/_.-]|$))");

    public static final class Item {
        public final String path;
        public final String format;
        public final int strings;
        public final boolean recommended;
        public boolean selected;

        Item(String path, String format, int strings, boolean recommended) {
            this.path = path;
            this.format = format;
            this.strings = strings;
            this.recommended = recommended;
            this.selected = recommended;
        }
    }

    public interface Progress {
        void update(int done, int total, String message);
        boolean cancelled();
    }

    public static final class Result {
        public int translated;
        public int failed;
    }

    public final File input;
    public final String displayName;
    public final Kind kind;
    public final Lang lang;
    public final List<Item> items = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    public String engine = "일반 파일";
    /** 자바/코틀린으로 만든 일반 안드로이드 앱 방식 게임 */
    private boolean plainApp;
    /** 대사가 스크립트 코드에 들어 있는 게임(HTML5, 티라노 등) */
    private boolean scriptGame;

    private GameProject(File input, String displayName, Kind kind, Lang lang) {
        this.input = input;
        this.displayName = displayName;
        this.kind = kind;
        this.lang = lang;
    }

    public static boolean isSupportedSingle(String name) {
        return handlerFor(name, Kind.SINGLE) != null;
    }

    // ------------------------------------------------------------ 분석
    public static GameProject analyze(File input, String displayName, Lang lang, Progress p) throws Exception {
        String low = displayName.toLowerCase(Locale.ROOT);
        Kind kind = low.endsWith(".apk") ? Kind.APK
                : low.endsWith(".zip") ? Kind.ZIP : Kind.SINGLE;
        if (low.endsWith(".xapk") || low.endsWith(".apks") || low.endsWith(".apkm")) {
            throw new IllegalArgumentException("분할 APK(.xapk, .apks)는 아직 지원하지 않아요. 하나로 된 .apk 파일을 넣어 주세요.");
        }
        if (kind == Kind.ZIP) {
            String inner = extractApk(input);
            if (inner != null) {
                GameProject g = analyze(new File(input.getParentFile(), "inner.apk"), inner, lang, p);
                g.notes.add(0, "압축 파일 안의 " + inner + " 을(를) 번역해요.");
                return g;
            }
        }
        GameProject g = new GameProject(input, displayName, kind, lang);
        if (kind == Kind.SINGLE) g.analyzeSingle();
        else g.analyzeArchive(p);
        if (g.items.isEmpty()) g.notes.add(0, "번역할 수 있는 문장을 찾지 못했어요. 원문 언어 설정이 맞는지 확인해 주세요.");
        return g;
    }

    /** 압축 파일이 사실상 APK 하나를 감싼 것이면 꺼내고 그 이름을 돌려준다. */
    private static String extractApk(File zipFile) throws IOException {
        try (ZipFile z = new ZipFile(zipFile)) {
            ZipEntry best = null;
            long total = 0;
            for (ZipEntry e : Collections.list(z.entries())) {
                total += Math.max(0, e.getSize());
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (!e.isDirectory() && n.endsWith(".apk") && !n.contains("__macosx")
                        && (best == null || e.getSize() > best.getSize())) best = e;
            }
            if (best == null || best.getSize() * 2 < total) return null;
            try (InputStream in = z.getInputStream(best);
                 OutputStream out = new FileOutputStream(new File(zipFile.getParentFile(), "inner.apk"))) {
                pipe(in, out);
            }
            String n = best.getName();
            return n.substring(n.lastIndexOf('/') + 1);
        }
    }

    private void analyzeSingle() throws Exception {
        FormatHandler h = handlerFor(displayName, Kind.SINGLE);
        if (h == null) throw new IllegalArgumentException("지원하지 않는 파일 형식이에요: " + displayName);
        byte[] data = Files.readAllBytes(input.toPath());
        Set<String> found = collect(h, displayName, data);
        if (!found.isEmpty()) items.add(new Item(displayName, h.label(), found.size(), true));
        engine = h.label();
    }

    private void analyzeArchive(Progress p) throws Exception {
        try (ZipFile zip = new ZipFile(input)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            Set<String> names = new HashSet<>();
            for (ZipEntry e : entries) names.add(e.getName());
            detectEngine(names);

            int done = 0;
            for (ZipEntry e : entries) {
                if (p.cancelled()) throw new InterruptedException();
                done++;
                if (done % 25 == 0) p.update(done, entries.size(), "파일 분석 중");
                FormatHandler h = eligible(e);
                if (h == null) continue;
                byte[] data = readAll(zip, e);
                if (!(h instanceof ArscHandler) && Encoding.looksBinary(data)) continue;
                Set<String> found;
                try {
                    found = collect(h, e.getName(), data);
                } catch (RuntimeException ex) {
                    continue; // 확장자만 같고 형식이 다른 파일은 건드리지 않는다
                }
                if (!found.isEmpty()) items.add(new Item(e.getName(), h.label(), found.size(), recommended(e.getName(), h)));
            }
        }
        items.sort((a, b) -> a.recommended != b.recommended ? (a.recommended ? -1 : 1) : b.strings - a.strings);
        if (items.isEmpty()) describeContents();
    }

    private FormatHandler eligible(ZipEntry e) {
        if (e.isDirectory() || e.getSize() > MAX_TEXT_FILE) return null;
        String n = e.getName();
        if (kind == Kind.APK && !n.startsWith("assets/") && !n.equals("resources.arsc")) return null;
        if (kind == Kind.APK && n.startsWith("assets/x-")) return null;
        if (EXCLUDED.matcher(n).find()) return null;
        FormatHandler h = handlerFor(n, kind);
        if (h instanceof CodeStringHandler && CodeStringHandler.isLibrary(n)) return null;
        return h;
    }

    private boolean recommended(String path, FormatHandler h) {
        if (h instanceof RpgMakerHandler || h instanceof RenpyHandler || h instanceof TyranoHandler) return true;
        if (h instanceof ArscHandler) return plainApp;
        if (h instanceof CodeStringHandler) return scriptGame || plainApp || TEXT_HINT.matcher(path).find();
        return kind != Kind.APK || TEXT_HINT.matcher(path).find();
    }

    static FormatHandler handlerFor(String path, Kind kind) {
        String low = path.toLowerCase(Locale.ROOT);
        if (RpgMakerHandler.PATH.matcher(path).matches()) return new RpgMakerHandler();
        if (low.endsWith(".rpy")) return new RenpyHandler();
        if (low.equals("resources.arsc")) return new ArscHandler();
        if (low.endsWith(".js") || low.endsWith(".mjs")) return new CodeStringHandler(false);
        if (low.endsWith(".lua")) return new CodeStringHandler(true);
        if (low.endsWith(".ks")) return new TyranoHandler();
        if (low.endsWith(".json")) return new SimpleHandlers.JsonHandler();
        if (low.endsWith(".csv") || low.endsWith(".tsv")) return new SimpleHandlers.CsvHandler();
        if (low.endsWith(".xml")) return new SimpleHandlers.XmlHandler();
        if (low.endsWith(".properties") || low.endsWith(".lang") || low.endsWith(".ini")
                || low.endsWith(".yml") || low.endsWith(".yaml")) return new SimpleHandlers.KeyValueHandler();
        if (low.endsWith(".txt")) return new SimpleHandlers.PlainTextHandler();
        return null;
    }

    private void detectEngine(Set<String> names) {
        boolean unity = false, unreal = false, godot = false, cocos = false, flutter = false, renpyLib = false;
        boolean rpgm = false, rpy = false, rpyc = false, rpa = false, vxace = false, html = false;
        boolean gamemaker = false, love = false, defold = false, solar = false, kirikiri = false, wolf = false;
        boolean tyrano = false, dex = false;
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.endsWith("libunity.so") || l.endsWith("libil2cpp.so") || l.contains("assets/bin/data/") || l.endsWith("globalgamemanagers")) unity = true;
            if (l.endsWith("libue4.so") || l.endsWith("libunreal.so") || l.endsWith(".pak")) unreal = true;
            if (l.endsWith("libgodot_android.so") || l.endsWith(".pck")) godot = true;
            if (l.contains("libcocos") || l.endsWith(".luac") || l.endsWith(".jsc")) cocos = true;
            if (l.endsWith("libflutter.so")) flutter = true;
            if (l.endsWith("librenpython.so") || l.contains("assets/x-game/") || l.endsWith("private.mp3")) renpyLib = true;
            if (RpgMakerHandler.PATH.matcher(n).matches()) rpgm = true;
            if (l.endsWith(".rpy")) rpy = true;
            if (l.endsWith(".rpyc")) rpyc = true;
            if (l.endsWith(".rpa")) rpa = true;
            if (l.endsWith(".rvdata2") || l.endsWith(".rvdata") || l.endsWith(".rxdata")) vxace = true;
            if (l.endsWith("assets/www/index.html") || l.equals("index.html") || l.endsWith("/index.html") && l.startsWith("assets/")) html = true;
            if (l.endsWith("libyoyo.so") || l.endsWith("game.droid") || l.endsWith("data.win")) gamemaker = true;
            if (l.endsWith("liblove.so") || l.endsWith(".love")) love = true;
            if (l.endsWith("libdmengine.so")) defold = true;
            if (l.endsWith("libcorona.so") || l.endsWith("libsolar2d.so") || l.endsWith("resource.car")) solar = true;
            if (l.endsWith(".xp3")) kirikiri = true;
            if (l.endsWith(".wolf")) wolf = true;
            if (l.endsWith(".ks") || l.contains("/tyrano/")) tyrano = true;
            if (l.matches("classes\\d*\\.dex")) dex = true;
        }
        if (rpgm) engine = "RPG 쯔꾸르 MV/MZ";
        else if (rpy) {
            engine = "렌파이";
            if (kind == Kind.APK) notes.add("APK 안의 렌파이 스크립트는 다시 컴파일되지 않아 번역이 반영되지 않을 수 있어요.");
        } else if (renpyLib || rpyc || rpa) {
            engine = "렌파이";
            notes.add("대사가 컴파일된 .rpyc/.rpa 파일에 들어 있어 번역할 수 없어요. .rpy 원본이 들어 있는 버전이 필요해요.");
        } else if (vxace) {
            engine = "RPG 쯔꾸르 VX/XP";
            notes.add("VX Ace 이하의 .rvdata 형식은 지원하지 않아요. MV/MZ 게임만 번역할 수 있어요.");
        } else if (unity) {
            engine = "유니티";
            notes.add("유니티 게임 본문은 컴파일된 파일 안에 있어 번역되지 않아요. StreamingAssets 같은 외부 텍스트만 번역할 수 있어요.");
        } else if (unreal) {
            engine = "언리얼";
            notes.add("언리얼 게임 본문은 .pak 안에 있어 번역되지 않아요. 외부 텍스트 파일만 번역할 수 있어요.");
        } else if (godot) {
            engine = "고도";
            notes.add("고도 게임 본문은 .pck 안에 있어 번역되지 않아요. 외부 텍스트 파일만 번역할 수 있어요.");
        } else if (cocos) {
            engine = "코코스";
            notes.add("코코스 게임의 컴파일된 스크립트(.luac, .jsc)는 번역되지 않아요. 외부 텍스트 파일만 번역할 수 있어요.");
        } else if (flutter) {
            engine = "플러터";
            notes.add("플러터 앱의 문구는 컴파일되어 있어 번역되지 않을 수 있어요.");
        } else if (gamemaker) {
            engine = "게임메이커";
            notes.add("게임메이커 게임은 대사가 game.droid 안에 들어 있고 한글 글자 모양이 없어 번역되지 않아요.");
        } else if (love) {
            engine = "LÖVE";
            notes.add("LÖVE 게임의 스크립트는 .love 묶음 안에 있어 아직 번역할 수 없어요.");
        } else if (defold) {
            engine = "디폴드";
            notes.add("디폴드 게임은 대사가 컴파일되어 있어 번역되지 않아요.");
        } else if (solar) {
            engine = "Solar2D";
            notes.add("Solar2D 게임은 스크립트가 resource.car 안에 컴파일되어 있어 번역되지 않아요.");
        } else if (kirikiri) {
            engine = "KiriKiri";
            notes.add("KiriKiri 게임의 .xp3 묶음은 아직 지원하지 않아요.");
        } else if (wolf) {
            engine = "울프 RPG";
            notes.add("울프 RPG의 .wolf 파일은 암호화되어 있어 번역되지 않아요.");
        } else if (tyrano) {
            engine = "티라노빌더";
            scriptGame = true;
        } else if (html) {
            engine = "HTML5";
            scriptGame = true;
        } else if (dex) {
            engine = "안드로이드 앱";
            plainApp = true;
        }
        if (kind == Kind.APK) {
            notes.add("번역된 APK는 서명이 달라져요. 원본 게임을 지운 뒤 설치해야 하고, 기존 세이브가 사라질 수 있어요.");
            notes.add("서명이나 파일 변조를 검사하는 게임, 온라인 게임은 실행되지 않을 수 있어요.");
        }
    }

    /** 번역할 곳을 못 찾았을 때 어떤 파일들로 이뤄졌는지 보여 준다. */
    private void describeContents() throws IOException {
        Map<String, Integer> ext = new LinkedHashMap<>();
        List<ZipEntry> big = new ArrayList<>();
        try (ZipFile zip = new ZipFile(input)) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                String n = e.getName();
                if (e.isDirectory() || n.startsWith("META-INF/") || n.startsWith("res/")) continue;
                String f = n.substring(n.lastIndexOf('/') + 1);
                int dot = f.lastIndexOf('.');
                String x = dot > 0 ? f.substring(dot).toLowerCase(Locale.ROOT) : "(확장자 없음)";
                ext.merge(x, 1, Integer::sum);
                if (!n.startsWith("lib/")) big.add(e);
            }
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(ext.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder("파일 구성: ");
        for (int i = 0; i < Math.min(6, top.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(top.get(i).getKey()).append(' ').append(top.get(i).getValue()).append("개");
        }
        notes.add(sb.toString());
        big.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
        StringBuilder bs = new StringBuilder("큰 파일: ");
        for (int i = 0; i < Math.min(3, big.size()); i++) {
            if (i > 0) bs.append(", ");
            long s = big.get(i).getSize();
            bs.append(big.get(i).getName()).append(" (")
              .append(s >= 1048576 ? (s / 1048576) + "MB" : Math.max(1, s / 1024) + "KB").append(')');
        }
        if (!big.isEmpty()) notes.add(bs.toString());
    }

    private Set<String> collect(FormatHandler h, String path, byte[] data) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        h.transform(data, new FormatHandler.Context(path, lang, null), s -> {
            if (s != null && s.codePoints().anyMatch(Character::isLetter)) out.add(s);
            return s;
        });
        return out;
    }

    // ------------------------------------------------------------ 번역
    public int selectedStrings() {
        int n = 0;
        for (Item i : items) if (i.selected) n += i.strings;
        return n;
    }

    public Result translate(Translator tr, TranslationMemory mem, Map<String, String> glossary, Progress p) throws Exception {
        p.update(0, 1, "번역할 문장 모으는 중");
        List<String> all = new ArrayList<>(selectedSources());
        List<String> todo = new ArrayList<>();
        Map<String, String> fixed = new LinkedHashMap<>();
        for (String s : all) {
            if (mem.has(s)) continue;
            String g = glossary.get(s.trim());
            if (g != null) fixed.put(s, keepEdges(s, g));
            else todo.add(s);
        }
        mem.putAll(fixed);

        Result r = new Result();
        int total = all.size();
        int done = total - todo.size();
        int i = 0;
        while (i < todo.size()) {
            if (p.cancelled()) throw new InterruptedException();
            p.update(done, total, "번역 중");
            int j = i, chars = 0;
            while (j < todo.size() && j - i < tr.maxBatchSize()
                    && (j == i || chars + todo.get(j).length() <= tr.maxBatchChars())) {
                chars += todo.get(j).length();
                j++;
            }
            List<String> batch = todo.subList(i, j);
            Map<String, String> res = tr.keepsTokens() ? translateMasked(tr, batch) : translatePieces(tr, batch);
            mem.putAll(res);
            r.failed += batch.size() - res.size();
            done += batch.size();
            i = j;
        }
        p.update(total, total, "번역 완료");
        r.translated = total - r.failed;
        return r;
    }

    private Map<String, String> translateMasked(Translator tr, List<String> batch) throws Exception {
        List<Protector.Masked> masks = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        for (String s : batch) {
            Protector.Masked m = Protector.mask(s.trim());
            masks.add(m);
            inputs.add(m.text);
        }
        List<String> out = tr.translate(inputs);
        Map<String, String> res = new LinkedHashMap<>();
        List<String> retry = new ArrayList<>();
        for (int k = 0; k < batch.size(); k++) {
            String u = k < out.size() ? Protector.unmask(out.get(k), masks.get(k).tags) : null;
            if (u != null && !u.trim().isEmpty()) res.put(batch.get(k), keepEdges(batch.get(k), u));
            else retry.add(batch.get(k));
        }
        // 토큰을 망가뜨린 문장은 태그 사이 조각만 다시 번역해 태그를 확실히 지킨다
        if (!retry.isEmpty()) res.putAll(translatePieces(tr, retry));
        return res;
    }

    private Map<String, String> translatePieces(Translator tr, List<String> batch) throws Exception {
        Map<String, String> res = new LinkedHashMap<>();
        for (String s : batch) {
            String core = s.trim();
            List<Protector.Piece> pieces = Protector.split(core);
            List<String> inputs = new ArrayList<>();
            for (Protector.Piece pc : pieces) if (pc.translatable) inputs.add(pc.text.trim());
            if (inputs.isEmpty()) { res.put(s, s); continue; }
            List<String> out = tr.translate(inputs);
            StringBuilder sb = new StringBuilder();
            int n = 0;
            boolean ok = out.size() == inputs.size();
            for (Protector.Piece pc : pieces) {
                if (!pc.translatable) { sb.append(pc.text); continue; }
                String t = ok ? out.get(n++) : null;
                if (t == null || t.trim().isEmpty()) { ok = false; break; }
                sb.append(keepEdges(pc.text, t.trim()));
            }
            if (ok) res.put(s, keepEdges(s, sb.toString()));
        }
        return res;
    }

    private static String keepEdges(String source, String translated) {
        int a = 0, b = source.length();
        while (a < b && Character.isWhitespace(source.charAt(a))) a++;
        while (b > a && Character.isWhitespace(source.charAt(b - 1))) b--;
        return source.substring(0, a) + translated.trim() + source.substring(b);
    }

    private Set<String> selectedSources() throws Exception {
        Set<String> all = new LinkedHashSet<>();
        if (kind == Kind.SINGLE) {
            if (!items.isEmpty() && items.get(0).selected) {
                all.addAll(collect(handlerFor(displayName, kind), displayName, Files.readAllBytes(input.toPath())));
            }
            return all;
        }
        try (ZipFile zip = new ZipFile(input)) {
            for (Item it : items) {
                if (!it.selected) continue;
                ZipEntry e = zip.getEntry(it.path);
                all.addAll(collect(handlerFor(it.path, kind), it.path, readAll(zip, e)));
            }
        }
        return all;
    }

    // ------------------------------------------------------------ 결과 만들기
    public String outputName() {
        int dot = displayName.lastIndexOf('.');
        return dot < 0 ? displayName + "_ko" : displayName.substring(0, dot) + "_ko" + displayName.substring(dot);
    }

    /** @param font 렌파이 게임에 넣을 한글 글꼴. 없으면 null. */
    public void build(File output, TranslationMemory mem, byte[] font, Progress p) throws Exception {
        UnaryOperator<String> fn = s -> {
            String t = mem.get(s);
            return t != null ? t : s;
        };
        if (kind == Kind.SINGLE) {
            byte[] data = Files.readAllBytes(input.toPath());
            byte[] out = handlerFor(displayName, kind).transform(data, new FormatHandler.Context(displayName, lang, null), fn);
            Files.write(output.toPath(), out);
            return;
        }
        Map<String, Item> selected = new LinkedHashMap<>();
        for (Item i : items) if (i.selected) selected.put(i.path, i);
        String renpyDir = font != null && kind == Kind.ZIP ? renpyGameDir(selected.keySet()) : null;
        String fontName = renpyDir != null ? FONT_FILE : null;

        try (ZipFile zip = new ZipFile(input);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output))) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            Set<String> written = new HashSet<>();
            int done = 0;
            for (ZipEntry e : entries) {
                if (p.cancelled()) throw new InterruptedException();
                done++;
                if (done % 20 == 0) p.update(done, entries.size(), "결과 파일 만드는 중");
                String name = e.getName();
                if (!written.add(name)) continue;
                if (kind == Kind.APK && isOldSignature(name)) continue;
                if (selected.containsKey(name)) {
                    byte[] out = handlerFor(name, kind).transform(readAll(zip, e),
                            new FormatHandler.Context(name, lang, fontName), fn);
                    writeBytes(zos, e, out);
                } else if (renpyDir != null && name.equals(renpyDir + "gui.rpy")) {
                    // 번역할 문장이 없어도 글꼴 설정은 한글 글꼴로 바꿔야 한다
                    byte[] out = new RenpyHandler().transform(readAll(zip, e),
                            new FormatHandler.Context(name, lang, fontName), s -> s);
                    writeBytes(zos, e, out);
                } else {
                    copy(zip, e, zos);
                }
            }
            if (renpyDir != null) {
                ZipEntry f = new ZipEntry(renpyDir + FONT_FILE);
                zos.putNextEntry(f);
                zos.write(font);
                zos.closeEntry();
                ZipEntry s = new ZipEntry(renpyDir + FONT_SCRIPT);
                zos.putNextEntry(s);
                zos.write(RenpyHandler.fontScript(FONT_FILE).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    private static String renpyGameDir(Set<String> paths) {
        for (String p : paths) {
            if (!p.toLowerCase(Locale.ROOT).endsWith(".rpy")) continue;
            int i = p.startsWith("game/") ? 0 : p.indexOf("/game/");
            if (i == 0) return "game/";
            if (i > 0) return p.substring(0, i + 6);
        }
        return null;
    }

    private static boolean isOldSignature(String n) {
        if (!n.startsWith("META-INF/")) return false;
        String u = n.toUpperCase(Locale.ROOT);
        return u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".DSA") || u.endsWith(".EC") || u.startsWith("META-INF/SIG-");
    }

    private static void writeBytes(ZipOutputStream zos, ZipEntry src, byte[] data) throws IOException {
        ZipEntry out = new ZipEntry(src.getName());
        if (src.getTime() > 0) out.setTime(src.getTime());
        if (src.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            out.setMethod(ZipEntry.STORED);
            out.setSize(data.length);
            out.setCompressedSize(data.length);
            out.setCrc(crc.getValue());
        } else {
            out.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(out);
        zos.write(data);
        zos.closeEntry();
    }

    private static void copy(ZipFile zip, ZipEntry src, ZipOutputStream zos) throws IOException {
        ZipEntry out = new ZipEntry(src.getName());
        if (src.getTime() > 0) out.setTime(src.getTime());
        if (src.getMethod() == ZipEntry.STORED) {
            out.setMethod(ZipEntry.STORED);
            out.setSize(src.getSize());
            out.setCompressedSize(src.getSize());
            out.setCrc(src.getCrc());
        } else {
            out.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(out);
        if (!src.isDirectory()) {
            try (InputStream in = zip.getInputStream(src)) {
                pipe(in, zos);
            }
        }
        zos.closeEntry();
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static byte[] readAll(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(Math.max(1024, (int) Math.min(e.getSize(), MAX_TEXT_FILE)));
            pipe(in, b);
            return b.toByteArray();
        }
    }

    /** 사용자가 적은 "원문=번역" 줄을 용어집으로 바꾼다. */
    public static Map<String, String> parseGlossary(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }
}
