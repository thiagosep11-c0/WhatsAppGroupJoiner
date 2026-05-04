package com.groupjoiner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GroupJoinerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedTime = 0L
    private val cooldown = 5000L
    private var waitingForGroup = false

    companion object {
        var serviceInstance: GroupJoinerService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (!waitingForGroup) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < cooldown) return

        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed

            if (isGroupInviteScreen(root)) {
                val result = tryClickJoinButton(root)
                root.recycle()
                lastProcessedTime = System.currentTimeMillis()
                waitingForGroup = false

                handler.postDelayed({
                    val appIntent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
                    appIntent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    appIntent?.let { startActivity(it) }
                    MainActivity.instance?.onGroupProcessed(result.first, result.second)
                }, 1200L)
            } else {
                root.recycle()
                waitingForGroup = false
                lastProcessedTime = System.currentTimeMillis()
                handler.postDelayed({
                    val appIntent = packageManager.getLaunchIntentForPackage("com.groupjoiner")
                    appIntent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    appIntent?.let { startActivity(it) }
                    MainActivity.instance?.onGroupProcessed(false, "invalid")
                }, 1200L)
            }
        }, 3000L)
    }

    private fun isGroupInviteScreen(root: AccessibilityNodeInfo): Boolean {
        val texts = listOf("entrar no grupo","join group","enviar pedido","send request",
            "grupo do whatsapp","whatsapp group","convidado para","you've been invited")
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) { nodes.forEach { it.recycle() }; return true }
        }
        return false
    }

    private fun tryClickJoinButton(root: AccessibilityNodeInfo): Pair<Boolean, String> {
        for (text in listOf("entrar no grupo","join group","entrar","join")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node.recycle(); return Pair(true, "joined") }
                val p = node.parent; if (p?.isClickable == true) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); node.recycle(); return Pair(true, "joined") }
                node.recycle()
            }
        }
        for (text in listOf("enviar pedido","send request","solicitar","request")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node.recycle(); return Pair(true, "requested") }
                val p = node.parent; if (p?.isClickable == true) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); node.recycle(); return Pair(true, "requested") }
                node.recycle()
            }
        }
        return Pair(false, "not_found")
    }

    fun setWaiting(waiting: Boolean) {
        waitingForGroup = waiting
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
    }
}
