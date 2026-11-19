package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefsManager: PrefsManager
    private val geminiClient = GeminiClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeRequestJob: Job? = null

    // Overlay utama (Bubble + Expanded Panel)
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // View 1x1 px transparan untuk mendeteksi Triple-Tap global tanpa memblokir sentuhan
    private var tripleTapSensorView: View? = null
    private var tripleTapSensorParams: WindowManager.LayoutParams? = null

    // Referensi komponen UI overlay
    private var bubbleContainer: View? = null
    private var panelContainer: View? = null
    private var panelHeader: View? = null
    private var tvActiveModel: TextView? = null
    private var btnMinimize: ImageButton? = null
    private var btnClose: ImageButton? = null
    private var btnAutoAnswer: View? = null
    private var loadingContainer: View? = null
    private var tvLoadingStatus: TextView? = null
    private var scrollResponse: ScrollView? = null
    private var tvResponse: TextView? = null
    private var btnCopy: View? = null
    private var tvCopyLabel: TextView? = null
    private var btnClear: View? = null
    private var etPrompt: EditText? = null
    private var btnSend: ImageButton? = null

    private var isExpanded = false
    private var isHiddenByUser = false
    private var isOverlayFocusable = false
    private var lastResponseText = ""

    // State Triple-Tap Detector (3x ketukan dengan jeda antar-ketukan <= 400 ms)
    private var tapCount = 0
    private var lastTapElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefsManager = PrefsManager(this)

        createNotificationChannel()
        startForegroundWithNotification()
        setupTripleTapSensorOverlay()
        setupMainOverlay()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_VISIBILITY -> toggleOverlayVisibility(vibrate = false)
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_SETTINGS -> {
                tvActiveModel?.text = prefsManager.modelId
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _isRunning.value = false
        activeRequestJob?.cancel()
        serviceScope.cancel()
        removeOverlaySafely(overlayView)
        removeOverlaySafely(tripleTapSensorView)
        overlayView = null
        tripleTapSensorView = null
        super.onDestroy()
    }

    private fun removeOverlaySafely(view: View?) {
        if (view != null && view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // Abaikan bila view sudah dilepas oleh sistem
            }
        }
    }

    // ------------------------------------------------------------------------
    // Foreground Notification + Show/Hide Cadangan
    // ------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            getString(R.string.notif_channel_id),
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = ACTION_TOGGLE_VISIBILITY
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            REQ_TOGGLE,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQ_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLabel = if (isHiddenByUser) {
            getString(R.string.notif_action_show)
        } else {
            getString(R.string.notif_action_hide)
        }
        val contentText = if (isHiddenByUser) {
            getString(R.string.notif_text_hidden)
        } else {
            getString(R.string.notif_text_visible)
        }

        return NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, toggleLabel, togglePendingIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopPendingIntent)
            .build()
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    // ------------------------------------------------------------------------
    // Triple-Tap Global Listener (1x1 px transparan di 0,0 tanpa blokir sentuhan)
    // ------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTripleTapSensorOverlay() {
        val sensorView = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    // Jangan ganggu pengguna yang sedang mengetik di keyboard virtual panel
                    if (!isOverlayFocusable) {
                        registerGlobalTap()
                    }
                }
                false
            }
        }

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        tripleTapSensorView = sensorView
        tripleTapSensorParams = params

        try {
            windowManager.addView(sensorView, params)
        } catch (_: Exception) {
            // Izin overlay belum tersedia
        }
    }

    private fun registerGlobalTap() {
        if (!prefsManager.isTripleTapEnabled) {
            tapCount = 0
            return
        }

        val now = SystemClock.elapsedRealtime()
        val delta = now - lastTapElapsedMs

        // Cegah penghitungan ganda bila event dari 2 view datang bersamaan dalam < 45 ms
        if (lastTapElapsedMs != 0L && delta < MIN_DEBOUNCE_MS) {
            return
        }

        if (lastTapElapsedMs == 0L || delta > TRIPLE_TAP_MAX_GAP_MS) {
            tapCount = 1
        } else {
            tapCount += 1
        }
        lastTapElapsedMs = now

        if (tapCount >= 3) {
            tapCount = 0
            lastTapElapsedMs = 0L
            toggleOverlayVisibility(vibrate = true)
        }
    }

    private fun toggleOverlayVisibility(vibrate: Boolean) {
        val root = overlayView ?: return
        isHiddenByUser = !isHiddenByUser

        if (isHiddenByUser) {
            setOverlayFocusable(false)
            hideKeyboard()
            root.visibility = View.GONE
        } else {
            tvActiveModel?.text = prefsManager.modelId
            // Kembalikan ke posisi terakhir yang tersimpan
            overlayParams?.let { params ->
                params.x = prefsManager.lastOverlayX
                params.y = prefsManager.lastOverlayY
                safeUpdateViewLayout(root, params)
            }
            root.visibility = View.VISIBLE
        }

        updateNotification()
        if (vibrate && prefsManager.isTripleTapVibrateEnabled) {
            triggerSubtleHapticFeedback()
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerSubtleHapticFeedback() {
        try {
            val effect = VibrationEffect.createOneShot(
                TRIPLE_TAP_VIBRATE_MS,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator?.vibrate(effect)
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(effect)
            }
        } catch (_: Exception) {
            // Perangkat tidak mendukung vibrator
        }
    }

    // ------------------------------------------------------------------------
    // Overlay Utama: Mode Bubble & Mode Panel + Fokus Keyboard Dinamis
    // ------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupMainOverlay() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_FloatingGeminiAI_Overlay)
        val rootView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_ai, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefsManager.lastOverlayX
            y = prefsManager.lastOverlayY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        overlayView = rootView
        overlayParams = params

        bubbleContainer = rootView.findViewById(R.id.bubbleContainer)
        panelContainer = rootView.findViewById(R.id.panelContainer)
        panelHeader = rootView.findViewById(R.id.panelHeader)
        tvActiveModel = rootView.findViewById(R.id.tvActiveModel)
        btnMinimize = rootView.findViewById(R.id.btnMinimize)
        btnClose = rootView.findViewById(R.id.btnClose)
        btnAutoAnswer = rootView.findViewById(R.id.btnAutoAnswer)
        loadingContainer = rootView.findViewById(R.id.loadingContainer)
        tvLoadingStatus = rootView.findViewById(R.id.tvLoadingStatus)
        scrollResponse = rootView.findViewById(R.id.scrollResponse)
        tvResponse = rootView.findViewById(R.id.tvResponse)
        btnCopy = rootView.findViewById(R.id.btnCopy)
        tvCopyLabel = rootView.findViewById(R.id.tvCopyLabel)
        btnClear = rootView.findViewById(R.id.btnClear)
        etPrompt = rootView.findViewById(R.id.etPrompt)
        btnSend = rootView.findViewById(R.id.btnSend)

        tvActiveModel?.text = prefsManager.modelId

        // Sentuhan di luar panel saat mode focusable -> lepas fokus keyboard agar aplikasi belakang bisa disentuh
        rootView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && isOverlayFocusable) {
                setOverlayFocusable(false)
                hideKeyboard()
            }
            false
        }

        setupBubbleDragAndTap()
        setupPanelHeaderDrag()
        setupPanelActions()

        try {
            windowManager.addView(rootView, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleDragAndTap() {
        val bubble = bubbleContainer ?: return
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            val root = overlayView ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    // Ikut hitung sentuhan pada overlay utama untuk deteksi ketuk 3x cepat
                    registerGlobalTap()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        tapCount = 0
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        safeUpdateViewLayout(root, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        prefsManager.lastOverlayX = params.x
                        prefsManager.lastOverlayY = params.y
                    } else if (!isHiddenByUser) {
                        expandPanel()
                    }
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPanelHeaderDrag() {
        val header = panelHeader ?: return
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        header.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            val root = overlayView ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    if (!isOverlayFocusable) {
                        registerGlobalTap()
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        tapCount = 0
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        safeUpdateViewLayout(root, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        prefsManager.lastOverlayX = params.x
                        prefsManager.lastOverlayY = params.y
                    }
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPanelActions() {
        btnMinimize?.setOnClickListener {
            collapseToBubble()
        }

        btnClose?.setOnClickListener {
            stopSelf()
        }

        // Manajemen fokus keyboard dinamis: lepas FLAG_NOT_FOCUSABLE hanya saat kolom input diketuk
        etPrompt?.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                if (!isOverlayFocusable) {
                    setOverlayFocusable(true)
                }
                view.requestFocus()
                showKeyboard(view)
            }
            false
        }

        etPrompt?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendManualPrompt()
                true
            } else {
                false
            }
        }

        btnSend?.setOnClickListener {
            sendManualPrompt()
        }

        btnAutoAnswer?.setOnClickListener {
            triggerAutoAnswerFromScreen()
        }

        btnCopy?.setOnClickListener {
            copyCurrentResponse()
        }

        btnClear?.setOnClickListener {
            clearConversation()
        }
    }

    private fun expandPanel() {
        isExpanded = true
        tvActiveModel?.text = prefsManager.modelId
        bubbleContainer?.visibility = View.GONE
        panelContainer?.visibility = View.VISIBLE
        // Tetap FLAG_NOT_FOCUSABLE saat baru dibuka sampai user mengetuk EditText
        setOverlayFocusable(false)
    }

    private fun collapseToBubble() {
        isExpanded = false
        setOverlayFocusable(false)
        hideKeyboard()
        panelContainer?.visibility = View.GONE
        bubbleContainer?.visibility = View.VISIBLE
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val root = overlayView ?: return
        val params = overlayParams ?: return
        if (isOverlayFocusable == focusable) return

        isOverlayFocusable = focusable
        params.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        safeUpdateViewLayout(root, params)
    }

    private fun showKeyboard(target: View) {
        target.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = etPrompt?.windowToken ?: overlayView?.windowToken
        if (token != null) {
            imm?.hideSoftInputFromWindow(token, 0)
        }
        etPrompt?.clearFocus()
    }

    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        if (view.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                // Abaikan jika window sudah dilepas
            }
        }
    }

    // ------------------------------------------------------------------------
    // Interaksi Gemini: Tanya Manual & Auto Jawab Layar
    // ------------------------------------------------------------------------

    private fun sendManualPrompt() {
        val prompt = etPrompt?.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return

        etPrompt?.setText("")
        setOverlayFocusable(false)
        hideKeyboard()
        executeGeminiQuery(prompt)
    }

    private fun triggerAutoAnswerFromScreen() {
        setOverlayFocusable(false)
        hideKeyboard()

        val readerService = ScreenReaderAccessibilityService.instance
        if (readerService == null) {
            showResponseText(getString(R.string.floating_accessibility_missing))
            return
        }

        activeRequestJob?.cancel()
        activeRequestJob = serviceScope.launch {
            setLoadingState(true, getString(R.string.floating_scanning_screen))

            // Jeda sangat singkat agar window belakang stabil sebelum dipindai
            delay(100)
            val screenText = readerService.captureActiveScreenText(excludePackageName = packageName)

            if (screenText.isBlank()) {
                setLoadingState(false)
                showResponseText(getString(R.string.floating_screen_empty))
                return@launch
            }

            val autoPrompt = buildString {
                appendLine("Berikut adalah teks yang tampil di layar saat ini:")
                appendLine()
                appendLine(screenText)
                appendLine()
                append("Analisis dan jawab pertanyaan, soal, atau pesan utama dari teks layar di atas secara ringkas dan tepat.")
            }

            executeGeminiQueryInternal(autoPrompt)
        }
    }

    private fun executeGeminiQuery(prompt: String) {
        activeRequestJob?.cancel()
        activeRequestJob = serviceScope.launch {
            executeGeminiQueryInternal(prompt)
        }
    }

    private suspend fun executeGeminiQueryInternal(prompt: String) {
        val apiKey = prefsManager.getConfiguredApiKey()
        val modelId = prefsManager.modelId
        val systemInstruction = prefsManager.systemInstruction

        tvActiveModel?.text = modelId
        setLoadingState(true, getString(R.string.floating_loading_text))

        val result = geminiClient.generateContent(
            apiKey = apiKey,
            modelId = modelId,
            systemInstruction = systemInstruction,
            userPrompt = prompt
        )

        setLoadingState(false)

        when (result) {
            is GeminiResult.Success -> showResponseText(result.text)
            is GeminiResult.Error -> showResponseText(result.message)
        }
    }

    private fun setLoadingState(loading: Boolean, message: String = "") {
        loadingContainer?.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading && message.isNotBlank()) {
            tvLoadingStatus?.text = message
        }
        btnSend?.isEnabled = !loading
        btnAutoAnswer?.isEnabled = !loading
        btnAutoAnswer?.alpha = if (loading) 0.65f else 1f
    }

    private fun showResponseText(text: String) {
        lastResponseText = text
        tvResponse?.text = text
        scrollResponse?.post {
            scrollResponse?.fullScroll(View.FOCUS_UP)
        }
    }

    private fun copyCurrentResponse() {
        val textToCopy = lastResponseText.ifBlank { tvResponse?.text?.toString().orEmpty() }
        if (textToCopy.isBlank()) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Floating Gemini AI", textToCopy))

        tvCopyLabel?.text = getString(R.string.floating_btn_copied)
        serviceScope.launch {
            delay(1400)
            tvCopyLabel?.text = getString(R.string.floating_btn_copy)
        }
    }

    private fun clearConversation() {
        activeRequestJob?.cancel()
        setLoadingState(false)
        lastResponseText = ""
        etPrompt?.setText("")
        tvResponse?.text = getString(R.string.floating_placeholder_response)
    }

    companion object {
        const val ACTION_TOGGLE_VISIBILITY = "com.example.action.TOGGLE_VISIBILITY"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"
        const val ACTION_REFRESH_SETTINGS = "com.example.action.REFRESH_SETTINGS"

        private const val NOTIFICATION_ID = 2026
        private const val REQ_OPEN_APP = 100
        private const val REQ_TOGGLE = 101
        private const val REQ_STOP = 102

        private const val TRIPLE_TAP_MAX_GAP_MS = 400L
        private const val MIN_DEBOUNCE_MS = 45L
        private const val TRIPLE_TAP_VIBRATE_MS = 30L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}
