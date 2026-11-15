package com.example.arabicvideotranslator

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.arabicvideotranslator.core.Cue
import com.example.arabicvideotranslator.core.DubbingScheduler
import com.example.arabicvideotranslator.core.FatalProviderException
import com.example.arabicvideotranslator.translate.OnDeviceTranslationProvider
import com.example.arabicvideotranslator.core.SpeechFit
import com.example.arabicvideotranslator.core.SubtitleParser
import com.example.arabicvideotranslator.core.TranslationPipeline
import com.example.arabicvideotranslator.core.YouTubeUrl
import com.example.arabicvideotranslator.player.PlayerBridge
import com.example.arabicvideotranslator.player.YouTubePlayer
import com.example.arabicvideotranslator.player.playerErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TranslatorScreen() } }
    }
}

@Composable
private fun TranslatorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { PlayerBridge() }

    var url by remember { mutableStateOf("") }
    var videoId by remember { mutableStateOf("") }
    var sourceCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var translated by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var subtitleText by remember { mutableStateOf("") }
    var alreadyArabic by remember { mutableStateOf(false) }
    var localVideoUri by remember { mutableStateOf<Uri?>(null) }
    var localPlayer by remember { mutableStateOf<VideoView?>(null) }
    var sourceLang by remember { mutableStateOf("en") }
    var translating by remember { mutableStateOf(false) }
    var dubbing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("الصق رابط يوتيوب، ثم استورد ملف ترجمة SRT أو VTT.") }
    var speakerStatus by remember { mutableStateOf(ArabicSpeaker.Status.INITIALIZING) }

    val speaker = remember { ArabicSpeaker(context) { speakerStatus = it.status } }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }

    val dubCues: List<Cue> = if (alreadyArabic) sourceCues else translated

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            localVideoUri = uri; videoId = ""; dubbing = false; speaker.stop()
            bridge.positionMs = 0; bridge.state = 2; bridge.errorCode = 0
            status = "تم اختيار الفيديو المحلي. استورد ملف SRT/VTT مطابقاً للفيديو لتفعيل الترجمة والدبلجة."
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("تعذر قراءة الملف")
        }.onSuccess { raw ->
            val cues = SubtitleParser.parse(raw)
            dubbing = false; speaker.stop()
            sourceCues = cues; translated = emptyList()
            subtitleText = cues.joinToString("\n") { it.text }
            status = if (cues.isEmpty()) "لم أجد أي مقاطع صالحة في الملف (تأكد أنه SRT أو VTT)."
            else "تم تحميل ${cues.size} مقطعاً. اضغط «ترجم إلى العربية» أو فعّل «الملف عربي أصلاً»."
        }.onFailure { status = "تعذر قراءة ملف الترجمة: ${it.message}" }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("مترجم الفيديو العربي", style = MaterialTheme.typography.headlineSmall)
            Text(
                "يشغّل روابط YouTube أو فيديو محفوظاً على الهاتف، ويترجم ملفات SRT/VTT مجاناً على الجهاز ثم ينطق العربية بصوت النظام.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("رابط فيديو YouTube") },
                placeholder = { Text("https://www.youtube.com/watch?v=...") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Button(onClick = {
                val id = YouTubeUrl.extractVideoId(url)
                if (id == null) status = "الرابط غير صالح أو لا يحتوي على معرّف فيديو معروف."
                else { dubbing = false; speaker.stop(); localVideoUri = null; videoId = id; status = "تم تحميل مشغل يوتيوب." }
            }, modifier = Modifier.fillMaxWidth()) { Text("فتح الفيديو") }

            if (videoId.isNotBlank()) {
                YouTubePlayer(videoId, bridge, context.packageName)
            }
            OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("اختيار فيديو محفوظ على الهاتف")
            }
            if (localVideoUri != null) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val controls = MediaController(ctx)
                            controls.setAnchorView(this)
                            setMediaController(controls)
                            setVideoURI(localVideoUri)
                            tag = localVideoUri.toString()
                            setOnPreparedListener { mp -> mp.isLooping = false; start() }
                            setOnCompletionListener { bridge.state = 0; speaker.stop() }
                            localPlayer = this
                        }
                    },
                    update = { view -> if (view.tag != localVideoUri.toString()) { view.tag = localVideoUri.toString(); view.setVideoURI(localVideoUri) } }
                )
                LaunchedEffect(localVideoUri) {
                    while (isActive && localVideoUri != null) {
                        val player = localPlayer
                        if (player != null) {
                            bridge.positionMs = player.currentPosition.toLong()
                            bridge.state = if (player.isPlaying) 1 else 2
                        }
                        delay(200)
                    }
                }
            }

            OutlinedButton(
                onClick = { subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("استيراد ملف ترجمة SRT / VTT") }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = alreadyArabic, onCheckedChange = { alreadyArabic = it; dubbing = false; speaker.stop() })
                Text("الملف عربي أصلاً (بدون ترجمة)")
            }

            if (!alreadyArabic) {
                Text("الترجمة مجانية على الجهاز ولا تحتاج مفتاح API. يلزم الإنترنت لتنزيل نموذج اللغة أول مرة؛ بعد تنزيله تتم الترجمة محلياً.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = sourceLang, onValueChange = { sourceLang = it.trim() },
                    label = { Text("لغة الملف الأصلية (مثل en أو fr أو es؛ الفارغ = en)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Button(
                    enabled = !translating && sourceCues.isNotEmpty(),
                    onClick = {
                        translating = true; dubbing = false; speaker.stop()
                        status = "جارٍ الترجمة…"
                        scope.launch {
                            val cues = sourceCues; val lang = sourceLang.ifBlank { null }
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    TranslationPipeline(OnDeviceTranslationProvider(context, lang ?: "en")).translate(cues, lang, "ar")
                                }
                            }
                            translating = false
                            outcome.onSuccess { r ->
                                translated = r.translated.filterNotNull()
                                subtitleText = translated.joinToString("\n") { it.text }
                                status = if (r.isComplete) "اكتملت ترجمة ${r.successCount} مقطعاً."
                                else "ترجمة جزئية: نجح ${r.successCount} من ${cues.size}. المقاطع الفاشلة ستُتخطى (${r.failures.values.firstOrNull()})."
                            }.onFailure {
                                status = if (it is FatalProviderException) "فشلت الترجمة: ${it.message}"
                                else "خطأ غير متوقع: ${it.message}"
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text(if (translating) "جارٍ الترجمة…" else "ترجم إلى العربية") }
            }

            OutlinedTextField(
                value = subtitleText, onValueChange = { subtitleText = it },
                label = { Text("النص") },
                placeholder = { Text("ألصق نصاً عربياً هنا للقراءة الحرة، أو استورد ملفاً") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                textStyle = MaterialTheme.typography.bodyLarge
            )

            Button(
                enabled = (videoId.isNotBlank() || localVideoUri != null) && dubCues.isNotEmpty(),
                onClick = {
                    if (dubbing) { dubbing = false; speaker.stop(); status = "أُوقفت الدبلجة." }
                    else when (speakerStatus) {
                        ArabicSpeaker.Status.READY -> { dubbing = true; status = "الدبلجة تعمل: شغّل الفيديو وسيُنطق كل مقطع في وقته." }
                        ArabicSpeaker.Status.NO_ARABIC_VOICE -> status = "لا توجد حزمة صوت عربي. ثبّتها من إعدادات تحويل النص إلى كلام."
                        ArabicSpeaker.Status.NO_ENGINE -> status = "محرك الصوت غير متاح على الجهاز."
                        ArabicSpeaker.Status.INITIALIZING -> status = "محرك الصوت قيد التهيئة، حاول بعد لحظة."
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text(if (dubbing) "إيقاف الدبلجة المتزامنة" else "ابدأ الدبلجة المتزامنة") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    when {
                        subtitleText.isBlank() -> status = "أدخل نصاً أولاً."
                        !speaker.isReady -> status = "محرك الصوت العربي غير جاهز."
                        else -> { speaker.speak(subtitleText); status = "قراءة حرة للنص (غير متزامنة مع الفيديو)." }
                    }
                }, modifier = Modifier.weight(1f)) { Text("اقرأ النص كاملاً") }
                OutlinedButton(onClick = { speaker.stop(); status = "تم إيقاف الصوت." }, modifier = Modifier.weight(1f)) {
                    Text("إيقاف الصوت")
                }
            }

            // حلقة الدبلجة: تستعلم عن موضع المشغل وتنطق المقطع المستحق فقط أثناء التشغيل الفعلي.
            LaunchedEffect(dubbing, dubCues, videoId) {
                if (!dubbing) return@LaunchedEffect
                val scheduler = DubbingScheduler(dubCues)
                var wasPlaying = false
                while (isActive) {
                    delay(200)
                    val err = playerErrorMessage(bridge.errorCode)
                    if (err != null) { status = err; dubbing = false; speaker.stop(); break }
                    if (!bridge.isPlaying) {
                        if (wasPlaying) speaker.stop()
                        wasPlaying = false
                        continue
                    }
                    wasPlaying = true
                    val cue = scheduler.poll(bridge.positionMs) ?: continue
                    val fit = SpeechFit.fit(cue.text, cue.durationMs)
                    speaker.speak(cue.text, fit.rate)
                }
            }

            Card(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(12.dp), textAlign = TextAlign.Start) }
            Text(
                "ملاحظات صريحة: لا يستخرج التطبيق الكلام من صوت الفيديو تلقائياً؛ يجب توفير ملف SRT/VTT. الترجمة على الجهاز بلا مفتاح مدفوع، لكنها تحتاج تنزيل نموذج اللغة أول مرة وخدمات Google Play المتوافقة. الصوت العربي يستخدم محرك TTS المثبت على الهاتف وقد يحتاج تنزيل بيانات صوت عربية. قد يمنع YouTube تضمين بعض الفيديوهات.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
