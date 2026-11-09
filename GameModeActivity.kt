package com.example.gamemode // เปลี่ยนให้ตรงกับ package ของโปรเจกต์

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File

/**
 * หน้า "โหมดเกม" สไตล์ iQOO: ประหยัดแบต / สมดุล / MONSTER
 * ไม่ใช้ overlay (ไม่มี SYSTEM_ALERT_WINDOW) เป็นหน้าปกติในแอป
 * ไม่ต้อง dependency เพิ่ม ใช้ View ล้วน
 */
class GameModeActivity : Activity() {

    private enum class Mode(val label: String) { SAVE("ประหยัดแบต"), BALANCE("สมดุล"), MONSTER("MONSTER") }

    private val yellow = Color.parseColor("#FFC107")
    private val panel = Color.parseColor("#1E1E22")
    private val chip = Color.parseColor("#2C2C31")
    private val ui = Handler(Looper.getMainLooper())

    private var mode = Mode.BALANCE
    private lateinit var tvTemp: TextView
    private lateinit var tvBatt: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvStatus: TextView
    private val modeButtons = mutableMapOf<Mode, TextView>()
    private lateinit var etPkg: EditText

    private val tick = object : Runnable {
        override fun run() { refreshStats(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        val prefs = getSharedPreferences("gm", MODE_PRIVATE)
        mode = Mode.values()[prefs.getInt("mode", 1)]

        val root = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(col)

        // ---- แถวสถานะ (ค่าจริงจากเครื่อง) ----
        val stats = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvCpu = stat(row, "CPU"); tvTemp = stat(row, "อุณหภูมิ")
        tvBatt = stat(row, "แบตเตอรี่"); tvRam = stat(row, "RAM ว่าง")
        stats.addView(row)
        col.addView(stats)

        // ---- ปุ่มโหมด ----
        val modes = card()
        modes.addView(title("โหมดประสิทธิภาพ"))
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Mode.values().forEach { m ->
            val t = TextView(this).apply {
                text = m.label; gravity = Gravity.CENTER; textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { select(m) }
            }
            mrow.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            modeButtons[m] = t
        }
        modes.addView(mrow)
        tvStatus = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; setPadding(0, dp(10), 0, 0) }
        modes.addView(tvStatus)
        col.addView(modes)

        // ---- เปิดเกม ----
        val game = card()
        game.addView(title("เกม"))
        etPkg = EditText(this).apply {
            setText(prefs.getString("pkg", "com.roblox.client"))
            setTextColor(Color.WHITE); textSize = 14f
            hint = "package ของเกม"; setHintTextColor(Color.GRAY)
        }
        game.addView(etPkg)
        val go = TextView(this).apply {
            text = "ใช้โหมด + เปิดเกม"; gravity = Gravity.CENTER
            setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; textSize = 16f
            setPadding(0, dp(14), 0, dp(14)); background = round(yellow, 28)
            setOnClickListener {
                prefs.edit().putString("pkg", etPkg.text.toString().trim()).apply()
                applyMode(); launchGame()
            }
        }
        game.addView(go, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        col.addView(game)

        setContentView(root)
        paintModes()
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    // ---------- เลือก/ใช้โหมด ----------
    private fun select(m: Mode) {
        mode = m
        getSharedPreferences("gm", MODE_PRIVATE).edit().putInt("mode", m.ordinal).apply()
        paintModes()
        applyMode()
    }

    private fun applyMode() {
        val done = mutableListOf<String>()

        // 1) ปิดแอปพื้นหลัง (สมดุล/MONSTER) -> คืน RAM
        if (mode != Mode.SAVE) {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            var n = 0
            packageManager.getInstalledApplications(0).forEach {
                if (it.packageName != packageName && it.packageName != etPkg.text.toString().trim()
                    && (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                    am.killBackgroundProcesses(it.packageName); n++
                }
            }
            done += "เคลียร์แอปพื้นหลัง"
        }

        // 2) ห้ามรบกวน (ต้องให้สิทธิ์ครั้งแรก)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(
                if (mode == Mode.SAVE) NotificationManager.INTERRUPTION_FILTER_ALL
                else NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
            if (mode != Mode.SAVE) done += "ห้ามรบกวน"
        } else if (mode != Mode.SAVE) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        // 3) รีเฟรชเรตสูงสุดที่จอรองรับ (ใช้กับหน้าต่างของแอปนี้)
        if (Build.VERSION.SDK_INT >= 23) {
            val modes = windowManager.defaultDisplay.supportedModes
            val best = modes.maxByOrNull { it.refreshRate }
            val lp = window.attributes
            lp.preferredDisplayModeId = if (mode == Mode.MONSTER && best != null) best.modeId else 0
            window.attributes = lp
        }

        // 4) Sustained performance: ล็อกความแรงให้นิ่ง ลดอาการเฟรมตกจากความร้อน (ถ้าเครื่องรองรับ)
        if (Build.VERSION.SDK_INT >= 24) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (pm.isSustainedPerformanceModeSupported) {
                window.setSustainedPerformanceMode(mode == Mode.MONSTER)
                if (mode == Mode.MONSTER) done += "Sustained performance"
            }
        }

        // 5) ไม่ให้จอดับ
        val keepOn = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (mode == Mode.SAVE) window.clearFlags(keepOn) else window.addFlags(keepOn)

        tvStatus.text = if (done.isEmpty()) "ใช้โหมด ${mode.label} แล้ว" else "ทำแล้ว: " + done.joinToString(" • ")
    }

    private fun launchGame() {
        val pkg = etPkg.text.toString().trim()
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
        else Toast.makeText(this, "ไม่พบเกม $pkg", Toast.LENGTH_SHORT).show()
    }

    // ---------- อ่านค่าจริง ----------
    private fun refreshStats() {
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        tvTemp.text = "%.1f°C".format(temp)
        tvBatt.text = "$lvl%"
        tvRam.text = "${mi.availMem / 1048576} MB"
        tvCpu.text = cpuMaxMhz()?.let { "$it MHz" } ?: "—"
    }

    /** ความถี่ปัจจุบันสูงสุดของแกนที่อ่านได้ (บางเครื่อง/Android ใหม่ บล็อกการอ่าน -> แสดง —) */
    private fun cpuMaxMhz(): Int? {
        var max = 0
        for (c in 0 until Runtime.getRuntime().availableProcessors()) {
            try {
                val khz = File("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq").readText().trim().toInt()
                if (khz > max) max = khz
            } catch (_: Exception) {}
        }
        return if (max > 0) max / 1000 else null
    }

    // ---------- UI helper ----------
    private fun paintModes() = modeButtons.forEach { (m, t) ->
        val on = m == mode
        t.background = round(if (on) yellow else chip, 14)
        t.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(panel, 18)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }
    private fun title(s: String) = TextView(this).apply {
        text = s; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(10))
    }
    private fun stat(parent: LinearLayout, label: String): TextView {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val v = TextView(this).apply { setTextColor(yellow); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; text = "—" }
        val l = TextView(this).apply { setTextColor(Color.GRAY); textSize = 11f; gravity = Gravity.CENTER; text = label }
        box.addView(v); box.addView(l)
        parent.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        return v
    }
    private fun round(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
