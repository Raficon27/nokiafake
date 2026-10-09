package com.example.nokiamode

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

/** Configuration remains touchable so system permission dialogs can be operated. */
class SetupActivity : Activity() {
    private data class Grant(val title: String, val detail: String,
                             val permissions: Array<String> = emptyArray(), val kind: String = "runtime")
    private var mode = NokiaMode.DEMO
    private val modeTabs = listOf(NokiaMode.SAFE, NokiaMode.DEMO, NokiaMode.FULL)
    private var focus = 0
    private var scroll = 0
    private lateinit var view: SetupView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ModeStore.isConfigured(this) && !intent.getBooleanExtra("change_mode", false)) {
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        mode = ModeStore.get(this)
        view = SetupView()
        setContentView(view)
    }

    private fun grants(): List<Grant> {
        val common = mutableListOf(
            Grant("חסימת מגע", "פתח נגישות, בחר מצב Nokia ואשר", kind = "accessibility"),
            Grant("גלריה וסרטונים", "קריאת תמונות וסרטונים", mediaPermissions()),
            Grant("מוזיקה", "קריאת קובצי שמע", audioPermission()),
            Grant("לוח שנה", "קריאת אירועים", arrayOf(Manifest.permission.READ_CALENDAR)),
            Grant("אנשי קשר", "שמות ומספרים", arrayOf(Manifest.permission.READ_CONTACTS)),
            Grant("יומן שיחות", "קריאת שיחות", arrayOf(Manifest.permission.READ_CALL_LOG))
        )
        if (mode != NokiaMode.SAFE) common.addAll(listOf(
            Grant("מצלמה ופנס", "צילום ושימוש בפנס", arrayOf(Manifest.permission.CAMERA)),
            Grant("רשמקול", "גישה למיקרופון", arrayOf(Manifest.permission.RECORD_AUDIO))
        ))
        if (mode != NokiaMode.SAFE && Build.VERSION.SDK_INT >= 33)
            common.add(Grant("התראות", "שעון מעורר והודעות", arrayOf(Manifest.permission.POST_NOTIFICATIONS)))
        if (mode == NokiaMode.SAFE && smsRoleHeld())
            common.add(Grant("שחרור תפקיד SMS", "בחר אפליקציית SMS אחרת לפני מצב בטוח", kind = "smsRelease"))
        if (mode == NokiaMode.FULL) common.addAll(listOf(
            Grant("אפליקציית הודעות", "קבלת SMS אמיתי", kind = "sms"),
            Grant("קריאת הודעות", "הודעות נכנסות ושרשורים", arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)),
            Grant("חיוג", "שיחות יוצאות", arrayOf(Manifest.permission.CALL_PHONE)),
            Grant("שליחת הודעות", "SMS יוצא אמיתי", arrayOf(Manifest.permission.SEND_SMS))
        ))
        return common
    }
    private fun mediaPermissions() = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun smsRoleHeld(): Boolean = SmsRoleGuard.isHeld(this)
    private fun granted(g: Grant): Boolean = when (g.kind) {
        "accessibility" -> TouchShieldService.isReady(this)
        "smsRelease" -> !smsRoleHeld()
        "sms" -> if (Build.VERSION.SDK_INT < 29) true else {
            val rm = getSystemService(RoleManager::class.java)
            rm == null || !rm.isRoleAvailable(RoleManager.ROLE_SMS) || rm.isRoleHeld(RoleManager.ROLE_SMS)
        }
        else -> g.permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    private fun activate(g: Grant) {
        when (g.kind) {
            "accessibility" -> if (!granted(g)) {
                Toast.makeText(this, "במסך נגישות בחר מצב Nokia, הפעל את השירות וחזור", Toast.LENGTH_LONG).show()
                try { startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 33) }
                catch (_: Exception) { openAppSettings() }
            }
            "smsRelease" -> if (!granted(g)) try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), 34)
            } catch (_: Exception) { openAppSettings() }
            "sms" -> if (!granted(g) && Build.VERSION.SDK_INT >= 29) {
                getSystemService(RoleManager::class.java)?.let { rm ->
                    if (rm.isRoleAvailable(RoleManager.ROLE_SMS))
                        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 32)
                }
            }
            else -> {
                if (g.permissions.contains(Manifest.permission.READ_SMS)) {
                    val sms = grants().first { it.kind == "sms" }
                    if (!granted(sms)) { activate(sms); return }
                }
                val missing = g.permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) {
                    val prefs = getPreferences(MODE_PRIVATE)
                    if (missing.any { prefs.getBoolean("blocked_" + it, false) }) openAppSettings()
                    else requestPermissions(missing.toTypedArray(), 31)
                }
            }
        }
    }
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")))
    }
    private fun launch() {
        val shield = grants().first { it.kind == "accessibility" }
        if (!granted(shield)) {
            activate(shield); return
        }
        if (mode == NokiaMode.SAFE && smsRoleHeld()) {
            activate(grants().first { it.kind == "smsRelease" }); return
        }
        if (grants().any { !granted(it) }) Toast.makeText(this,
            "הפונקציות שלא אושרו יופיעו כלא זמינות", Toast.LENGTH_LONG).show()
        ModeStore.set(this, mode)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
    private fun requestNext() {
        val missing = grants().firstOrNull { !granted(it) } ?: return
        scroll = grants().indexOf(missing).coerceIn(0, (grants().size - 4).coerceAtLeast(0))
        activate(missing); view.invalidate()
    }
    private fun choose() {
        when {
            focus == 0 -> changeMode(1)
            focus <= grants().size -> activate(grants()[focus - 1])
            else -> launch()
        }
        view.invalidate()
    }
    private fun changeMode(delta: Int) {
        mode = modeTabs[(modeTabs.indexOf(mode) + delta + modeTabs.size) % modeTabs.size]
        scroll = 0
    }
    private fun ensureVisible() {
        if (focus in 1..grants().size) {
            if (focus - 1 < scroll) scroll = focus - 1
            if (focus - 1 > scroll + 3) scroll = focus - 4
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (KeypadController.map(event).action) {
            KeyAction.UP -> focus = (focus - 1).coerceAtLeast(0)
            KeyAction.DOWN -> focus = (focus + 1).coerceAtMost(grants().size + 1)
            KeyAction.LEFT, KeyAction.RIGHT -> if (focus == 0)
                changeMode(if (KeypadController.map(event).action == KeyAction.RIGHT) 1 else -1)
            KeyAction.OK -> choose()
            KeyAction.SOFT_LEFT, KeyAction.CALL -> requestNext()
            KeyAction.SOFT_RIGHT, KeyAction.END -> finish()
            else -> return super.dispatchKeyEvent(event)
        }
        ensureVisible(); view.invalidate(); return true
    }
    override fun onResume() {
        super.onResume()
        if (::view.isInitialized) {
            view.invalidate()
            view.postDelayed({ if (::view.isInitialized) view.invalidate() }, 500)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 31) {
            val edit = getPreferences(MODE_PRIVATE).edit()
            permissions.forEachIndexed { i, permission ->
                edit.putBoolean("blocked_" + permission,
                    results.getOrNull(i) != PackageManager.PERMISSION_GRANTED &&
                        !shouldShowRequestPermissionRationale(permission))
            }
            edit.apply()
        }
        if (::view.isInitialized) view.invalidate()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::view.isInitialized) view.invalidate()
    }

    private inner class SetupView : View(this) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var touchStart = 0f
        private val orange = 0xFFFF9851.toInt()
        private val white = 0xFFF5F3F7.toInt()
        private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
                         align: Paint.Align = Paint.Align.RIGHT) {
            p.color = color; p.shader = null; p.style = Paint.Style.FILL
            p.textSize = size; p.textAlign = align
            p.typeface = android.graphics.Typeface.create("sans-serif-condensed", 0)
            c.drawText(s, x, y, p)
        }
        override fun onDraw(c: Canvas) {
            c.save(); c.scale(width / 480f, height / 640f)
            c.drawColor(0xFF171522.toInt())
            p.color = 0xFF292536.toInt(); c.drawRect(0f, 0f, 480f, 64f, p)
            text(c, "הכנת מצב Nokia", 454f, 43f, 28f, white)
            text(c, "בחר מצב, אשר הרשאות והפעל", 453f, 101f, 21f, white)
            text(c, "חצים ו־OK או לחיצה · החלקה לגלילת הרשאות", 453f, 133f, 16f,
                0xFFC6BFCC.toInt())
            listOf("מצב בטוח", "מצב דמה", "פעולה מלאה").forEachIndexed { i, s ->
                val x = 14f + i * 153f
                p.color = if (modeTabs[i] == mode) orange else 0xFF383345.toInt()
                c.drawRoundRect(x, 157f, x + 146f, 220f, 13f, 13f, p)
                text(c, s, x + 73f, 197f, 18f,
                    if (modeTabs[i] == mode) Color.BLACK else white, Paint.Align.CENTER)
            }
            if (focus == 0) { p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = white
                c.drawRoundRect(12f, 155f, 468f, 222f, 16f, 16f, p); p.style = Paint.Style.FILL }
            val all = grants(); val done = all.count { granted(it) }
            text(c, "הרשאות  $done/${all.size}", 453f, 264f, 21f, orange)
            for (slot in 0..3) {
                val idx = scroll + slot
                if (idx >= all.size) break
                val g = all[idx]; val y = 280f + slot * 55f
                p.color = if (focus == idx + 1) 0xFF674037.toInt() else 0xFF302C3D.toInt()
                c.drawRoundRect(14f, y, 466f, y + 50f, 9f, 9f, p)
                text(c, g.title, 446f, y + 23f, 20f, white)
                text(c, g.detail, 446f, y + 43f, 14f, 0xFFC6BFCC.toInt())
                text(c, if (granted(g)) "✓" else "+", 38f, y + 34f, 25f,
                    if (granted(g)) 0xFF84DDB6.toInt() else orange, Paint.Align.CENTER)
            }
            if (all.size > 4) text(c, "${scroll + 1}–${(scroll + 4).coerceAtMost(all.size)} / ${all.size}",
                446f, 509f, 14f, 0xFFAFA8BA.toInt())
            text(c, if (done < all.size) "אשר הרשאה הבאה  ◀" else "כל ההרשאות אושרו",
                240f, 522f, 16f, 0xFFE4C8A7.toInt(), Paint.Align.CENTER)
            p.color = if (focus == all.size + 1) 0xFFFFB46F.toInt() else orange
            c.drawCircle(240f, 570f, 39f, p)
            text(c, "הפעל", 240f, 579f, 24f, Color.BLACK, Paint.Align.CENTER)
            val seconds = ModeStore.duration(this@SetupActivity) / 1000
            text(c, "זמן שימוש מצטבר: ${seconds / 3600}ש׳ ${(seconds / 60) % 60}ד׳", 240f, 631f,
                16f, 0xFFCFC8D5.toInt(), Paint.Align.CENTER)
            c.restore()
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) { touchStart = e.y * 640f / height; return true }
            if (e.action != MotionEvent.ACTION_UP) return true
            val x = e.x * 480f / width; val y = e.y * 640f / height
            if (touchStart in 270f..515f && kotlin.math.abs(y - touchStart) > 25f) {
                scroll = (scroll + if (y < touchStart) 2 else -2)
                    .coerceIn(0, (grants().size - 4).coerceAtLeast(0))
                invalidate(); return true
            }
            when {
                y in 153f..225f -> {
                    mode = modeTabs[((x - 14f) / 153f).toInt().coerceIn(0, 2)]
                    scroll = 0; focus = 0
                }
                y in 280f..500f -> {
                    val idx = scroll + ((y - 280f) / 55f).toInt()
                    if (idx in grants().indices) { focus = idx + 1; activate(grants()[idx]) }
                }
                y in 500f..530f -> requestNext()
                y in 531f..612f && x in 196f..284f -> { focus = grants().size + 1; launch() }
            }
            invalidate(); return true
        }
    }
}
