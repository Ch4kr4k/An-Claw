package dev.anclaw.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.admin.DeviceAdminReceiver
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screen access for the agent: reads the window as text and performs taps,
 * swipes, typing and global actions. Enabled by the user in system Settings;
 * each use goes through claw's permission prompts (the tools are marked
 * non-read-only).
 */
class ClawAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** The active window as an indented outline: text, ids, bounds and what is clickable. */
    fun dump(maxNodes: Int = 400): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val out = StringBuilder("app: ${root.packageName}\n")
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                count++
                out.append("  ".repeat(depth.coerceAtMost(12)))
                    .append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                if (text.isNotBlank()) out.append(" \"").append(text.take(120)).append('"')
                if (desc.isNotBlank() && desc != text) out.append(" desc=\"").append(desc.take(80)).append('"')
                if (id.isNotBlank()) out.append(" #").append(id)
                if (node.isClickable) out.append(" [click]")
                if (node.isEditable) out.append(" [edit]")
                if (node.isChecked) out.append(" [checked]")
                out.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        if (count >= maxNodes) out.append("… (cut at $maxNodes elements)\n")
        return out.toString()
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val desc = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(desc, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    fun tap(x: Float, y: Float, longPress: Boolean = false) =
        gesture(Path().apply { moveTo(x, y) }, if (longPress) 700 else 60)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms.coerceIn(50, 5000))

    /** Clicks the first node whose text or description contains [text]. */
    fun clickText(text: String): String {
        val root = rootInActiveWindow ?: return "no active window"
        val matches = root.findAccessibilityNodeInfosByText(text)
        val node = matches.firstOrNull() ?: return "no element with text \"$text\""
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return "clicked \"$text\""
        val r = Rect().also { node.getBoundsInScreen(it) }
        return if (tap(r.exactCenterX(), r.exactCenterY())) "tapped \"$text\" at ${r.centerX()},${r.centerY()}" else "could not click \"$text\""
    }

    /** Types into the focused field (or the first editable one). */
    fun type(text: String, append: Boolean): String {
        val root = rootInActiveWindow ?: return "no active window"
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.isEditable) return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            find(root)
        } ?: return "no text field on screen"
        val value = if (append) (field.text?.toString().orEmpty() + text) else text
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed ${text.length} characters" else "the field refused the text"
    }

    fun global(action: String): Boolean {
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return performGlobalAction(id)
    }

    companion object {
        @Volatile var instance: ClawAccessibilityService? = null
    }
}

/** Device admin: lets the agent lock the screen on request. */
class ClawAdminReceiver : DeviceAdminReceiver()
