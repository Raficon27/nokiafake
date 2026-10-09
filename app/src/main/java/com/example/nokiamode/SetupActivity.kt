package com.example.nokiamode

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
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

/** First launch and mode switching. Touch is deliberately available in setup. */
class SetupActivity : Activity() {
    private var choice = 0
    private var focus = 0
    private lateinit var setupView: SetupView
    private val permissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ModeStore.isConfigured(this) && !intent.getBooleanExtra("change_mode", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        choice = if (ModeStore.isConfigured(this) && ModeStore.get(this) == NokiaMode.FULL) 0 else 1
        setupView = SetupView()
        setContentView(setupView)
    }

    override fun onResume() {
        super.onResume()
        if (::setupView.isInitialized) setupView.invalidate()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> focus = (focus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> focus = (focus + 1).coerceAtMost(5)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SOFT_LEFT -> perform(focus)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_SOFT_RIGHT -> finish()
            else -> return super.dispatchKeyEvent(event)
        }
        setupView.invalidate()
        return true
    }

    private fun overlayGranted(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    private fun smsRoleGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        val manager = getSystemService(RoleManager::class.java) ?: return false
        return !manager.isRoleAvailable(RoleManager.ROLE_SMS) || manager.isRoleHeld(RoleManager.ROLE_SMS)
    }
    private fun fullPermissionsGranted(): Boolean = permissions.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun perform(row: Int) {
        when (row) {
            0, 1 -> choice = row
            2 -> {
                if (choice == 1) return
                if (!smsRoleGranted()) {
                    Toast.makeText(this, "ראשית בחר באפליקציה כברירת המחדל להודעות", Toast.LENGTH_LONG).show()
                    perform(4)
                    return
                }
                val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    .toMutableList()
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    missing.add(Manifest.permission.POST_NOTIFICATIONS)
                if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 11)
            }
            3 -> if (!overlayGranted()) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
            4 -> if (choice == 0 && !smsRoleGranted() && Build.VERSION.SDK_INT >= 29) {
                val manager = getSystemService(RoleManager::class.java)
                if (manager?.isRoleAvailable(RoleManager.ROLE_SMS) == true)
                    startActivity(manager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
            5 -> {
                if (!overlayGranted()) {
                    Toast.makeText(this, "כדי לחסום מגע בתוך האפליקציה, אשר תחילה הצגה מעל אפליקציות", Toast.LENGTH_LONG).show()
                    perform(3)
                    return
                }
                if (choice == 0 && (!smsRoleGranted() || !fullPermissionsGranted())) {
                    Toast.makeText(this, "למצב מלא יש לאשר הרשאות ותפקיד SMS. אפשר לבחור מצב דמה בלי הרשאות אלה.", Toast.LENGTH_LONG).show()
                    return
                }
                ModeStore.set(this, if (choice == 0) NokiaMode.FULL else NokiaMode.DEMO)
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
        setupView.invalidate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (::setupView.isInitialized) setupView.invalidate()
    }

    private inner class SetupView : View(this) {
        private val paint = Paint(3)
        private val orange = Color.rgb(250, 143, 57)
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val y = event.y * 640f / height
                val row = when {
                    y in 190f..244f -> 0
                    y in 250f..304f -> 1
                    y in 328f..385f -> 2
                    y in 390f..447f -> 3
                    y in 452f..509f -> 4
                    y in 524f..590f -> 5
                    else -> -1
                }
                if (row >= 0) { focus = row; perform(row) }
            }
            return true
        }
        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(width / 480f, height / 640f)
            canvas.drawColor(Color.rgb(17, 17, 28))
            paint.color = Color.rgb(55, 54, 76)
            canvas.drawRect(0f, 0f, 480f, 44f, paint)
            label(canvas, "הכנת מצב Nokia", 452f, 30f, 24f, Color.WHITE)
            label(canvas, "בחר אופן פעולה ואשר את ההרשאות הדרושות.", 450f, 80f, 19f, Color.WHITE)
            label(canvas, "במצב דמה אין חיוג או שליחת SMS אמיתיים.", 450f, 111f, 16f, 0xFFC7C6D0.toInt())
            label(canvas, "מחוות מערכת נשארות בשליטת Android.", 450f, 139f, 16f, 0xFFC7C6D0.toInt())
            label(canvas, "אפשר לצאת אחר כך באמצעות 1234 בחייגן", 450f, 168f, 16f, 0xFFC7C6D0.toInt())
            row(canvas, 0, "פעולה מלאה", if (choice == 0) "נבחר" else "בחר", 190f)
            row(canvas, 1, "מצב דמה", if (choice == 1) "נבחר" else "בחר", 250f)
            row(canvas, 2, "אנשי קשר · שיחות · SMS", if (fullPermissionsGranted()) "אושר" else "הרשאות", 328f)
            row(canvas, 3, "חסימת מגע", if (overlayGranted()) "אושר" else "אשר", 390f)
            row(canvas, 4, "אפליקציית SMS", if (smsRoleGranted()) "אושר" else "בחר", 452f)
            row(canvas, 5, "הפעל מצב Nokia", "OK", 524f)
            label(canvas, "חצים לבחירה · OK לאישור", 240f, 622f, 17f, Color.WHITE, Paint.Align.CENTER)
            canvas.restore()
        }
        private fun row(c: Canvas, i: Int, title: String, detail: String, y: Float) {
            paint.color = if (focus == i) orange else Color.rgb(37, 37, 53)
            c.drawRect(12f, y, 468f, y + 53f, paint)
            val color = if (focus == i) Color.BLACK else Color.WHITE
            label(c, title, 447f, y + 34f, 21f, color)
            label(c, detail, 28f, y + 34f, 17f, color, Paint.Align.LEFT)
        }
        private fun label(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int,
                          align: Paint.Align = Paint.Align.RIGHT) {
            paint.color = color; paint.textSize = size; paint.textAlign = align
            paint.typeface = android.graphics.Typeface.create("sans-serif-condensed", 0)
            c.drawText(text, x, y, paint)
        }
    }
}
