package com.example.nokiamode

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.TextureView
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView
import android.util.LruCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

private data class MediaEntry(val id: Long, val title: String, val uri: Uri)

/** Small-screen hardware-key UI for the independent device features. */
@Suppress("DEPRECATION")
class FeatureActivity : Activity(), TextureView.SurfaceTextureListener {
    private var feature = "gallery"
    private val items = mutableListOf<MediaEntry>()
    private var cursor = 0
    private var opened = false
    private var info = ""
    private lateinit var display: FeatureView
    private lateinit var root: FrameLayout
    private var texture: TextureView? = null
    private var camera: Camera? = null
    private var cameraIndex = 0
    private var cameraVideoMode = false
    private var videoRecorder: MediaRecorder? = null
    private var videoDescriptor: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var videoSurface: Surface? = null
    private var video: VideoView? = null
    private var audio: MediaPlayer? = null
    private var playingUri: Uri? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var torchId: String? = null
    private var torch = false
    private val pictureCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private var alarmDigits = ""
    private var selectedDay = Calendar.getInstance()
    private var stopwatch = false
    private var stopwatchElapsed = 0L
    private var stopwatchStarted = 0L
    private var counterType = 0
    private var timerMinutes = ""
    private var ninjaX = 240f
    private var ninjaY = 450f
    private var ninjaV = -10f
    private var ninjaScore = 0
    private var ninjaActive = false
    private var platformX = 150f
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (feature == "ninja" && ninjaActive) stepNinja()
            if (::display.isInitialized) display.invalidate()
            handler.postDelayed(this, if (feature == "ninja") 35 else 200)
        }
    }
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TouchShieldService.ACTION_EXIT) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feature = intent.getStringExtra("feature") ?: "gallery"
        ImmersiveUi.apply(this)
        root = FrameLayout(this)
        display = FeatureView(this)
        if (feature == "camera") {
            texture = TextureView(this).apply { surfaceTextureListener = this@FeatureActivity }
            root.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(display, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        when (feature) {
            "gallery", "videos", "music", "files" -> loadMedia()
            "recorder" -> loadRecordings()
            "flashlight" -> findTorch()
            "alarms" -> alarmDigits = getPreferences(MODE_PRIVATE).getString("alarm", "") ?: ""
        }
        val filter = IntentFilter(TouchShieldService.ACTION_EXIT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(exitReceiver, filter)
        handler.post(ticker)
    }
    override fun onResume() { super.onResume(); ImmersiveUi.apply(this)
        if (feature == "camera" && texture?.isAvailable == true && camera == null) openCamera(texture!!.surfaceTexture!!)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { ImmersiveUi.apply(this) }
    }
    override fun onPause() { stopVideo(); closeCamera(); if (torch) setTorch(false); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacks(ticker); closeCamera(); stopRecording(); audio?.release(); audio = null
        video?.stopPlayback(); pictureCache.evictAll(); unregisterReceiver(exitReceiver); super.onDestroy()
    }
    private fun permitted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun warn(s: String) { info = s; Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); display.invalidate() }

    private fun loadMedia() {
        items.clear()
        val uri = when (feature) {
            "gallery" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "music" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) when (feature) {
            "gallery" -> Manifest.permission.READ_MEDIA_IMAGES
            "videos" -> Manifest.permission.READ_MEDIA_VIDEO
            "music" -> Manifest.permission.READ_MEDIA_AUDIO
            else -> Manifest.permission.READ_MEDIA_IMAGES
        } else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!permitted(permission)) { info = "אין הרשאת קריאת קבצים. חזור למסך ההכנה."; return }
        try {
            contentResolver.query(uri, arrayOf("_id", "_display_name"), null, null,
                "date_modified DESC")?.use { c ->
                while (c.moveToNext() && items.size < 500) {
                    val id = c.getLong(0)
                    items.add(MediaEntry(id, c.getString(1) ?: "ללא שם", ContentUris.withAppendedId(uri, id)))
                }
            }
        } catch (e: Exception) { info = "אין גישה לקבצים במכשיר" }
        display.invalidate()
    }
    private fun loadRecordings() {
        items.clear()
        val folder = File(filesDir, "recordings")
        folder.mkdirs()
        folder.listFiles()?.sortedByDescending { it.lastModified() }?.forEachIndexed { i, file ->
            items.add(MediaEntry(i.toLong(), file.name, Uri.fromFile(file)))
        }
    }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP) return true
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val key = KeypadController.map(e)
        if (e.repeatCount > 0 && key.action !in listOf(KeyAction.UP, KeyAction.DOWN, KeyAction.LEFT, KeyAction.RIGHT)) return true
        when (key.action) {
            KeyAction.SOFT_RIGHT, KeyAction.END -> {
                if (opened) closeItem() else finish()
            }
            KeyAction.UP -> move(-1, true)
            KeyAction.DOWN -> move(1, true)
            KeyAction.LEFT -> move(-1, false)
            KeyAction.RIGHT -> move(1, false)
            KeyAction.CALL -> if (feature == "recorder" && items.isNotEmpty())
                playAudio(items[cursor].uri) else select()
            KeyAction.OK, KeyAction.SOFT_LEFT -> select()
            KeyAction.DIGIT -> digit(key.symbol)
            KeyAction.DELETE -> if (feature == "alarms") alarmDigits = alarmDigits.dropLast(1)
                else if (feature == "counters") timerMinutes = timerMinutes.dropLast(1)
            else -> return super.dispatchKeyEvent(e)
        }
        display.invalidate(); return true
    }
    private fun move(n: Int, vertical: Boolean) {
        when (feature) {
            "gallery", "videos" -> if (opened) cursor = (cursor + n).coerceIn(0, (items.size - 1).coerceAtLeast(0))
                .also { showItem() } else cursor = (cursor + n * if (feature == "gallery" && vertical) 3 else 1)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
            "music", "files", "recorder" -> cursor = (cursor + n).coerceIn(0, (items.size - 1).coerceAtLeast(0))
            "camera" -> if (vertical && n < 0) switchCamera()
            "calendar" -> selectedDay.add(if (vertical) Calendar.MONTH else Calendar.DAY_OF_MONTH, n)
            "counters" -> if (!vertical) counterType = (counterType + n + 2) % 2
            "ninja" -> if (!vertical) ninjaX = (ninjaX + n * 24).coerceIn(20f, 460f)
        }
    }
    private fun digit(s: String) {
        when (feature) {
            "alarms" -> {
                if (s == "#") cancelAlarm()
                else if (s.length == 1 && s[0].isDigit() && alarmDigits.length < 4) alarmDigits += s
            }
            "counters" -> {
                if (s == "#") { stopwatch = false; stopwatchElapsed = 0L; timerMinutes = "" }
                else if (counterType == 1 && s.length == 1 && s[0].isDigit() && timerMinutes.length < 3) timerMinutes += s
            }
            "ninja" -> if (s == "4") ninjaX = (ninjaX - 24).coerceAtLeast(20f)
                else if (s == "6") ninjaX = (ninjaX + 24).coerceAtMost(460f)
            "camera" -> if (s == "#" && videoRecorder == null) cameraVideoMode = !cameraVideoMode
        }
    }
    private fun select() {
        when (feature) {
            "gallery", "videos", "files" -> if (items.isNotEmpty()) {
                if (opened && feature == "videos") {
                    video?.let { if (it.isPlaying) it.pause() else it.start() }
                } else { opened = true; showItem() }
            }
            "music" -> if (items.isNotEmpty()) playAudio(items[cursor].uri)
            "camera" -> if (cameraVideoMode) {
                if (videoRecorder == null) startVideo() else stopVideo()
            } else takePhoto()
            "flashlight" -> setTorch(!torch)
            "recorder" -> if (recorder == null) startRecording() else stopRecording()
            "alarms" -> scheduleAlarm()
            "counters" -> {
                if (stopwatch) {
                    stopwatchElapsed += SystemClock.elapsedRealtime() - stopwatchStarted; stopwatch = false
                } else {
                    if (counterType == 1 && stopwatchElapsed == 0L)
                        stopwatchElapsed = (timerMinutes.toLongOrNull() ?: 0L) * 60_000L
                    stopwatchStarted = SystemClock.elapsedRealtime(); stopwatch = true
                }
            }
            "ninja" -> ninjaActive = !ninjaActive
        }
    }
    private fun closeItem() {
        opened = false
        video?.stopPlayback(); video?.let { root.removeView(it) }; video = null
        display.invalidate()
    }
    private fun showItem() {
        closeItem(); opened = true
        val entry = items.getOrNull(cursor) ?: return
        if (feature == "videos") {
            video = VideoView(this).also { v ->
                val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.7f).toInt())
                params.topMargin = (resources.displayMetrics.heightPixels * 0.15f).toInt()
                root.addView(v, 0, params); v.setVideoURI(entry.uri)
                v.setOnPreparedListener { it.isLooping = false; v.start() }
                v.setOnErrorListener { _, _, _ -> warn("לא ניתן לנגן סרטון זה"); true }
            }
        } else if (feature == "files") {
            when {
                entry.title.endsWith(".mp3", true) || entry.title.endsWith(".m4a", true) -> playAudio(entry.uri)
                entry.title.endsWith(".jpg", true) || entry.title.endsWith(".png", true) -> Unit
                else -> info = "הקובץ נבחר: " + entry.title.take(30)
            }
        }
    }
    private fun playAudio(uri: Uri) {
        try {
            if (playingUri == uri && audio != null) {
                audio?.let { if (it.isPlaying) it.pause() else it.start() }
                return
            }
            audio?.release()
            audio = MediaPlayer().apply { setDataSource(this@FeatureActivity, uri); prepare(); start() }
            playingUri = uri
            info = "מנגן: " + (items.getOrNull(cursor)?.title ?: "")
        } catch (e: Exception) { warn("לא ניתן לנגן את הקובץ") }
    }
    private fun findTorch() {
        try {
            val manager = getSystemService(CameraManager::class.java)
            torchId = manager.cameraIdList.firstOrNull {
                val chars = manager.getCameraCharacteristics(it)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (torchId == null) info = "אין פנס במכשיר זה"
        } catch (e: Exception) { info = "הפנס אינו זמין" }
    }
    private fun setTorch(on: Boolean) {
        try {
            val id = torchId ?: return
            getSystemService(CameraManager::class.java).setTorchMode(id, on)
            torch = on
        } catch (e: Exception) { warn("הפנס אינו זמין כעת") }
    }
    private fun startRecording() {
        if (!permitted(Manifest.permission.RECORD_AUDIO)) { warn("אין הרשאת מיקרופון"); return }
        try {
            val folder = File(filesDir, "recordings").apply { mkdirs() }
            recordingFile = File(folder, "הקלטה_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordingFile!!.absolutePath)
                prepare(); start()
            }
        } catch (e: Exception) { recorder?.release(); recorder = null; warn("לא ניתן להתחיל הקלטה") }
    }
    private fun stopRecording() {
        val current = recorder ?: return
        try { current.stop(); info = "ההקלטה נשמרה" }
        catch (e: Exception) { recordingFile?.delete(); info = "ההקלטה נכשלה" }
        finally { current.release(); recorder = null; if (feature == "recorder") loadRecordings() }
    }
    private fun scheduleAlarm() {
        if (alarmDigits.length != 4) { warn("הקלד שעה בארבע ספרות"); return }
        val hour = alarmDigits.substring(0, 2).toInt(); val minute = alarmDigits.substring(2).toInt()
        if (hour > 23 || minute > 59) { warn("שעה לא תקינה"); return }
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        val pending = PendingIntent.getBroadcast(this, 1, Intent(this, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pending)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pending)
            getPreferences(MODE_PRIVATE).edit().putString("alarm", alarmDigits).apply()
            warn("השעון כוון ל־" + alarmDigits.substring(0, 2) + ":" + alarmDigits.substring(2))
        } catch (e: Exception) { warn("לא ניתן לקבוע שעון מעורר") }
    }
    private fun cancelAlarm() {
        val pending = PendingIntent.getBroadcast(this, 1, Intent(this, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pending)
        alarmDigits = ""; getPreferences(MODE_PRIVATE).edit().remove("alarm").apply()
        info = "השעון בוטל"
    }
    private fun stepNinja() {
        ninjaV += 0.55f; ninjaY += ninjaV
        if (ninjaY >= 455f && ninjaV > 0) {
            if (ninjaX < platformX || ninjaX > platformX + 180f) {
                ninjaActive = false; info = "נפסלת! OK למשחק חדש"; ninjaY = 455f
                ninjaScore = 0; platformX = 150f
            } else {
                ninjaY = 455f; ninjaV = -12f; ninjaScore++
                platformX = ((platformX.toInt() * 7 + ninjaScore * 61) % 270).toFloat() + 15f
            }
        }
        if (ninjaY < 95f) { ninjaY = 95f; ninjaV = 3f; ninjaScore++ }
    }
    private fun closeCamera() { try { camera?.stopPreview(); camera?.release() } catch (_: Exception) {} ; camera = null }
    private fun openCamera(surface: SurfaceTexture) {
        if (!permitted(Manifest.permission.CAMERA)) { info = "אין הרשאת מצלמה"; display.invalidate(); return }
        try {
            closeCamera()
            camera = Camera.open(cameraIndex).apply {
                setDisplayOrientation(90)
                val p = parameters
                p.setRotation(90)
                parameters = p
                setPreviewTexture(surface)
                startPreview()
            }
        } catch (e: Exception) { closeCamera(); warn("המצלמה אינה זמינה") }
    }
    private fun switchCamera() {
        if (Camera.getNumberOfCameras() < 2) return
        cameraIndex = (cameraIndex + 1) % Camera.getNumberOfCameras()
        texture?.surfaceTexture?.let { openCamera(it) }
    }
    private fun takePhoto() {
        val cam = camera ?: run { warn("המצלמה אינה זמינה"); return }
        try {
            cam.takePicture(null, null, Camera.PictureCallback { bytes, _ ->
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME,
                            "Nokia_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NokiaMode")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException()
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException()
                    warn("התמונה נשמרה בגלריה")
                } catch (e: Exception) { warn("שמירת התמונה נכשלה") }
                try { cam.startPreview() } catch (_: Exception) {}
            })
        } catch (e: Exception) { warn("הצילום נכשל") }
    }
    private fun startVideo() {
        val cam = camera ?: run { warn("המצלמה אינה זמינה"); return }
        if (!permitted(Manifest.permission.CAMERA) || !permitted(Manifest.permission.RECORD_AUDIO)) {
            warn("דרושות הרשאות מצלמה ומיקרופון"); return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME,
                    "Nokia_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NokiaMode")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException()
            videoDescriptor = contentResolver.openFileDescriptor(videoUri!!, "w")
                ?: throw IllegalStateException()
            cam.unlock()
            videoSurface = Surface(texture?.surfaceTexture ?: throw IllegalStateException())
            videoRecorder = MediaRecorder().apply {
                setCamera(cam)
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOrientationHint(90)
                setOutputFile(videoDescriptor!!.fileDescriptor)
                setPreviewDisplay(videoSurface)
                prepare(); start()
            }
            info = "● מקליט וידאו — OK לסיום"
        } catch (e: Exception) {
            videoRecorder?.release(); videoRecorder = null
            videoDescriptor?.close(); videoDescriptor = null
            videoSurface?.release(); videoSurface = null
            videoUri?.let { contentResolver.delete(it, null, null) }; videoUri = null
            closeCamera(); texture?.surfaceTexture?.let { openCamera(it) }
            warn("הקלטת וידאו אינה זמינה במצלמה זו")
        }
    }
    private fun stopVideo() {
        val current = videoRecorder ?: return
        var saved = false
        try { current.stop(); saved = true }
        catch (_: Exception) {} finally {
            current.release(); videoRecorder = null
            videoDescriptor?.close(); videoDescriptor = null
            videoSurface?.release(); videoSurface = null
            videoUri?.let { uri ->
                if (saved && Build.VERSION.SDK_INT >= 29) contentResolver.update(uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                if (!saved) contentResolver.delete(uri, null, null)
            }
            videoUri = null
            closeCamera(); texture?.surfaceTexture?.let { openCamera(it) }
        }
        if (saved) warn("הסרטון נשמר") else warn("הקלטת הווידאו נכשלה")
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera(surface) }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private inner class FeatureView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orange = 0xFFF1883E.toInt()
        private val white = Color.WHITE
        private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = white,
                         align: Paint.Align = Paint.Align.RIGHT) {
            p.color = color; p.style = Paint.Style.FILL; p.textAlign = align; p.textSize = size
            p.typeface = android.graphics.Typeface.create("sans-serif-condensed", 0)
            c.drawText(s, x, y, p)
        }
        private fun row(c: Canvas, i: Int, label: String, active: Boolean) {
            val y = 86f + i * 57f
            if (active) { p.color = orange; c.drawRect(0f, y, 480f, y + 54f, p) }
            text(c, label.take(35), 449f, y + 35f, 21f)
        }
        private fun picture(uri: Uri, x: Float, y: Float, w: Float, h: Float) {
            try {
                val key = uri.toString() + ":" + w.toInt() + ":" + h.toInt()
                val bmp = pictureCache.get(key) ?: (if (Build.VERSION.SDK_INT >= 29)
                    contentResolver.loadThumbnail(uri, android.util.Size(w.toInt().coerceAtLeast(1),
                        h.toInt().coerceAtLeast(1)), null)
                else contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) })
                    ?.also { pictureCache.put(key, it) }
                if (bmp != null) {
                    val ratio = minOf(w / bmp.width, h / bmp.height)
                    val dstW = bmp.width * ratio; val dstH = bmp.height * ratio
                    canvasForPicture?.drawBitmap(bmp, null,
                        android.graphics.RectF(x + (w - dstW) / 2, y + (h - dstH) / 2,
                            x + (w + dstW) / 2, y + (h + dstH) / 2), p)
                }
            } catch (_: Exception) {}
        }
        private var canvasForPicture: Canvas? = null
        override fun onDraw(c: Canvas) {
            c.save(); c.scale(width / 480f, height / 640f)
            val cameraLike = feature == "camera" || (feature == "videos" && opened)
            if (!cameraLike) c.drawColor(0xFF181722.toInt())
            else { p.color = 0xFF191724.toInt(); c.drawRect(0f, 0f, 480f, 78f, p); c.drawRect(0f, 535f, 480f, 640f, p) }
            p.color = 0xFF383447.toInt(); c.drawRect(0f, 0f, 480f, 34f, p)
            text(c, SimpleDateFormat("HH:mm", Locale.US).format(Date()), 457f, 24f, 17f)
            val name = when (feature) {
                "gallery" -> "גלריה"; "videos" -> "סרטונים"; "music" -> "מוזיקה"; "camera" -> "מצלמה"
                "files" -> "הקבצים שלי"; "recorder" -> "רשמקול"; "flashlight" -> "פנס"
                "alarms" -> "שעון מעורר"; "calendar" -> "לוח שנה"; "counters" -> "מונים"
                else -> "נינג׳ה אפ"
            }
            p.color = 0xFF302D3F.toInt(); c.drawRect(0f, 34f, 480f, 78f, p)
            text(c, name, 449f, 65f, 23f)
            when (feature) {
                "gallery" -> {
                    canvasForPicture = c
                    if (opened) items.getOrNull(cursor)?.let { picture(it.uri, 15f, 90f, 450f, 455f) }
                    else if (items.isEmpty()) text(c, info.ifBlank { "אין תמונות" }, 449f, 260f, 21f)
                    else {
                        val first = (cursor / 12) * 12
                        items.drop(first).take(12).forEachIndexed { i, entry ->
                            val x = 12f + (i % 3) * 155f; val y = 92f + (i / 3) * 115f
                            p.color = if (first + i == cursor) orange else 0xFF393645.toInt()
                            c.drawRect(x - 3f, y - 3f, x + 143f, y + 105f, p)
                            picture(entry.uri, x, y, 137f, 99f)
                        }
                    }
                    canvasForPicture = null
                }
                "videos", "music", "files", "recorder" -> {
                    if (feature == "recorder") {
                        text(c, if (recorder == null) "OK להתחיל הקלטה" else "● מקליט... OK לעצירה",
                            449f, 113f, 20f, orange)
                    }
                    if (items.isEmpty()) text(c, info.ifBlank { "אין קבצים" }, 449f, 275f, 20f)
                    else if (!opened || feature != "videos") {
                        val first = (cursor - 6).coerceAtLeast(0)
                        items.drop(first).take(8).forEachIndexed { i, entry ->
                            row(c, i, entry.title, first + i == cursor)
                        }
                    }
                }
                "camera" -> {
                    p.color = orange; c.drawCircle(240f, 573f, 26f, p)
                    p.color = Color.WHITE; c.drawCircle(240f, 573f, 18f, p)
                    text(c, (if (cameraVideoMode) "וידאו" else "תמונה") +
                        "   # החלפה · OK צילום · ↑ מצלמה", 452f, 517f, 18f)
                }
                "flashlight" -> {
                    p.color = if (torch) 0xFFFFD671.toInt() else 0xFF555164.toInt()
                    c.drawCircle(240f, 286f, 103f, p)
                    text(c, if (torch) "פועל" else "כבוי", 240f, 300f, 38f, white, Paint.Align.CENTER)
                    text(c, "OK להפעלה או כיבוי", 240f, 485f, 21f, white, Paint.Align.CENTER)
                }
                "alarms" -> {
                    val hour = alarmDigits.padEnd(4, '_')
                    text(c, hour.substring(0, 2) + ":" + hour.substring(2), 240f, 274f, 64f,
                        orange, Paint.Align.CENTER)
                    text(c, "הקלד HHMM ואשר ב־OK", 240f, 360f, 20f, white, Paint.Align.CENTER)
                    text(c, "# לביטול השעון", 240f, 405f, 18f, white, Paint.Align.CENTER)
                }
                "calendar" -> {
                    text(c, SimpleDateFormat("EEEE  dd.MM.yyyy", Locale("he", "IL")).format(selectedDay.time),
                        240f, 156f, 27f, orange, Paint.Align.CENTER)
                    text(c, "ימין / שמאל: יום   למעלה / למטה: חודש", 240f, 206f, 18f, white, Paint.Align.CENTER)
                    val start = selectedDay.clone() as Calendar
                    start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0)
                    start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
                    if (permitted(Manifest.permission.READ_CALENDAR)) try {
                        val selection = CalendarContract.Events.DTSTART + ">=? AND " +
                            CalendarContract.Events.DTSTART + "<?"
                        contentResolver.query(CalendarContract.Events.CONTENT_URI,
                            arrayOf(CalendarContract.Events.TITLE),
                            selection, arrayOf(start.timeInMillis.toString(),
                                (start.timeInMillis + 86_400_000L).toString()),
                            CalendarContract.Events.DTSTART + " ASC")?.use { events ->
                            var i = 0
                            while (events.moveToNext() && i < 5) {
                                text(c, events.getString(0) ?: "אירוע", 449f, 270f + i * 55f, 21f)
                                i++
                            }
                            if (i == 0) text(c, "אין אירועים ביום זה", 240f, 306f, 20f, white, Paint.Align.CENTER)
                        }
                    } catch (_: Exception) { text(c, "לא ניתן לקרוא אירועים", 240f, 306f, 20f) }
                }
                "counters" -> {
                    text(c, if (counterType == 0) "שעון עצר" else "טיימר", 240f, 166f, 25f,
                        orange, Paint.Align.CENTER)
                    val elapsed = if (stopwatch) SystemClock.elapsedRealtime() - stopwatchStarted else 0L
                    val ms = if (counterType == 0) stopwatchElapsed + elapsed else
                        max(0L, stopwatchElapsed - elapsed)
                    text(c, "%02d:%02d.%01d".format(Locale.US, ms / 60000,
                        ms / 1000 % 60, ms / 100 % 10), 240f, 306f, 53f, white, Paint.Align.CENTER)
                    text(c, if (stopwatch) "OK עצור" else "OK הפעל", 240f, 407f, 24f,
                        orange, Paint.Align.CENTER)
                    text(c, "ימין/שמאל מצב · # איפוס · דקות: " + timerMinutes, 240f, 484f, 17f,
                        white, Paint.Align.CENTER)
                }
                "ninja" -> {
                    p.color = orange; c.drawRect(platformX, 490f, platformX + 180f, 505f, p)
                    c.drawCircle(ninjaX, ninjaY, 18f, p)
                    text(c, "ניקוד " + ninjaScore, 449f, 111f, 23f)
                    text(c, "חצים או 4/6 · OK התחלה/השהיה", 240f, 540f, 17f,
                        white, Paint.Align.CENTER)
                }
            }
            if (info.isNotEmpty() && feature !in listOf("gallery", "videos", "music", "files"))
                text(c, info.take(48), 240f, 531f, 16f, orange, Paint.Align.CENTER)
            p.color = 0xFF282537.toInt(); c.drawRect(0f, 583f, 480f, 640f, p)
            text(c, if (feature == "camera") "אפשרויות" else "בחר", 35f, 620f, 18f,
                white, Paint.Align.LEFT)
            text(c, "אישור", 240f, 620f, 18f, white, Paint.Align.CENTER)
            text(c, "חזרה", 447f, 620f, 18f)
            c.restore()
        }
        private val fallbackExit = CornerExitDetector()
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
                fallbackExit.onDown(event.x, event.y, width.toFloat(), height.toFloat(),
                    android.os.SystemClock.elapsedRealtime())) {
                sendBroadcast(Intent(TouchShieldService.ACTION_EXIT).setPackage(packageName))
                finish()
            }
            return true
        }
    }
}
