package com.sawoo.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 번역문 박스를 원래 글자 위에 겹쳐 그리는 전체 화면 뷰.
 * 박스 좌표는 "화면 좌표"로 받고, 그릴 때 이 뷰의 화면상 위치만큼 보정한다.
 */
class BoxesView(ctx: Context) : View(ctx) {

    class Box(val rect: RectF, val text: String)
    private class Laid(val bg: RectF, val layout: StaticLayout)

    private val density = resources.displayMetrics.density
    private val pad = 3f * density
    private val minText = 10f * density
    private val maxText = 24f * density

    @Volatile
    private var items: List<Laid> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0141414.toInt() }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val loc = IntArray(2)

    val hasBoxes: Boolean get() = items.isNotEmpty()

    /** 화면 좌표 기준 박스 영역들 (변화 감지용) */
    fun boxRects(): List<RectF> = items.map { it.bg }

    /** 새 번역 결과를 받기 시작: 기존 박스를 지우고 보이게 한다 */
    fun begin() {
        items = emptyList()
        visibility = VISIBLE
        invalidate()
    }

    fun add(box: Box) {
        items = items + layoutBox(box)
        visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        items = emptyList()
        invalidate()
    }

    /** 캡처 직전에 잠깐 숨긴다. 숨긴 게 있으면 true */
    fun hideForCapture(): Boolean {
        if (items.isEmpty() || visibility != VISIBLE) return false
        visibility = INVISIBLE
        return true
    }

    fun showAgain() {
        visibility = VISIBLE
    }

    private fun layoutBox(b: Box): Laid {
        val width = max(b.rect.width(), 28f * density)
        val innerW = max(1, (width - 2 * pad).toInt())
        val innerH = b.rect.height() - 2 * pad
        val text = b.text
        var size = (b.rect.height() * 0.75f).coerceIn(minText, maxText)
        var layout: StaticLayout
        while (true) {
            textPaint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            if (layout.height <= innerH || size <= minText) break
            size = max(minText, size * 0.9f)
        }
        val h = max(b.rect.height(), layout.height + 2 * pad)
        val bg = RectF(b.rect.left, b.rect.top, b.rect.left + width, b.rect.top + h)
        return Laid(bg, layout)
    }

    override fun onDraw(canvas: Canvas) {
        val list = items
        if (list.isEmpty()) return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (it in list) {
            canvas.drawRoundRect(it.bg, 6f * density, 6f * density, bgPaint)
            canvas.save()
            canvas.translate(it.bg.left + pad, it.bg.top + pad)
            it.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}

/**
 * 번역할 영역을 손가락으로 드래그해서 고르는 전체 화면 뷰.
 * onDone에는 화면 좌표 사각형(취소하면 null)이 전달된다.
 */
class RegionSelectView(ctx: Context, private val onDone: (RectF?) -> Unit) : View(ctx) {

    private val density = resources.displayMetrics.density
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var hasRect = false
    private var finished = false
    private val loc = IntArray(2)

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val fill = Paint().apply { color = 0x334FC3F7 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFF4FC3F7.toInt()
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (finished) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.rawX; sy = e.rawY; ex = sx; ey = sy
                hasRect = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                ex = e.rawX; ey = e.rawY
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                ex = e.rawX; ey = e.rawY
                finished = true
                val r = RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey))
                val ok = r.width() > 40f * density && r.height() > 20f * density
                onDone(if (ok) r else null)
            }
            MotionEvent.ACTION_CANCEL -> {
                hasRect = false
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        val cx = width / 2f
        val ty = height * 0.12f
        canvas.drawText("번역할 영역을 손가락으로 드래그하세요", cx, ty, textPaint)
        canvas.drawText("짧게 탭하면 취소돼요", cx, ty + 26f * density, textPaint)
        if (hasRect) {
            val r = RectF(
                min(sx, ex) - loc[0], min(sy, ey) - loc[1],
                max(sx, ex) - loc[0], max(sy, ey) - loc[1]
            )
            canvas.drawRect(r, fill)
            canvas.drawRect(r, stroke)
        }
    }
}
