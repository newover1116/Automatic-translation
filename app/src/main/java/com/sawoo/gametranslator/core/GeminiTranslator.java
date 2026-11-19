package com.sawoo.gametranslator.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gemini API 번역. 문맥을 이해하므로 무료 기기 번역보다 품질이 좋다. */
public final class GeminiTranslator implements Translator {
    private final String apiKey;
    private final String model;
    private final String system;

    public GeminiTranslator(String apiKey, String model, Lang lang, Map<String, String> glossary) {
        this.apiKey = apiKey.trim();
        this.model = model.trim();
        StringBuilder sb = new StringBuilder()
                .append("You are a professional video game localizer. Translate each ").append(lang.english)
                .append(" string in the JSON array into natural, fluent Korean that suits game dialogue and UI.\n")
                .append("Rules:\n")
                .append("- Return only a JSON array of strings with exactly the same length and order.\n")
                .append("- Tokens such as ⟦0⟧ are game variables. Keep every token exactly once, unchanged. Move them only if Korean word order requires it.\n")
                .append("- Keep line breaks. Keep each speaker's tone consistent. Keep UI labels short.\n")
                .append("- Never add explanations or notes.\n");
        if (!glossary.isEmpty()) {
            sb.append("Always use this glossary:\n");
            for (Map.Entry<String, String> e : glossary.entrySet()) {
                sb.append("- ").append(e.getKey()).append(" => ").append(e.getValue()).append('\n');
            }
        }
        this.system = sb.toString();
    }

    @Override public boolean keepsTokens() { return true; }
    @Override public int maxBatchSize() { return 40; }
    @Override public int maxBatchChars() { return 6000; }

    @Override
    public List<String> translate(List<String> texts) throws Exception {
        List<String> parsed = request(texts);
        if (parsed != null && parsed.size() == texts.size()) return parsed;
        if (texts.size() == 1) return new ArrayList<>(Arrays.asList((String) null));
        // 응답 길이가 어긋나면 반으로 나눠 다시 요청한다
        int mid = texts.size() / 2;
        List<String> out = new ArrayList<>(translate(new ArrayList<>(texts.subList(0, mid))));
        out.addAll(translate(new ArrayList<>(texts.subList(mid, texts.size()))));
        return out;
    }

    private List<String> request(List<String> texts) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", parts(system));
        Map<String, Object> user = parts(Json.write(new ArrayList<Object>(texts)));
        user.put("role", "user");
        List<Object> contents = new ArrayList<>();
        contents.add(user);
        body.put("contents", contents);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("temperature", new Json.Num("0.2"));
        config.put("responseMimeType", "application/json");
        body.put("generationConfig", config);
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);

        IOException lastNetwork = null;
        for (int attempt = 0; attempt < 7; attempt++) {
            if (attempt > 0) Thread.sleep(Math.min(60000L, 2000L << (attempt - 1)));
            HttpURLConnection c = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(30000);
                c.setReadTimeout(180000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("x-goog-api-key", apiKey);
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
                int code = c.getResponseCode();
                if (code == 429 || code >= 500) continue;
                String resp = read(code < 400 ? c.getInputStream() : c.getErrorStream());
                if (code >= 400) throw new IllegalStateException(friendlyError(code, resp));
                return parse(resp);
            } catch (IOException e) {
                lastNetwork = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        if (lastNetwork != null) throw lastNetwork;
        throw new IllegalStateException("Gemini 사용 한도에 걸렸어요. 잠시 뒤 다시 시작하면 이어서 번역합니다.");
    }

    private static Map<String, Object> parts(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", text);
        List<Object> list = new ArrayList<>();
        list.add(part);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parts", list);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parse(String resp) {
        try {
            Map<String, Object> root = (Map<String, Object>) Json.parse(resp);
            List<Object> cands = (List<Object>) root.get("candidates");
            if (cands == null || cands.isEmpty()) return null;
            Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) cands.get(0)).get("content");
            if (content == null) return null;
            StringBuilder text = new StringBuilder();
            for (Object p : (List<Object>) content.get("parts")) {
                Object t = ((Map<String, Object>) p).get("text");
                if (t instanceof String) text.append(t);
            }
            Object arr = Json.parse(text.toString().trim());
            if (!(arr instanceof List)) return null;
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) arr) out.add(o instanceof String ? (String) o : null);
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String friendlyError(int code, String resp) {
        if (code == 400 && resp.contains("API_KEY")) return "Gemini API 키가 올바르지 않아요.";
        if (code == 401 || code == 403) return "Gemini API 키를 확인해 주세요. (오류 " + code + ")";
        if (code == 404) return "Gemini 모델 이름을 찾을 수 없어요. 설정에서 모델 이름을 확인해 주세요.";
        return "Gemini 오류 " + code + ": " + resp.substring(0, Math.min(200, resp.length()));
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
