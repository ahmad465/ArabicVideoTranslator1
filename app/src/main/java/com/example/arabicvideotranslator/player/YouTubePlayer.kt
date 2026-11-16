package com.example.arabicvideotranslator.player

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * حالة المشغّل (موضع التشغيل والحالة). نقرأ الزمن فقط من صفحة التضمين الرسمية داخل WebView
 * لمزامنة نطق ترجمة يوفّرها المستخدم؛ لا نصل إلى صوت الفيديو أو بثه إطلاقاً.
 */
class PlayerBridge {
    @Volatile var positionMs: Long = 0
    @Volatile var state: Int = -1      // 1 = playing, 2 = paused, 0 = ended
    @Volatile var errorCode: Int = 0
    @Volatile var ready: Boolean = false

    val isPlaying: Boolean get() = state == 1

    @JavascriptInterface fun onTime(seconds: Double) {
        if (seconds.isFinite() && seconds >= 0) positionMs = (seconds * 1000).toLong()
    }
    @JavascriptInterface fun onState(s: Int) { state = s }
    @JavascriptInterface fun onError(code: Int) { errorCode = code }
    @JavascriptInterface fun onReady() { ready = true }
}

fun playerErrorMessage(code: Int): String? = when (code) {
    0 -> null
    2 -> "معرّف الفيديو أو معاملاته غير صالحة."
    5 -> "تعذّر تشغيل الفيديو في مشغل HTML5."
    100 -> "الفيديو غير موجود أو خاص."
    101, 150 -> "صاحب الفيديو لا يسمح بتشغيله داخل التطبيقات."
    153 -> "YouTube رفض تعريف التطبيق (خطأ 153)."
    else -> "خطأ في المشغل (رمز $code)."
}

/** يقرأ زمن التشغيل وحالته من عنصر video في صفحة التضمين الرسمية. الناتج مثل "12.5|1|0" أو "none". */
private const val READ_STATE_JS =
    "(function(){var v=document.querySelector('video');if(!v)return 'none';" +
        "return v.currentTime+'|'+(v.paused?0:1)+'|'+(v.ended?1:0);})()"

/** يحلّل ناتج READ_STATE_JS (يأتي من evaluateJavascript محاطاً بعلامتي اقتباس) ويحدّث الجسر. */
internal fun applyPlayerReading(raw: String?, bridge: PlayerBridge) {
    val s = raw?.trim()?.trim('"') ?: return
    val parts = s.split('|')
    if (parts.size != 3) { bridge.ready = false; return }
    val seconds = parts[0].toDoubleOrNull()
    if (seconds == null || !seconds.isFinite() || seconds < 0) return
    bridge.ready = true
    bridge.positionMs = (seconds * 1000).toLong()
    bridge.state = when {
        parts[2] == "1" -> 0
        parts[1] == "1" -> 1
        else -> 2
    }
}

/** مشغل YouTube الرسمي (صفحة التضمين) داخل WebView مع قراءة موضع التشغيل لمزامنة الدبلجة. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(videoId: String, bridge: PlayerBridge, appId: String) {
    val context = LocalContext.current
    key(videoId) {
        val holder = remember { mutableListOf<WebView>() }
        var info by remember { mutableStateOf("جارٍ تحميل المشغل…") }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(230.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        bridge.positionMs = 0; bridge.state = -1; bridge.errorCode = 0; bridge.ready = false
                        loadUrl(
                            "https://www.youtube.com/embed/$videoId?playsinline=1&rel=0",
                            mapOf("Referer" to "https://$appId")
                        )
                        holder.add(this)
                    }
                }
            )
            Text(info, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("افتح الفيديو في YouTube") }
        }

        // استعلام دوري عن موضع التشغيل (على الخيط الرئيسي كما يشترط evaluateJavascript)
        LaunchedEffect(videoId) {
            while (isActive) {
                holder.firstOrNull()?.evaluateJavascript(READ_STATE_JS) { raw -> applyPlayerReading(raw, bridge) }
                val err = playerErrorMessage(bridge.errorCode)
                info = when {
                    err != null -> "$err (رمز ${bridge.errorCode})"
                    bridge.ready -> {
                        val st = when (bridge.state) { 1 -> "يعمل"; 0 -> "انتهى"; else -> "متوقف" }
                        "المشغل جاهز. الحالة: $st، الموضع: ${bridge.positionMs / 1000} ث"
                    }
                    else -> "جارٍ تحميل المشغل… اضغط تشغيل داخل الفيديو."
                }
                delay(250)
            }
        }
        DisposableEffect(Unit) {
            onDispose { holder.forEach { it.stopLoading(); it.destroy() }; holder.clear() }
        }
    }
}
