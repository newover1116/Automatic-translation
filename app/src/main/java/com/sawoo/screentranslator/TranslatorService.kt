package com.sawoo.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 화면 캡처를 계속 켜 두고, 버튼(또는 화면 변화)에 맞춰
 * 글자 인식 → 번역 → 원래 글자 위에 번역 박스 표시를 하는 서비스.
 *
 * 속도를 위한 장치
 *  - 캡처를 미리 켜 두고 가장 최근 화면만 들고 있다가 바로 꺼내 쓴다
 *  - 캡처 해상도를 최대 가로 1080으로 줄인다
 *  - 번역 영역을 지정하면 그 부분만 인식한다
 *  - 한 번 번역한 문장은 캐시에서 즉시 꺼낸다 (캐시된 것부터 먼저 표시)
 *  - 번역은 끝나는 대로 박스를 하나씩 바로 띄운다
 *  - 자동 모드는 화면이 실제로 바뀌었을 때만 다시 번역한다
 */
class TranslatorService : Service() {

    companion object {
        const val ACTION_START = "com.sawoo.screentranslator.START"
        const val ACTION_STOP = "com.sawoo.screentranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val CHANNEL_ID = "translator"
        private const val NOTIF_ID = 1
        private const val COLOR_IDLE = 0xFF1565C0.toInt()
        private const val COLOR_AUTO = 0xFF2E7D32.toInt()
        private const val COLOR_BUSY = 0xFFEF6C00.toInt()
        private const val MASK_NORMAL: Byte = 0
        private const val MASK_BOX: Byte = 1
        private const val MASK_IGNORE: Byte = 2

        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("capture")
    private lateinit var captureHandler: Handler
    private val workThread = HandlerThread("work")
    private lateinit var work: Handler

    // ---- 캡처 ----
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private val imageLock = Any()
    private var latest: Image? = null
    private var sigBitmap: Bitmap? = null
    @Volatile private var frameDirty = false

    @Volatile private var screenW = 0
    @Volatile private var screenH = 0
    @Volatile private var capW = 0
    @Volatile private var capH = 0
    private var dpi = 0

    private lateinit var engine: Engine

    // ---- 화면에 띄우는 것들 ----
    @Volatile private var boxesView: BoxesView? = null
    private var button: TextView? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var clearBtn: TextView? = null
    private var clearLp: WindowManager.LayoutParams? = null
    @Volatile private var buttonRect: RectF? = null
    /** '지우기'를 누를 때마다 올라감. 진행 중이던 번역 결과가 다시 뜨지 않게 막는 용도 */
    @Volatile private var generation = 0
    private var menuView: View? = null
    private var regionView: View? = null

    // ---- 상태 ----
    @Volatile private var roiNorm: RectF? = null
    @Volatile private var busy = false
    @Volatile private var autoMode = false
    @Volatile private var dragging = false
    @Volatile private var menuOpen = false

    // ---- 화면 변화 감지 ----
    private class Sig(val v: IntArray, val gw: Int, val gh: Int, val cell: Int, val roi: Rect)
    @Volatile private var baseline: Sig? = null
    @Volatile private var baselineMask: ByteArray? = null
    @Volatile private var baselineReadyAt = 0L
    @Volatile private var lastRunAt = 0L

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                autoCheck()
            } catch (_: Exception) {
            }
            work.postDelayed(this, 400)
        }
    }

    // =====================================================================
    // 생명주기
    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
        workThread.start()
        work = Handler(workThread.looper)
        engine = Engine(Prefs.lang(this))
        roiNorm = Prefs.roi(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (projection != null) return START_NOT_STICKY
                startInForeground()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = resultData(intent)
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val p: MediaProjection? = if (data == null || mpm == null) null else try {
                    mpm.getMediaProjection(code, data)
                } catch (e: Exception) {
                    null
                }
                if (p == null) {
                    toast("화면 캡처를 시작하지 못했어요. 다시 시도해 주세요")
                    stopSelf()
                    return START_NOT_STICKY
                }
                projection = p
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post { stopSelf() }
                    }
                }, main)
                running = true
                setupCapture(p)
                setupOverlays()
                if (!engine.ready) toast("번역 모델 준비 중… (처음 한 번만, 약 30MB)")
                engine.prepare { ok ->
                    if (ok) toast("준비 완료! 번역 버튼을 눌러보세요")
                    else toast("번역 모델을 받지 못했어요. 인터넷 연결을 확인해 주세요")
                }
                work.postDelayed(tick, 500)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        work.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        dismissMenu()
        removeRegionView()
        for (v in listOf<View?>(boxesView, button, clearBtn)) {
            if (v != null) try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        boxesView = null
        button = null
        clearBtn = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        synchronized(imageLock) {
            try { latest?.close() } catch (_: Exception) {}
            latest = null
        }
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        engine.close()
        workThread.quitSafely()
        captureThread.quitSafely()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun resultData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "화면 번역", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("화면 번역기 실행 중")
            .setContentText("버튼 탭 = 번역, 길게 누르기 = 메뉴")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "종료", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // =====================================================================
    // 화면 캡처 (계속 켜 두고 가장 최근 화면만 보관)
    // =====================================================================

    @Suppress("DEPRECATION")
    private fun readScreen() {
        val dm = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) display.getRealMetrics(dm) else dm.setTo(resources.displayMetrics)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        dpi = dm.densityDpi
        val scale = if (screenW > 1080) 1080f / screenW else 1f
        capW = max(2, (screenW * scale).toInt() and 1.inv())
        capH = max(2, (screenH * scale).toInt() and 1.inv())
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, captureHandler)
        return r
    }

    private fun setupCapture(p: MediaProjection) {
        readScreen()
        val r = newReader()
        reader = r
        virtualDisplay = p.createVirtualDisplay(
            "screen-translator", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, captureHandler
        )
    }

    private fun onFrame(rd: ImageReader) {
        val img = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        if (rd !== reader) {
            img.close()
            return
        }
        synchronized(imageLock) {
            try { latest?.close() } catch (_: Exception) {}
            latest = img
        }
        frameDirty = true
    }

    /**
     * 가장 최근 화면을 Bitmap으로. forOcr=false면 변화 감지용으로 같은 Bitmap을 재사용한다
     * (이 경우 폭에 여백 픽셀이 붙어 있을 수 있음).
     */
    private fun grabFrame(forOcr: Boolean): Bitmap? {
        synchronized(imageLock) {
            val img = latest ?: return null
            return try {
                val plane = img.planes[0]
                val ps = plane.pixelStride
                val rs = plane.rowStride
                val w = img.width
                val h = img.height
                val bw = rs / ps
                val buf = plane.buffer
                buf.rewind()
                val need = rs * h
                val src: ByteBuffer = if (buf.remaining() >= need) buf else {
                    val tmp = ByteBuffer.allocate(need)
                    tmp.put(buf)
                    tmp.rewind()
                    tmp
                }
                val cached = if (forOcr) null else sigBitmap
                val bmp = if (cached != null && cached.width == bw && cached.height == h) cached else {
                    val nb = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
                    if (!forOcr) sigBitmap = nb
                    nb
                }
                bmp.copyPixelsFromBuffer(src)
                if (forOcr && bw != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = virtualDisplay ?: return
        val oldW = screenW
        val oldH = screenH
        readScreen()
        if (screenW == oldW && screenH == oldH) return
        // 화면이 회전하면 캡처 크기를 새로 맞춘다
        val nr = newReader()
        val old = reader
        reader = nr
        vd.resize(capW, capH, dpi)
        vd.surface = nr.surface
        synchronized(imageLock) {
            try { latest?.close() } catch (_: Exception) {}
            latest = null
        }
        captureHandler.post {
            try { old?.close() } catch (_: Exception) {}
        }
        boxesView?.clear()
        resetBaseline()
        val lp = buttonLp
        val b = button
        if (lp != null && b != null) {
            lp.x = lp.x.coerceIn(0, max(0, screenW - dp(54)))
            lp.y = lp.y.coerceIn(0, max(0, screenH - dp(54)))
            try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
            positionClearButton()
            updateButtonRect()
        }
    }

    // =====================================================================
    // 화면 위에 띄우는 것들
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun setupOverlays() {
        val bv = BoxesView(this)
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        blp.gravity = Gravity.TOP or Gravity.START
        // 안드로이드 12부터는 창 투명도가 0.8 이하여야 아래 앱이 터치를 받을 수 있다
        blp.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) {
            blp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(bv, blp)
        boxesView = bv
        addButton()
    }

    private fun addButton() {
        val size = dp(54)
        val tv = TextView(this)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.typeface = Typeface.DEFAULT_BOLD

        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Prefs.buttonX(this, screenW - size - dp(12)).coerceIn(0, max(0, screenW - size))
        lp.y = Prefs.buttonY(this, screenH / 3).coerceIn(0, max(0, screenH - size))

        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        tv.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            var longFired = false
            val longPress = Runnable {
                longFired = true
                showMenu()
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = lp.x
                        startY = lp.y
                        moved = false
                        longFired = false
                        main.postDelayed(longPress, 500)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && hypot(dx, dy) > slop) {
                            moved = true
                            dragging = true
                            main.removeCallbacks(longPress)
                        }
                        if (moved) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                            positionClearButton()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(longPress)
                        if (moved) {
                            dragging = false
                            Prefs.setButtonPos(this@TranslatorService, lp.x, lp.y)
                            updateButtonRect()
                            resetBaseline()
                        } else if (!longFired) {
                            onButtonTap()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(longPress)
                        if (moved) {
                            dragging = false
                            updateButtonRect()
                            resetBaseline()
                        }
                    }
                }
                return true
            }
        })

        wm.addView(tv, lp)
        button = tv
        buttonLp = lp
        updateButton()
        addClearButton()
        updateButtonRect()
    }

    /** 번역 버튼 바로 아래에 붙어 다니는 '지우기' 버튼 */
    private fun addClearButton() {
        val small = dp(40)
        val tv = TextView(this)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = 11f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.text = "지우기"
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xFF616161.toInt())
        bg.setStroke(dp(2), 0x66FFFFFF)
        tv.background = bg
        tv.alpha = 0.92f
        tv.setOnClickListener { clearTranslation() }

        val clp = WindowManager.LayoutParams(
            small, small, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        clp.gravity = Gravity.TOP or Gravity.START
        clearBtn = tv
        clearLp = clp
        placeClearButton()
        wm.addView(tv, clp)
    }

    /** 지우기 버튼 위치를 번역 버튼 아래(화면 끝이면 위)로 계산 */
    private fun placeClearButton() {
        val lp = buttonLp ?: return
        val clp = clearLp ?: return
        val big = dp(54)
        val small = dp(40)
        clp.x = lp.x + (big - small) / 2
        clp.y = lp.y + big + dp(8)
        if (clp.y + small > screenH) clp.y = lp.y - small - dp(8)
    }

    private fun positionClearButton() {
        val c = clearBtn ?: return
        val clp = clearLp ?: return
        placeClearButton()
        try { wm.updateViewLayout(c, clp) } catch (_: Exception) {}
    }

    private fun clearTranslation() {
        generation++
        boxesView?.clear()
        resetBaseline()
    }

    private fun viewRect(v: View): RectF {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat())
    }

    /** 두 버튼이 차지하는 화면 영역 (글자 인식·변화 감지에서 제외) */
    private fun updateButtonRect() {
        val b = button ?: return
        b.postDelayed({
            val r = viewRect(b)
            clearBtn?.let { r.union(viewRect(it)) }
            val m = dp(6).toFloat()
            r.inset(-m, -m)
            buttonRect = r
        }, 80)
    }

    private fun updateButton() {
        val b = button ?: return
        val color = when {
            busy -> COLOR_BUSY
            autoMode -> COLOR_AUTO
            else -> COLOR_IDLE
        }
        b.text = when {
            busy -> "…"
            autoMode -> "자동"
            else -> "번역"
        }
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        bg.setStroke(dp(2), 0x66FFFFFF)
        b.background = bg
        b.alpha = 0.92f
    }

    private fun setBusy(v: Boolean) {
        busy = v
        main.post { updateButton() }
    }

    private fun showMenu() {
        if (menuView != null) return
        menuOpen = true
        val pad = dp(8)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad, pad, pad)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(16).toFloat()
        bg.setColor(0xF2202124.toInt())
        box.background = bg

        fun item(label: String, action: () -> Unit) {
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 16f
            tv.setPadding(dp(20), dp(13), dp(20), dp(13))
            tv.setOnClickListener {
                dismissMenu()
                action()
            }
            box.addView(tv)
        }

        item(if (autoMode) "자동 번역 끄기" else "자동 번역 켜기 (화면이 바뀌면 알아서)") { setAuto(!autoMode) }
        item("번역 영역 지정") { main.postDelayed({ showRegionSelect() }, 200) }
        if (roiNorm != null) item("영역 해제 (전체 화면 번역)") {
            setRoi(null)
            toast("전체 화면을 번역해요")
        }
        item("번역 지우기") { clearTranslation() }
        item("번역기 종료") { stopSelf() }
        item("닫기") { }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.CENTER
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else {
                false
            }
        }
        try {
            wm.addView(box, lp)
            menuView = box
        } catch (e: Exception) {
            menuOpen = false
        }
    }

    private fun dismissMenu() {
        val v = menuView ?: return
        menuView = null
        try { wm.removeView(v) } catch (_: Exception) {}
        menuOpen = false
        resetBaseline()
    }

    private fun setAuto(on: Boolean) {
        autoMode = on
        updateButton()
        if (on) {
            main.postDelayed({ requestTranslate() }, 300)
        } else {
            toast("자동 번역을 껐어요")
        }
    }

    private fun setRoi(r: RectF?) {
        roiNorm = r
        Prefs.setRoi(this, r)
        resetBaseline()
    }

    private fun showRegionSelect() {
        if (regionView != null) return
        menuOpen = true
        boxesView?.clear()
        val v = RegionSelectView(this) { rect ->
            main.post {
                removeRegionView()
                if (rect != null && screenW > 0 && screenH > 0) {
                    setRoi(
                        RectF(
                            rect.left / screenW, rect.top / screenH,
                            rect.right / screenW, rect.bottom / screenH
                        )
                    )
                    main.postDelayed({ requestTranslate() }, 300)
                } else {
                    toast("영역 지정을 취소했어요")
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, lp)
            regionView = v
        } catch (e: Exception) {
            menuOpen = false
        }
    }

    private fun removeRegionView() {
        val v = regionView ?: return
        regionView = null
        try { wm.removeView(v) } catch (_: Exception) {}
        menuOpen = false
        resetBaseline()
    }

    // =====================================================================
    // 번역
    // =====================================================================

    /** 번역 버튼 탭: 언제나 지금 화면을 새로 번역 (지우기는 따로 있는 버튼으로) */
    private fun onButtonTap() {
        if (busy) return
        if (!engine.ready) toast("번역 모델 준비 중이에요. 잠시만요…")
        requestTranslate()
    }

    private fun requestTranslate() {
        if (busy) return
        setBusy(true)
        work.post { doTranslate() }
    }

    /** work 스레드에서 실행. 호출 전에 busy=true 로 해 둘 것 */
    private fun doTranslate() {
        lastRunAt = SystemClock.uptimeMillis()
        val gen = generation
        try {
            engine.ensureReady()
            // 번역 박스가 캡처에 찍히지 않게 잠깐 숨기고 새 화면을 기다린다
            val hid = onMain(false) { boxesView?.hideForCapture() ?: false }
            if (hid) Thread.sleep(160)

            val frame = grabFrame(forOcr = true)
            if (frame == null) {
                main.post {
                    boxesView?.showAgain()
                    toast("화면을 아직 못 가져왔어요. 잠시 후 다시 눌러주세요")
                }
                return
            }
            val roi = roiInCapture(frame.width, frame.height)
            val img = if (roi.width() == frame.width && roi.height() == frame.height) frame
            else Bitmap.createBitmap(frame, roi.left, roi.top, roi.width(), roi.height())

            val blocks = engine.recognize(img)
            val sx = screenW.toFloat() / capW
            val sy = screenH.toFloat() / capH
            val btn = buttonRect
            if (gen != generation) return   // 그 사이에 '지우기'를 눌렀으면 그만

            main.post { if (gen == generation) boxesView?.begin() }
            if (blocks.isEmpty() && !autoMode) toast("번역할 글자를 찾지 못했어요")

            // 캐시에 있는 문장은 바로 표시하고, 나머지는 번역되는 대로 하나씩 표시
            val pending = ArrayList<Pair<String, RectF>>()
            for (b in blocks) {
                val r = RectF(
                    (b.rect.left + roi.left) * sx, (b.rect.top + roi.top) * sy,
                    (b.rect.right + roi.left) * sx, (b.rect.bottom + roi.top) * sy
                )
                if (btn != null && btn.contains(r.centerX(), r.centerY())) continue
                val hit = engine.cached(b.text)
                if (hit != null) {
                    val box = BoxesView.Box(r, hit)
                    main.post { if (gen == generation) boxesView?.add(box) }
                } else {
                    pending.add(Pair(b.text, r))
                }
            }
            for ((text, r) in pending) {
                if (gen != generation) break
                val box = BoxesView.Box(r, engine.translate(text))
                main.post { if (gen == generation) boxesView?.add(box) }
            }
        } catch (e: Exception) {
            val msg = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            main.post {
                boxesView?.showAgain()
                toast("번역 오류: $msg")
            }
        } finally {
            baseline = null
            baselineMask = null
            baselineReadyAt = SystemClock.uptimeMillis() + 400
            frameDirty = false
            setBusy(false)
        }
    }

    // =====================================================================
    // 화면 변화 감지 (work 스레드)
    // =====================================================================

    private fun resetBaseline() {
        baseline = null
        baselineMask = null
        baselineReadyAt = SystemClock.uptimeMillis() + 400
    }

    private fun autoCheck() {
        if (busy || dragging || menuOpen) return
        val now = SystemClock.uptimeMillis()
        val bv = boxesView ?: return
        val base = baseline
        if (base == null) {
            // 번역 박스가 화면에 그려진 뒤의 모습을 기준으로 삼는다
            if (now < baselineReadyAt) return
            if (!autoMode) return
            val s = currentSig() ?: return
            baselineMask = makeMask(s)
            baseline = s
            frameDirty = false
            return
        }
        if (!autoMode || !frameDirty) return
        frameDirty = false
        val s = currentSig() ?: return
        val mask = baselineMask ?: return
        if (changed(base, s, mask)) {
            if (now - lastRunAt < 700) {
                frameDirty = true
                return
            }
            setBusy(true)
            doTranslate()
        }
    }

    private fun roiInCapture(w: Int, h: Int): Rect {
        val full = Rect(0, 0, w, h)
        val r = roiNorm ?: return full
        val rect = Rect((r.left * w).toInt(), (r.top * h).toInt(), (r.right * w).toInt(), (r.bottom * h).toInt())
        if (!rect.intersect(full)) return full
        return if (rect.width() < 16 || rect.height() < 16) full else rect
    }

    private fun currentSig(): Sig? {
        val bmp = grabFrame(forOcr = false) ?: return null
        val roi = roiInCapture(min(capW, bmp.width), min(capH, bmp.height))
        return signature(bmp, roi)
    }

    /** 화면을 작은 칸으로 나눠 칸마다 평균 밝기를 구한다 */
    private fun signature(bmp: Bitmap, roi: Rect): Sig {
        val gw = 96
        val cell = max(1, roi.width() / gw)
        val gh = max(1, roi.height() / cell)
        val sums = IntArray(gw * gh)
        val counts = IntArray(gw * gh)
        val rw = roi.width()
        val row = IntArray(rw)
        var y = roi.top
        while (y < roi.bottom) {
            val gy = (y - roi.top) / cell
            if (gy >= gh) break
            bmp.getPixels(row, 0, rw, roi.left, y, rw, 1)
            var x = 0
            while (x < rw) {
                val gx = x / cell
                if (gx < gw) {
                    val c = row[x]
                    val lum = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
                    val idx = gy * gw + gx
                    sums[idx] += lum
                    counts[idx]++
                }
                x += 2
            }
            y += 2
        }
        val v = IntArray(sums.size) { if (counts[it] == 0) 0 else sums[it] / counts[it] }
        return Sig(v, gw, gh, cell, Rect(roi))
    }

    /** 칸마다: 번역 박스 아래인지, 버튼 자리(무시)인지 표시 */
    private fun makeMask(s: Sig): ByteArray {
        val rects = boxesView?.boxRects() ?: emptyList()
        val btn = buttonRect
        val sx = screenW.toFloat() / capW
        val sy = screenH.toFloat() / capH
        val m = ByteArray(s.v.size)
        for (i in m.indices) {
            val gx = i % s.gw
            val gy = i / s.gw
            val cx = (s.roi.left + (gx + 0.5f) * s.cell) * sx
            val cy = (s.roi.top + (gy + 0.5f) * s.cell) * sy
            m[i] = when {
                btn != null && btn.contains(cx, cy) -> MASK_IGNORE
                rects.any { it.contains(cx, cy) } -> MASK_BOX
                else -> MASK_NORMAL
            }
        }
        return m
    }

    /**
     * 번역 박스는 반투명이라 그 아래 글자가 바뀌면 조금 비쳐 보인다.
     * 그래서 박스 아래 칸은 작은 변화도, 나머지 칸은 큰 변화만 센다.
     */
    private fun changed(a: Sig, b: Sig, mask: ByteArray): Boolean {
        if (a.v.size != b.v.size || a.gw != b.gw || mask.size != a.v.size) return true
        var outside = 0
        var inside = 0
        for (i in a.v.indices) {
            val d = abs(a.v[i] - b.v[i])
            when (mask[i]) {
                MASK_BOX -> if (d >= 4) inside++
                MASK_NORMAL -> if (d >= 14) outside++
                else -> {}
            }
        }
        return outside > max(6, a.v.size / 150) || inside >= 3
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    private fun <T> onMain(default: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result = default
        main.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun toast(msg: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { toast(msg) }
            return
        }
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
