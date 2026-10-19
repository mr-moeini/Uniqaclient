package com.mrmoeini.smsforwarder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The core "Action Engine" + "UI Discovery" of the agent. Mirrors what Appium's UiAutomator2
 * driver does, using the public Accessibility Service API (no root, no instrumentation).
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentStore.setServiceRunning(this, true)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onDestroy() {
        if (instance === this) instance = null
        AgentStore.setServiceRunning(applicationContext, false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI dump

    fun dumpUi(): UiNode? {
        val root = rootInActiveWindow ?: return null
        return try {
            UiNode.from(root)
        } finally {
            root.recycle()
        }
    }

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    // ------------------------------------------------------------------ gestures

    private fun runGesture(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        val ok = AtomicReference(false)
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) return false
        latch.await(durationMs + 3000L, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    fun tap(x: Int, y: Int): Boolean {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        return runGesture(p, 60)
    }

    fun longClick(x: Int, y: Int): Boolean {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        return runGesture(p, 700)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val p = Path()
        p.moveTo(x1.toFloat(), y1.toFloat())
        p.lineTo(x2.toFloat(), y2.toFloat())
        return runGesture(p, durationMs.coerceAtLeast(50))
    }

    // ------------------------------------------------------------------ node actions (by selector)

    private fun findLiveNode(selector: Selector): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val found = findLiveRec(root, selector, intArrayOf(0))
        if (found !== root) root.recycle()
        return found
    }

    private fun findLiveRec(node: AccessibilityNodeInfo, sel: Selector, counter: IntArray): AccessibilityNodeInfo? {
        val n = UiNode.from(node, 0, 0).let {
            UiNode(it.className, it.text, it.contentDesc, it.resourceId, it.packageName, it.bounds,
                it.clickable, it.longClickable, it.scrollable, it.enabled, it.focused, it.checked,
                it.checkable, it.password, it.editable, it.index, emptyList())
        }
        if (sel.matches(n)) {
            if (counter[0] == sel.index) return node
            counter[0]++
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findLiveRec(c, sel, counter)
            if (found != null) {
                if (found !== c) c.recycle()
                return found
            }
            c.recycle()
        }
        return null
    }

    fun clickNode(selector: Selector): Boolean {
        val node = findLiveNode(selector) ?: return false
        try {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            node.recycle()
        }
    }

    fun longClickNode(selector: Selector): Boolean {
        val node = findLiveNode(selector) ?: return false
        try {
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } finally {
            node.recycle()
        }
    }

    fun setTextNode(selector: Selector, text: String): Boolean {
        val node = findLiveNode(selector) ?: return false
        try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            node.recycle()
        }
    }

    fun scrollNode(selector: Selector, forward: Boolean): Boolean {
        val node = findLiveNode(selector) ?: return false
        try {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return node.performAction(action)
        } finally {
            node.recycle()
        }
    }

    fun nodeBounds(selector: Selector): android.graphics.Rect? {
        val node = findLiveNode(selector) ?: return null
        try {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            return r
        } finally {
            node.recycle()
        }
    }

    // ------------------------------------------------------------------ global actions / keys

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pressNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun pressEnter(): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else false
        } finally {
            node.recycle()
        }
    }

    // ------------------------------------------------------------------ screenshot

    @SuppressLint("NewApi")
    fun takeScreenshotSync(timeoutMs: Long = 6000L): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(null)
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hb: HardwareBuffer = screenshot.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        result.set(bmp?.copy(Bitmap.Config.ARGB_8888, false))
                        hb.close()
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun mainExecutor() = java.util.concurrent.Executor { r -> Handler(Looper.getMainLooper()).post(r) }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null

        fun isRunning(): Boolean = instance != null
    }
}
