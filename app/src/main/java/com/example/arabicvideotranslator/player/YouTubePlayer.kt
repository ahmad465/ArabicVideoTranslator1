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
import com.example.arabicvideotranslator.core.YouTubeUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * حالة المشغّل كما يبلّغ عنها YouTube IFrame Player API الرسمي (موضع التشغيل، الحالة، رمز الخطأ).
 * لا نصل إلى صوت الفيديو إطلاقاً؛ نقرأ الموضع الزمني فقط لمزامنة نطق ترجمة يوفّرها المستخدم.
 */
class PlayerBridge {
    @Volatile var positionMs: Long = 0
    @Volatile var state: Int = -1      // 1 = playing, 2 = paused, 0 = ended, 3 = buffering, 5 = cued
    @Volatile var errorCode: Int = 0   // 0 = لا خطأ؛ 9998/9999 أخطاء تحميل من التطبيق؛ والباقي من YouTube
    @Volatile var ready: Boolean = false

    val isPlaying: Boolean get() = state == 1

    @JavascriptInterface fun onTime(seconds: Double) {
        if (seconds.isFinite() && seconds >= 0) positionMs = (seconds * 1000).toLong()
    }
    @JavascriptInterface fun onState(s: Int) { state = s }
    @JavascriptInterface fun onError(code: Int) { errorCode = code }
    @JavascriptInterface fun onReady() { ready = true; if (errorCode == 9999) errorCode = 0 }
}

fun playerErrorMessage(code: Int): String? = when (code) {
    0 -> null
    2 -> "معرّف الفيديو أو معاملاته غير صالحة."
    5 -> "تعذّر تشغيل الفيديو في مشغل HTML5."
    100 -> "الفيديو غير موجود أو خاص."
    101, 150 -> "صاحب الفيديو لا يسمح بتشغيله داخل التطبيقات."
    153 -> "YouTube رفض تعريف التطبيق (خطأ 153)."
    9998 -> "تعذّر تحميل سكربت مشغل YouTube. تحقق من الإنترنت."
    9999 -> "انتهت مهلة تحميل المشغل (لم يصبح جاهزاً)."
    else -> "خطأ في المشغل (رمز $code)."
}

internal fun buildPlayerHtml(videoId: String, appId: String): String {
    require(YouTubeUrl.isValidId(videoId)) { "معرّف فيديو غير صالح" } // يمنع حقن JS
    require(Regex("^[A-Za-z0-9._]+$").matches(appId)) { "appId غير صالح" }
    return """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}#player{width:100%;height:100%}</style></head>
<body><div id="player"></div>
<script>
var player; var isReady = false;
setTimeout(function(){ if(!isReady){ NativeBridge.onError(9999); } }, 15000);
</script>
<script src="https://www.youtube.com/iframe_api" onerror="NativeBridge.onError(9998)"></script>
<script>
function onYouTubeIframeAPIReady(){
  player = new YT.Player('player', {
    width:'100%', height:'100%', videoId:'$videoId',
    playerVars:{playsinline:1, rel:0, origin:'https://$appId'},
    events:{
      onReady:function(){ isReady = true; NativeBridge.onReady(); },
      onStateChange:function(e){ NativeBridge.onState(e.data); },
      onError:function(e){ NativeBridge.onError(e.data); }
    }
  });
  setInterval(function(){
    if(player && player.getCurrentTime){ NativeBridge.onTime(player.getCurrentTime()); }
  }, 200);
}
</script></body></html>
""".trimIndent()
}

/** مشغل YouTube الرسمي (IFrame API) داخل WebView، مع عرض حالة/خطأ المشغل وزر لفتح الفيديو في YouTube. */
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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        addJavascriptInterface(bridge, "NativeBridge")
                        bridge.positionMs = 0; bridge.state = -1; bridge.errorCode = 0; bridge.ready = false
                        loadDataWithBaseURL("https://$appId", buildPlayerHtml(videoId, appId), "text/html", "utf-8", null)
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

        LaunchedEffect(videoId) {
            while (isActive) {
                val err = playerErrorMessage(bridge.errorCode)
                info = when {
                    err != null -> "$err (رمز ${bridge.errorCode})"
                    bridge.ready -> "المشغل جاهز. اضغط تشغيل داخل الفيديو."
                    else -> "جارٍ تحميل المشغل…"
                }
                delay(500)
            }
        }
        DisposableEffect(Unit) {
            onDispose { holder.forEach { it.stopLoading(); it.removeJavascriptInterface("NativeBridge"); it.destroy() }; holder.clear() }
        }
    }
}
