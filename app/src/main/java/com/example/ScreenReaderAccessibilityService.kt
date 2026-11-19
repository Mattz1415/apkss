package com.example

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class ScreenReaderAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Pembacaan teks layar hanya dilakukan secara on-demand saat pengguna menekan Auto Jawab Layar.
    }

    override fun onInterrupt() {
        // Tidak ada aksi berkelanjutan yang perlu diinterupsi.
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Memindai hierarki node pada layar aplikasi lain yang sedang aktif,
     * menyaring teks kosong/duplikat, lalu menggabungkannya menjadi string bersih.
     */
    fun captureActiveScreenText(excludePackageName: String): String {
        val collected = LinkedHashSet<String>()

        // Prioritaskan daftar windows agar overlay milik sendiri dan system UI diabaikan.
        val interactiveWindows = try {
            windows
        } catch (_: Exception) {
            null
        }

        if (!interactiveWindows.isNullOrEmpty()) {
            val targetWindows = interactiveWindows
                .filter { win ->
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                .sortedByDescending { it.layer }

            for (window in targetWindows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == excludePackageName || pkg == SYSTEM_UI_PACKAGE) {
                    continue
                }
                traverseNode(root, collected)
                if (collected.isNotEmpty()) {
                    break
                }
            }
        }

        // Fallback ke rootInActiveWindow bila daftar windows tidak mengembalikan teks.
        if (collected.isEmpty()) {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString().orEmpty()
                if (pkg != excludePackageName) {
                    traverseNode(activeRoot, collected)
                }
            }
        }

        val combined = collected.joinToString(separator = "\n").trim()
        return if (combined.length > MAX_CAPTURED_CHARS) {
            combined.take(MAX_CAPTURED_CHARS)
        } else {
            combined
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: LinkedHashSet<String>) {
        if (node == null || out.size >= MAX_UNIQUE_SEGMENTS) return
        if (!node.isVisibleToUser) return

        val nodeText = node.text?.toString()?.trim().orEmpty()
        if (nodeText.isNotEmpty() && !isNoiseText(nodeText)) {
            out.add(nodeText)
        } else {
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.length >= 3 && !isNoiseText(desc) && !isLikelyControlLabel(node)) {
                out.add(desc)
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, out)
            }
        }
    }

    private fun isLikelyControlLabel(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return className.contains("ImageButton", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true)
    }

    private fun isNoiseText(text: String): Boolean {
        if (text.isBlank()) return true
        // Abaikan simbol tunggal yang tidak bermakna
        if (text.length == 1 && !text[0].isLetterOrDigit()) return true
        return false
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val MAX_UNIQUE_SEGMENTS = 180
        private const val MAX_CAPTURED_CHARS = 6000

        @Volatile
        var instance: ScreenReaderAccessibilityService? = null
            private set

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedComponent = ComponentName(
                context.packageName,
                ScreenReaderAccessibilityService::class.java.name
            )
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val componentName = ComponentName.unflattenFromString(splitter.next())
                if (componentName != null && componentName == expectedComponent) {
                    return true
                }
            }
            return false
        }
    }
}
