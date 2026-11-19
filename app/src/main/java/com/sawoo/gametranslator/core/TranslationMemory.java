package com.sawoo.gametranslator.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 번역 결과를 한 묶음마다 파일에 덧붙여, 중간에 끊겨도 이어서 번역한다. */
public final class TranslationMemory {
    private final Map<String, String> map = new HashMap<>();
    private final File file;

    @SuppressWarnings("unchecked")
    public TranslationMemory(File file) throws IOException {
        this.file = file;
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    List<Object> pair = (List<Object>) Json.parse(line);
                    map.put((String) pair.get(0), (String) pair.get(1));
                } catch (RuntimeException ignored) {
                    // 앱이 강제 종료되며 마지막 줄이 잘린 경우
                }
            }
        }
    }

    public String get(String source) { return map.get(source); }
    public boolean has(String source) { return map.containsKey(source); }
    public int size() { return map.size(); }

    public void putAll(Map<String, String> entries) throws IOException {
        if (entries.isEmpty()) return;
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                List<Object> pair = new ArrayList<>();
                pair.add(e.getKey());
                pair.add(e.getValue());
                w.write(Json.write(pair));
                w.write('\n');
            }
        }
        map.putAll(entries);
    }
}
