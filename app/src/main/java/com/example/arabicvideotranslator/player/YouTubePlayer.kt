package com.example.arabicvideotranslator.player

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.arabicvideotranslator.core.YouTubeUrl

/**
 * حالة المشغّل كما يبلّغ عنها YouTube IFrame Player API الرسمي (موضع التشغيل، الحالة، رمز الخطأ).
 * لا نصل إلى صوت الفيديو إطلاقاً؛ نقرأ الموضع الزمني فقط لمزامنة نطق ترجمة يملكها/يوفّرها المستخدم.
 */
class PlayerBridge {
    @Volatile var positionMs: Long = 0
    @Volatile var state: Int = -1      // 1 = playing, 2 = paused, 0 = ended, 3 = buffering, 5 = cued
    @Volatile var errorCode: Int = 0   // 0 = لا خطأ؛ 2,5,100,101,150,153 كما في وثائق YouTube

    val isPlaying: Boolean get() = state == 1

    @JavascriptInterface fun onTime(seconds: Double) {
        if (seconds.isFinite() && seconds >= 0) positionMs = (seconds * 1000).toLong()
    }
    @JavascriptInterface fun onState(s: Int) { state = s }
    @JavascriptInterface fun onError(code: Int) { errorCode = code }
}

fun playerErrorMessage(code: Int): String? = when (code) {
    0 -> null
    2 -> "معرّف الفيديو أو معاملاته غير صالحة."
    5 -> "تعذّر تشغيل الفيديو في مشغل HTML5."
    100 -> "الفيديو غير موجود أو خاص."
    101, 150 -> "صاحب الفيديو لا يسمح بتشغيله داخل التطبيقات."
    153 -> "تعذّر التحقق من هوية التطبيق (Referer). تحقق من الإنترنت وجرّب مجدداً."
    else -> "خطأ في المشغل (رمز $code)."
}

internal fun buildPlayerHtml(videoId: String): String {
    require(YouTubeUrl.isValidId(videoId)) { "معرّف فيديو غير صالح" } // يمنع حقن JS
    return """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}#player{width:100%;height:100%}</style></head>
<body><div id="player"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady(){
  player = new YT.Player('player', {
    width:'100%', height:'100%', videoId:'$videoId',
    playerVars:{playsinline:1, rel:0},
    events:{
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

/** مشغل YouTube الرسمي (IFrame API) داخل WebView، مع Referer/baseUrl يعرّف التطبيق كما تشترط YouTube. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(videoId: String, bridge: PlayerBridge, appId: String) {
    key(videoId) {
        val holder = remember { mutableListOf<WebView>() }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(230.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    addJavascriptInterface(bridge, "NativeBridge")
                    bridge.positionMs = 0; bridge.state = -1; bridge.errorCode = 0
                    loadDataWithBaseURL("https://$appId", buildPlayerHtml(videoId), "text/html", "utf-8", null)
                    holder.add(this)
                }
            }
        )
        DisposableEffect(Unit) {
            onDispose { holder.forEach { it.stopLoading(); it.removeJavascriptInterface("NativeBridge"); it.destroy() }; holder.clear() }
        }
    }
}
