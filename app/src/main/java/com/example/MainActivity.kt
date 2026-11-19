package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager
    private val geminiClient = GeminiClient()

    private var availableModels: List<String> = PrefsManager.DEFAULT_AVAILABLE_MODELS
    private var selectedModelId: String = PrefsManager.DEFAULT_MODEL_ID

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePermissionStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        prefsManager = PrefsManager(this)

        applyEdgeToEdgeInsets()
        loadSavedSettings()
        setupListeners()
        observeServiceState()
        syncAvailableModelsFromApi()
    }

    override fun onResume() {
        super.onResume()
        availableModels = prefsManager.cachedModels
        selectedModelId = prefsManager.modelId
        bindModelDropdown(availableModels, selectedModelId)
        updateApiStatusBadge()
        updatePermissionStatuses()
        updateServiceToggleUi(FloatingOverlayService.isRunning.value)
    }

    private fun applyEdgeToEdgeInsets() {
        val initialScrollTop = binding.mainScrollView.paddingTop
        val initialFooterBottom = binding.bottomActionBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.mainScrollView.updatePadding(top = initialScrollTop + systemBars.top)
            binding.bottomActionBar.updatePadding(
                bottom = initialFooterBottom + maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun loadSavedSettings() {
        binding.etApiKey.setText(prefsManager.apiKey)

        availableModels = prefsManager.cachedModels
        selectedModelId = prefsManager.modelId
        if (!availableModels.contains(selectedModelId)) {
            selectedModelId = availableModels.firstOrNull() ?: PrefsManager.DEFAULT_MODEL_ID
            prefsManager.modelId = selectedModelId
        }

        bindModelDropdown(availableModels, selectedModelId)
        binding.etSystemInstruction.setText(prefsManager.systemInstruction)
        binding.switchTripleTap.isChecked = prefsManager.isTripleTapEnabled
        binding.switchVibrate.isChecked = prefsManager.isTripleTapVibrateEnabled
        updateApiStatusBadge()
    }

    private fun bindModelDropdown(models: List<String>, currentSelection: String) {
        val fullList = (models + PrefsManager.PINNED_MODELS).distinct()
        val adapter = object : ArrayAdapter<String>(this, R.layout.item_model_dropdown, fullList) {
            private val noFilter = object : android.widget.Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    return FilterResults().apply {
                        values = fullList
                        count = fullList.size
                    }
                }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }

            override fun getFilter(): android.widget.Filter = noFilter
        }
        binding.dropdownModel.setAdapter(adapter)
        binding.dropdownModel.setText(currentSelection, false)
    }

    private fun syncAvailableModelsFromApi() {
        val typedKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        if (typedKey.isNotEmpty() && typedKey != prefsManager.apiKey) {
            prefsManager.apiKey = typedKey
        }
        updateApiStatusBadge()

        val apiKey = prefsManager.getEffectiveApiKey()
        if (apiKey.isEmpty()) {
            binding.progressModels.visibility = View.GONE
            binding.tvModelSyncStatus.text =
                getString(R.string.status_models_default, availableModels.size)
            return
        }

        lifecycleScope.launch {
            binding.progressModels.visibility = View.VISIBLE
            binding.btnRefreshModels.isEnabled = false
            binding.btnRefreshModels.alpha = 0.5f
            binding.tvModelSyncStatus.text = getString(R.string.status_models_loading)

            when (val result = geminiClient.fetchAvailableModels(apiKey)) {
                is ModelsResult.Success -> {
                    val liveModels = result.models
                    availableModels = (liveModels + PrefsManager.PINNED_MODELS).distinct()
                    prefsManager.cachedModels = availableModels

                    val currentInput = binding.dropdownModel.text?.toString()?.trim().orEmpty()
                    selectedModelId = when {
                        currentInput.isNotEmpty() && liveModels.contains(currentInput) -> currentInput
                        liveModels.contains(selectedModelId) -> selectedModelId
                        else -> liveModels.first()
                    }
                    prefsManager.modelId = selectedModelId
                    bindModelDropdown(availableModels, selectedModelId)
                    binding.tvModelSyncStatus.text =
                        getString(R.string.status_models_synced, liveModels.size)
                    notifyServiceSettingsChanged()
                }

                is ModelsResult.Error -> {
                    binding.tvModelSyncStatus.text =
                        getString(R.string.status_models_error, result.message)
                }
            }

            binding.progressModels.visibility = View.GONE
            binding.btnRefreshModels.isEnabled = true
            binding.btnRefreshModels.alpha = 1f
        }
    }

    private fun updateApiStatusBadge() {
        val hasKey = prefsManager.hasValidApiKey()
        if (hasKey) {
            binding.tvApiKeyBadge.text = getString(R.string.status_api_ready)
            binding.tvApiKeyBadge.setTextColor(ContextCompat.getColor(this, R.color.ink_inverse))
            binding.tvApiKeyBadge.setBackgroundResource(R.drawable.bg_pill_granted)
            binding.tilApiKey.error = null
        } else {
            binding.tvApiKeyBadge.text = getString(R.string.status_api_missing)
            binding.tvApiKeyBadge.setTextColor(ContextCompat.getColor(this, R.color.ink_primary))
            binding.tvApiKeyBadge.setBackgroundResource(R.drawable.bg_pill_denied)
        }
    }

    private fun saveInputSettings(showConfirmation: Boolean, syncModelsIfKeyChanged: Boolean = false) {
        val previousKey = prefsManager.apiKey
        val newApiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        val typedModel = binding.dropdownModel.text?.toString()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: selectedModelId
        val systemInstruction = binding.etSystemInstruction.text?.toString()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: PrefsManager.DEFAULT_SYSTEM_INSTRUCTION

        selectedModelId = typedModel
        prefsManager.apiKey = newApiKey
        prefsManager.modelId = selectedModelId
        prefsManager.systemInstruction = systemInstruction
        prefsManager.isTripleTapEnabled = binding.switchTripleTap.isChecked
        prefsManager.isTripleTapVibrateEnabled = binding.switchVibrate.isChecked

        updateApiStatusBadge()
        notifyServiceSettingsChanged()

        if (syncModelsIfKeyChanged && newApiKey.isNotEmpty() && newApiKey != previousKey) {
            syncAvailableModelsFromApi()
        }

        if (showConfirmation) {
            Toast.makeText(this, R.string.msg_settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifyServiceSettingsChanged() {
        if (FloatingOverlayService.isRunning.value) {
            val refreshIntent = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_REFRESH_SETTINGS
            }
            startService(refreshIntent)
        }
    }

    private fun setupListeners() {
        // Hanya pilihan dropdown model yang tersedia — otomatis simpan saat dipilih
        binding.dropdownModel.setOnItemClickListener { parent, _, position, _ ->
            val picked = parent.getItemAtPosition(position)?.toString()?.trim().orEmpty()
            if (picked.isNotEmpty()) {
                selectedModelId = picked
                prefsManager.modelId = picked
                notifyServiceSettingsChanged()
            }
        }

        binding.btnRefreshModels.setOnClickListener {
            saveInputSettings(showConfirmation = false)
            syncAvailableModelsFromApi()
        }

        binding.btnSaveSettings.setOnClickListener {
            saveInputSettings(showConfirmation = true, syncModelsIfKeyChanged = true)
        }

        binding.switchTripleTap.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.isTripleTapEnabled = isChecked
        }

        binding.switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.isTripleTapVibrateEnabled = isChecked
        }

        // Pintasan izin 1: Tampil di atas aplikasi lain
        binding.btnPermOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // Pintasan izin 2: Layanan Aksesibilitas
        binding.btnPermAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Pintasan izin 3: Notifikasi (Android 13+)
        binding.btnPermNotification.setOnClickListener {
            requestOrOpenNotificationPermission()
        }

        // Tombol Utama: Aktifkan / Matikan Floating AI
        binding.btnToggleFloating.setOnClickListener {
            if (FloatingOverlayService.isRunning.value) {
                stopFloatingService()
            } else {
                startFloatingService()
            }
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FloatingOverlayService.isRunning.collect { running ->
                    updateServiceToggleUi(running)
                }
            }
        }
    }

    private fun startFloatingService() {
        saveInputSettings(showConfirmation = false)

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.msg_overlay_permission_needed, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (!prefsManager.hasValidApiKey()) {
            binding.tilApiKey.error = getString(R.string.msg_api_key_required)
            Toast.makeText(this, R.string.msg_api_key_required, Toast.LENGTH_LONG).show()
            return
        } else {
            binding.tilApiKey.error = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val serviceIntent = Intent(this, FloatingOverlayService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopFloatingService() {
        val serviceIntent = Intent(this, FloatingOverlayService::class.java)
        stopService(serviceIntent)
    }

    private fun requestOrOpenNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasNotificationPermission()) {
                openAppNotificationSettings()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            openAppNotificationSettings()
        }
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun updatePermissionStatuses() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = ScreenReaderAccessibilityService.isServiceEnabled(this)
        val hasNotification = hasNotificationPermission()

        applyPermissionBadge(
            badge = binding.tvOverlayPermStatus,
            button = binding.btnPermOverlay,
            granted = hasOverlay
        )
        applyPermissionBadge(
            badge = binding.tvAccessibilityPermStatus,
            button = binding.btnPermAccessibility,
            granted = hasAccessibility
        )
        applyPermissionBadge(
            badge = binding.tvNotificationPermStatus,
            button = binding.btnPermNotification,
            granted = hasNotification
        )
    }

    private fun applyPermissionBadge(
        badge: TextView,
        button: MaterialButton,
        granted: Boolean
    ) {
        if (granted) {
            badge.text = getString(R.string.status_granted)
            badge.setTextColor(ContextCompat.getColor(this, R.color.ink_inverse))
            badge.setBackgroundResource(R.drawable.bg_pill_granted)
            button.text = getString(R.string.btn_permission_ok)
            button.setTextColor(ContextCompat.getColor(this, R.color.ink_secondary))
            button.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.border_strong)
            )
        } else {
            badge.text = getString(R.string.status_not_granted)
            badge.setTextColor(ContextCompat.getColor(this, R.color.ink_primary))
            badge.setBackgroundResource(R.drawable.bg_pill_denied)
            button.text = getString(R.string.btn_grant_permission)
            button.setTextColor(ContextCompat.getColor(this, R.color.ink_primary))
            button.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.border_ink)
            )
        }
    }

    private fun updateServiceToggleUi(running: Boolean) {
        val inkPrimary = ContextCompat.getColor(this, R.color.ink_primary)
        val inkInverse = ContextCompat.getColor(this, R.color.ink_inverse)

        if (running) {
            binding.tvServiceStatusBadge.text = getString(R.string.status_active)
            binding.tvServiceStatusBadge.setTextColor(inkInverse)
            binding.tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_pill_granted)

            // Inverted monochrome button saat aktif (Putih dengan border hitam tebal)
            binding.btnToggleFloating.text = getString(R.string.btn_stop_floating)
            binding.btnToggleFloating.setIconResource(R.drawable.ic_stop)
            binding.btnToggleFloating.backgroundTintList = ColorStateList.valueOf(inkInverse)
            binding.btnToggleFloating.setTextColor(inkPrimary)
            binding.btnToggleFloating.iconTint = ColorStateList.valueOf(inkPrimary)
        } else {
            binding.tvServiceStatusBadge.text = getString(R.string.status_inactive)
            binding.tvServiceStatusBadge.setTextColor(inkPrimary)
            binding.tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_pill_denied)

            // Solid ink-black primary CTA saat siap diaktifkan
            binding.btnToggleFloating.text = getString(R.string.btn_start_floating)
            binding.btnToggleFloating.setIconResource(R.drawable.ic_play)
            binding.btnToggleFloating.backgroundTintList = ColorStateList.valueOf(inkPrimary)
            binding.btnToggleFloating.setTextColor(inkInverse)
            binding.btnToggleFloating.iconTint = ColorStateList.valueOf(inkInverse)
        }
    }
}
