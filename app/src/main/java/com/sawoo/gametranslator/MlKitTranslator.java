package com.sawoo.gametranslator;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.sawoo.gametranslator.core.Lang;
import com.sawoo.gametranslator.core.Translator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** 기기 안에서 동작하는 무료 번역. 언어 모델은 처음 한 번만 내려받는다. */
final class MlKitTranslator implements Translator {
    private final com.google.mlkit.nl.translate.Translator client;

    MlKitTranslator(Lang lang) throws Exception {
        String source;
        switch (lang) {
            case JAPANESE: source = TranslateLanguage.JAPANESE; break;
            case CHINESE: source = TranslateLanguage.CHINESE; break;
            default: source = TranslateLanguage.ENGLISH;
        }
        client = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build());
        Tasks.await(client.downloadModelIfNeeded(new DownloadConditions.Builder().build()));
    }

    @Override public boolean keepsTokens() { return false; }
    @Override public int maxBatchSize() { return 40; }
    @Override public int maxBatchChars() { return 4000; }

    /**
     * 한 줄짜리 문장들은 줄바꿈으로 이어 한 번에 번역하고, 줄 수가 맞으면 나눠 쓴다.
     * 줄 수가 어긋나거나 여러 줄짜리 문장은 하나씩 번역하되, 요청을 한꺼번에 넣고 기다린다.
     */
    @Override
    public List<String> translate(List<String> texts) throws Exception {
        List<String> out = new ArrayList<>(Collections.nCopies(texts.size(), (String) null));
        List<Integer> joinable = new ArrayList<>();
        List<Integer> single = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t.indexOf('\n') < 0 && t.indexOf('\r') < 0 && !t.trim().isEmpty()) joinable.add(i);
            else single.add(i);
        }

        if (joinable.size() >= 2) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < joinable.size(); k++) {
                if (k > 0) sb.append('\n');
                sb.append(texts.get(joinable.get(k)).trim());
            }
            String[] lines = null;
            try {
                String r = Tasks.await(client.translate(sb.toString()));
                if (r != null) lines = r.split("\n", -1);
            } catch (ExecutionException ignored) {
                // 아래에서 하나씩 다시 번역
            }
            boolean ok = lines != null && lines.length == joinable.size();
            if (ok) for (String l : lines) if (l.trim().isEmpty()) { ok = false; break; }
            if (ok) {
                for (int k = 0; k < joinable.size(); k++) out.set(joinable.get(k), lines[k].trim());
            } else {
                single.addAll(joinable);
            }
        } else {
            single.addAll(joinable);
        }

        List<Task<String>> tasks = new ArrayList<>(single.size());
        for (int i : single) tasks.add(client.translate(texts.get(i)));
        for (int k = 0; k < single.size(); k++) {
            try {
                out.set(single.get(k), Tasks.await(tasks.get(k)));
            } catch (ExecutionException e) {
                out.set(single.get(k), null);
            }
        }
        return out;
    }

    @Override public void close() { client.close(); }
}
