package com.sawoo.screentranslator

import android.content.Context
import android.graphics.RectF

/** 앱 설정 저장 (원문 언어, 번역 영역, 버튼 위치) */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 원문 언어: "en" 영어, "ja" 일본어, "zh" 중국어 */
    fun lang(c: Context): String = sp(c).getString("lang", "en") ?: "en"
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()

    /** 번역 영역 (화면 크기에 대한 0~1 비율). null이면 전체 화면 */
    fun roi(c: Context): RectF? {
        val s = sp(c)
        if (!s.getBoolean("roi_set", false)) return null
        return RectF(s.getFloat("roi_l", 0f), s.getFloat("roi_t", 0f), s.getFloat("roi_r", 1f), s.getFloat("roi_b", 1f))
    }

    fun setRoi(c: Context, r: RectF?) {
        val e = sp(c).edit()
        if (r == null) {
            e.putBoolean("roi_set", false)
        } else {
            e.putBoolean("roi_set", true)
                .putFloat("roi_l", r.left).putFloat("roi_t", r.top)
                .putFloat("roi_r", r.right).putFloat("roi_b", r.bottom)
        }
        e.apply()
    }

    fun buttonX(c: Context, def: Int): Int = sp(c).getInt("btn_x", def)
    fun buttonY(c: Context, def: Int): Int = sp(c).getInt("btn_y", def)
    fun setButtonPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("btn_x", x).putInt("btn_y", y).apply()
}
