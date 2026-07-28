package com.orbisgpt.terminal.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.orbisgpt.terminal.core.RuntimeState
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            overlayView?.text = render()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        showOverlay()
        handler.post(updater)
        RuntimeState.setOverlayRunning(true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updater)
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        RuntimeState.setOverlayRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun render(): String {
        val terminal = RuntimeState.terminal.value
        val decision = terminal.decision
        val settings = RuntimeState.settings.value
        if (collapsed) {
            val status = if (decision.operationAllowed) "LIBERADA" else decision.stage.name
            return "ORBIS · $status · toque para abrir"
        }
        val probability = decision.probability
        return buildString {
            append("ORBIS DECISION TERMINAL\n")
            append("${settings.marketMode.name} · ${settings.captureTimeframe.label}\n")
            append(if (decision.context.screenValidated) "Tela validada" else "Procurando gráfico válido")
            append(" · ${terminal.trackedCandles} candles\n")
            append("Regime: ${decision.regime.name}\n")
            append("Playbook: ${decision.playbook.name}\n")
            append("Etapa: ${decision.stage.name}\n")
            append("Direção: ${decision.direction.name}\n")
            append("Confluência: ${decision.confluenceScore}/100\n")
            append("Entrada: ${decision.entryQuality.score}/100\n")
            append("Prob.: ${pct(probability.posteriorProbability)} · BE: ${pct(probability.breakEven)}\n")
            append("EV: ${signed(probability.expectedValue)}\n")
            append(if (decision.operationAllowed) "OPERAÇÃO PERMITIDA" else "BLOQUEADA: ${decision.blockers.firstOrNull() ?: "aguardando"}")
            append("\nPróximo: ${decision.nextCondition}")
        }
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = TextView(this).apply {
            text = "ORBIS DECISION TERMINAL\nInicializando..."
            textSize = 11.5f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xE6111720.toInt())
            setPadding(18, 14, 18, 14)
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }
        enableDragging(overlayView!!)
        windowManager.addView(overlayView, params)
    }

    private fun enableDragging(view: TextView) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layout.x
                    initialY = layout.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) + abs(dy) > 12f) moved = true
                    layout.x = initialX + dx.toInt()
                    layout.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(view, layout)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        collapsed = !collapsed
                        view.text = render()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun pct(value: Double?): String = value?.let { String.format(Locale.US, "%.1f%%", it * 100.0) } ?: "—"
    private fun signed(value: Double?): String = value?.let { String.format(Locale.US, "%+.3f", it) } ?: "—"

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Orbis Decision Terminal")
            .setContentText("Overlay de decisão e risco ativo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Overlay do terminal", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "terminal_overlay"
        private const val NOTIFICATION_ID = 2201
    }
}
