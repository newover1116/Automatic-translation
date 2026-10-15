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
 * mode: "auto"(자동 감지), "en", "ja", "zh"
 * recognize()/translate()는 시간이 걸리므로 반드시 백그라운드 스레드에서 호출할 것.
 */
class Engine(val mode: String) {

    companion object {
        val NAMES = linkedMapOf(
            "auto" to "자동 감지 (일본어·영어·중국어)",
            "ja" to "일본어",
            "en" to "영어",
            "zh" to "중국어"
        )

        fun name(mode: String): String = NAMES[mode] ?: mode
    }

    // 일본어 인식기는 히라가나·가타카나·한자·알파벳을 모두 읽을 수 있어서 자동 감지에도 쓴다
    private val recognizer: TextRecognizer = when (mode) {
        "en" -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val translators = HashMap<String, Translator>()
    private val readyLangs = HashSet<String>()

    /** 한 번 번역한 문장은 저장해 두었다가 바로 꺼내 쓴다 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 1000
    }

    /** 이 모드에서 주로 쓰는 원문 언어 (미리 모델을 받아 둘 언어) */
    val primaryLang: String get() = if (mode == "auto") "ja" else mode

    private fun mlLang(lang: String): String = when (lang) {
        "ja" -> TranslateLanguage.JAPANESE
        "zh" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.ENGLISH
    }

    @Synchronized
    private fun translatorFor(lang: String): Translator =
        translators.getOrPut(lang) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(mlLang(lang))
                    .setTargetLanguage(TranslateLanguage.KOREAN)
                    .build()
            )
        }

    fun isReady(lang: String = primaryLang): Boolean = synchronized(readyLangs) { lang in readyLangs }

    /** 번역 모델을 미리 받아 둔다 (언어마다 처음 한 번, 약 30MB) */
    fun prepare(onDone: (Boolean) -> Unit) {
        val lang = primaryLang
        translatorFor(lang).downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                synchronized(readyLangs) { readyLangs.add(lang) }
                onDone(true)
            }
            .addOnFailureListener { onDone(false) }
    }

    /** 필요하면 모델을 받는다 (처음이면 오래 걸릴 수 있음) */
    fun ensureReady(lang: String) {
        if (isReady(lang)) return
        Tasks.await(translatorFor(lang).downloadModelIfNeeded(DownloadConditions.Builder().build()))
        synchronized(readyLangs) { readyLangs.add(lang) }
    }

    class Block(val text: String, val rect: Rect, val lang: String)

    fun recognize(bmp: Bitmap): List<Block> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
        val raw = ArrayList<Pair<String, Rect>>()
        for (b in result.textBlocks) {
            val r = b.boundingBox ?: continue
            val lines = b.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            // 알파벳 문장은 줄 사이에 띄어쓰기, 일본어·중국어는 그냥 이어 붙임
            val sb = StringBuilder(lines[0])
            for (i in 1 until lines.size) {
                val prev = sb[sb.length - 1]
                val next = lines[i][0]
                if (isLatinLetter(prev) && isLatinLetter(next)) sb.append(' ')
                sb.append(lines[i])
            }
            val text = sb.toString()
            if (text.none { it.isLetter() }) continue   // 숫자·기호만 있으면 건너뜀
            if (mostlyHangul(text)) continue             // 이미 한국어면 건너뜀
            raw.add(Pair(text, r))
        }
        // 화면 어딘가에 가나(ひらがな·カタカナ)가 있으면 한자만 있는 글도 일본어로 본다
        val screenHasKana = raw.any { (t, _) -> t.any { isKana(it) } }
        return raw.map { (t, r) -> Block(t, r, detect(t, screenHasKana)) }
    }

    private fun detect(text: String, screenHasKana: Boolean): String {
        if (mode != "auto") return mode
        if (text.any { isKana(it) }) return "ja"
        if (text.any { isHan(it) }) return if (screenHasKana) "ja" else "zh"
        return "en"
    }

    fun cached(text: String, lang: String): String? = synchronized(cache) { cache["$lang|$text"] }

    fun translate(text: String, lang: String): String {
        cached(text, lang)?.let { return it }
        ensureReady(lang)
        val out = Tasks.await(translatorFor(lang).translate(text))
        synchronized(cache) { cache["$lang|$text"] = out }
        return out
    }

    fun close() {
        try { recognizer.close() } catch (_: Exception) {}
        synchronized(this) {
            for (t in translators.values) {
                try { t.close() } catch (_: Exception) {}
            }
            translators.clear()
        }
    }

    private fun isKana(ch: Char) = ch in '぀'..'ヿ' || ch in 'ㇰ'..'ㇿ' || ch in 'ｦ'..'ﾟ'
    private fun isHan(ch: Char) = ch in '一'..'鿿' || ch in '㐀'..'䶿'
    private fun isLatinLetter(ch: Char) = ch in 'A'..'Z' || ch in 'a'..'z'

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
