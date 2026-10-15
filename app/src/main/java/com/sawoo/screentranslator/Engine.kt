package com.sawoo.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * 글자 인식(OCR) + 번역 엔진. 모두 기기 안에서 동작한다.
 * recognize()/translate()는 시간이 걸리므로 반드시 백그라운드 스레드에서 호출할 것.
 */
class Engine(private val lang: String) {

    private val recognizer: TextRecognizer = when (lang) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(
                when (lang) {
                    "ja" -> TranslateLanguage.JAPANESE
                    "zh" -> TranslateLanguage.CHINESE
                    else -> TranslateLanguage.ENGLISH
                }
            )
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )

    @Volatile
    var ready = false
        private set

    /** 한 번 번역한 문장은 저장해 두었다가 바로 꺼내 쓴다 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 1000
    }

    /** 번역 모델을 미리 받아 둔다 (처음 한 번, 약 30MB) */
    fun prepare(onDone: (Boolean) -> Unit) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { ready = true; onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    fun ensureReady() {
        if (ready) return
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        ready = true
    }

    class Block(val text: String, val rect: Rect)

    fun recognize(bmp: Bitmap): List<Block> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
        val joiner = if (lang == "en") " " else ""
        val out = ArrayList<Block>()
        for (b in result.textBlocks) {
            val r = b.boundingBox ?: continue
            val text = b.lines.joinToString(joiner) { it.text.trim() }.trim()
            if (text.isEmpty()) continue
            if (text.none { it.isLetter() }) continue   // 숫자·기호만 있으면 건너뜀
            if (mostlyHangul(text)) continue             // 이미 한국어면 건너뜀
            out.add(Block(text, r))
        }
        return out
    }

    fun cached(text: String): String? = synchronized(cache) { cache[text] }

    fun translate(text: String): String {
        cached(text)?.let { return it }
        val out = Tasks.await(translator.translate(text))
        synchronized(cache) { cache[text] = out }
        return out
    }

    fun close() {
        try { recognizer.close() } catch (_: Exception) {}
        try { translator.close() } catch (_: Exception) {}
    }

    private fun mostlyHangul(s: String): Boolean {
        var letters = 0
        var hangul = 0
        for (ch in s) {
            if (!ch.isLetter()) continue
            letters++
            if (ch in '가'..'힣' || ch in 'ㄱ'..'ㆎ') hangul++
        }
        return letters > 0 && hangul * 2 > letters
    }
}
