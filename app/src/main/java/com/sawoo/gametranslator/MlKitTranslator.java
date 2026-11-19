package com.sawoo.gametranslator;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.sawoo.gametranslator.core.Lang;
import com.sawoo.gametranslator.core.Translator;

import java.util.ArrayList;
import java.util.List;

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
    @Override public int maxBatchSize() { return 20; }
    @Override public int maxBatchChars() { return 4000; }

    @Override
    public List<String> translate(List<String> texts) throws Exception {
        List<String> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(Tasks.await(client.translate(t)));
        return out;
    }

    @Override public void close() { client.close(); }
}
