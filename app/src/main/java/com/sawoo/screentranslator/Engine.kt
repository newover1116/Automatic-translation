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
import java.text.Normalizer
import kotlin.math.max

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

    /**
     * 번역할 글 덩어리 하나 (말풍선 하나, 문단 하나).
     * vertical: 세로쓰기였는지. thickness: 원문 글자 크기(px, 캡처 기준).
     */
    class Block(val text: String, val rect: Rect, val lang: String, val vertical: Boolean, val thickness: Int)

    private class Ln(val text: String, val r: Rect)

    private class Grp(val lines: MutableList<Ln>, val vertical: Boolean) {
        fun rect(): Rect {
            val u = Rect(lines[0].r)
            for (l in lines) u.union(l.r)
            return u
        }

        /** 줄 두께의 중앙값 = 대략적인 글자 크기 */
        fun thickness(): Int {
            val xs = lines.map { if (vertical) it.r.width() else it.r.height() }.sorted()
            return max(1, xs[xs.size / 2])
        }
    }

    fun recognize(bmp: Bitmap): List<Block> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
        val groups = ArrayList<Grp>()
        for (b in result.textBlocks) {
            val lines = ArrayList<Ln>()
            for (l in b.lines) {
                val r = l.boundingBox ?: continue
                val t = clean(l.text)
                if (t.isNotEmpty()) lines.add(Ln(t, r))
            }
            if (lines.isEmpty()) continue
            // 세로로 긴 줄이 더 많으면 세로쓰기(만화 말풍선 등)
            var vScore = 0
            var hScore = 0
            for (l in lines) {
                val w = l.r.width()
                val h = l.r.height()
                if (h > w * 1.4f) vScore += l.text.length
                else if (w > h * 1.4f) hScore += l.text.length
            }
            groups.add(Grp(lines, mode != "en" && vScore > hScore))
        }
        mergeVertical(groups)

        val out = ArrayList<Pair<Grp, String>>()
        for (g in groups) {
            val text = assemble(g)
            if (text.none { it.isLetter() }) continue   // 숫자·기호만 있으면 건너뜀
            if (mostlyHangul(text)) continue             // 이미 한국어면 건너뜀
            out.add(Pair(g, text))
        }
        // 화면 어딘가에 가나(ひらがな·カタカナ)가 있으면 한자만 있는 글도 일본어로 본다
        val screenHasKana = out.any { p -> p.second.any { isKana(it) } }
        return out.map { (g, t) -> Block(t, g.rect(), detect(t, screenHasKana), g.vertical, g.thickness()) }
    }

    /** 같은 말풍선인데 따로 인식된 세로 줄 덩어리들을 하나로 합친다 */
    private fun mergeVertical(groups: MutableList<Grp>) {
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in groups.indices) {
                val a = groups[i]
                if (!a.vertical) continue
                for (j in i + 1 until groups.size) {
                    val b = groups[j]
                    if (!b.vertical) continue
                    val m = (max(a.thickness(), b.thickness()) * 0.6f).toInt()
                    val ra = a.rect()
                    ra.inset(-m, -m)
                    if (Rect.intersects(ra, b.rect())) {
                        a.lines.addAll(b.lines)
                        groups.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
    }

    /** 줄들을 읽는 순서대로 이어 붙여 한 문장으로 만든다 */
    private fun assemble(g: Grp): String {
        val med = g.thickness().toFloat()
        // 한자 옆의 작은 읽기 표시(후리가나)는 빼야 번역이 자연스럽다
        var lines: List<Ln> = g.lines.filter { l ->
            val th = if (g.vertical) l.r.width() else l.r.height()
            !(th < med * 0.6f && l.text.length <= 8 && l.text.all { isKana(it) })
        }
        if (lines.isEmpty()) lines = g.lines
        val sorted = if (g.vertical) {
            lines.sortedByDescending { it.r.centerX() }          // 세로쓰기: 오른쪽 줄부터
        } else {
            val row = max(1f, med * 0.7f)
            lines.sortedWith(compareBy<Ln>({ (it.r.top / row).toInt() }, { it.r.left }))
        }
        val sb = StringBuilder(sorted[0].text)
        for (i in 1 until sorted.size) {
            val t = sorted[i].text
            // 알파벳끼리는 띄어쓰기, 일본어·중국어는 그냥 이어 붙임
            if (isLatinLetter(sb[sb.length - 1]) && isLatinLetter(t[0])) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    private val longVowel = Regex("(?<=[\\u3040-\\u30FF])[|｜丨](?=[\\u3040-\\u30FF]|$)")

    /** 전각/반각·세로쓰기용 문자 정리, 세로 장음(ー)이 막대로 읽힌 것 복구 */
    private fun clean(s: String): String {
        val t = Normalizer.normalize(s, Normalizer.Form.NFKC).trim()
        return longVowel.replace(t, "ー")
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
