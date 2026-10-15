package com.sawoo.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** 시작 화면: 언어 고르기, 권한 받기, 번역 시작/중지 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIF = 1002
    }

    private val langCodes = arrayOf("en", "ja", "zh")
    private val langNames = arrayOf("영어 → 한국어", "일본어 → 한국어", "중국어 → 한국어")
    private lateinit var status: TextView
    private var askedNotif = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), dp(24), dp(24), dp(24))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.WHITE)
        scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            applyInsets(v, insets)
            insets
        }

        val title = TextView(this)
        title.text = "화면 번역기"
        title.textSize = 28f
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.setTextColor(Color.BLACK)
        root.addView(title)

        root.addView(label("번역할 언어"))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langNames)
        spinner.setSelection(langCodes.indexOf(Prefs.lang(this)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val code = langCodes[position]
                if (code != Prefs.lang(this@MainActivity)) {
                    Prefs.setLang(this@MainActivity, code)
                    if (TranslatorService.running) {
                        toast("언어를 바꿨어요. 번역을 중지했다가 다시 시작하면 적용돼요")
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(spinner)

        val start = Button(this)
        start.text = "번역 시작"
        start.setOnClickListener { onStartClicked() }
        root.addView(start)

        val stop = Button(this)
        stop.text = "번역 중지"
        stop.setOnClickListener {
            stopService(Intent(this, TranslatorService::class.java))
            toast("번역을 중지했어요")
            stop.postDelayed({ updateStatus() }, 400)
        }
        root.addView(stop)

        status = TextView(this)
        status.textSize = 14f
        status.setTextColor(0xFF555555.toInt())
        status.setPadding(0, dp(12), 0, dp(12))
        root.addView(status)

        root.addView(label("사용 방법"))
        root.addView(
            body(
                "1. '번역 시작'을 누르고 '다른 앱 위에 표시'와 화면 캡처를 허용해요.\n\n" +
                    "2. 화면에 동그란 '번역' 버튼이 생겨요. 번역할 앱으로 가서 버튼을 누르면 글자 위에 한국어 번역이 떠요.\n\n" +
                    "3. 버튼을 다시 누르면: 화면이 그대로면 번역이 지워지고, 화면이 바뀌었으면 새로 번역해요.\n\n" +
                    "4. 버튼을 길게 누르면 메뉴가 나와요.\n" +
                    "   • 자동 번역: 화면이 바뀌면 알아서 다시 번역 (버튼이 초록색)\n" +
                    "   • 번역 영역 지정: 대화창·자막처럼 한 부분만 번역하면 훨씬 빨라요\n" +
                    "   • 번역기 종료\n\n" +
                    "5. 버튼은 끌어서 옮길 수 있어요."
            )
        )
        root.addView(label("참고"))
        root.addView(
            body(
                "• 처음 한 번은 번역 모델(약 30MB)을 받느라 인터넷이 필요해요. 그 뒤로는 오프라인에서도 돼요.\n" +
                    "• 넷플릭스·은행 앱처럼 화면 캡처를 막는 앱은 번역되지 않아요.\n" +
                    "• 폰을 다시 켜거나 번역기를 종료하면 화면 캡처 허용을 다시 해야 해요."
            )
        )

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(v: View, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
        } else {
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
        }
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 17f
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        t.setTextColor(Color.BLACK)
        t.setPadding(0, dp(20), 0, dp(6))
        return t
    }

    private fun body(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 15f
        t.setTextColor(0xFF333333.toInt())
        t.setLineSpacing(0f, 1.15f)
        return t
    }

    private fun updateStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "허용됨 ✓" else "아직 허용 안 됨"
        val run = if (TranslatorService.running) "실행 중" else "꺼짐"
        status.text = "다른 앱 위에 표시: $overlay\n번역기: $run"
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            toast("'다른 앱 위에 표시'를 허용한 뒤 돌아와서 다시 눌러주세요")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        if (TranslatorService.running) stopService(Intent(this, TranslatorService::class.java))
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            toast("이 기기에서는 화면 캡처를 쓸 수 없어요")
            return
        }
        val i = if (Build.VERSION.SDK_INT >= 34) {
            // 앱 하나가 아니라 화면 전체를 캡처하도록
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(i, REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) onStartClicked()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val result = data
        if (resultCode != RESULT_OK || result == null) {
            toast("화면 캡처를 허용해야 번역할 수 있어요")
            return
        }
        val svc = Intent(this, TranslatorService::class.java)
        svc.action = TranslatorService.ACTION_START
        svc.putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode)
        svc.putExtra(TranslatorService.EXTRA_DATA, result)
        startForegroundService(svc)
        toast("번역 버튼이 생겼어요. 번역할 앱으로 가서 눌러보세요")
        moveTaskToBack(true)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
