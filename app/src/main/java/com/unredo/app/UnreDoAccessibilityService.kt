package com.unredo.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque

/**
 * Detects the currently visible IME (soft keyboard) window, regardless of which
 * keyboard app is installed, and shows a small floating Undo/Redo toolbar
 * directly above its bounds using an accessibility overlay window.
 *
 * No root, ADB or Shizuku is used anywhere in this class. Only public,
 * documented AccessibilityService / AccessibilityNodeInfo APIs are used.
 */
class UnreDoAccessibilityService : AccessibilityService() {

    private var overlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private lateinit var windowManager: WindowManager

    private val history = TextHistory()

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> refreshKeyboardState()

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)

            else -> Unit
        }
    }

    override fun onInterrupt() {
        removeToolbar()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeToolbar()
    }

    // ---------------------------------------------------------------------
    // Keyboard (IME) window detection
    // ---------------------------------------------------------------------

    private fun refreshKeyboardState() {
        val imeWindow = try {
            windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (t: Throwable) {
            null
        }

        if (imeWindow == null) {
            removeToolbar()
            return
        }

        val bounds = Rect()
        imeWindow.getBoundsInScreen(bounds)

        if (bounds.width() <= 0 || bounds.height() <= 0) {
            removeToolbar()
            return
        }

        showOrUpdateToolbar(bounds)
    }

    // ---------------------------------------------------------------------
    // Overlay toolbar management
    // ---------------------------------------------------------------------

    private fun showOrUpdateToolbar(keyboardBounds: Rect) {
        val existingView = overlayView
        val existingParams = overlayLayoutParams

        if (existingView == null || existingParams == null) {
            createToolbar(keyboardBounds)
            return
        }

        val height = existingView.height
        existingParams.x = keyboardBounds.left
        existingParams.y = if (height > 0) {
            (keyboardBounds.top - height).coerceAtLeast(0)
        } else {
            keyboardBounds.top
        }

        try {
            windowManager.updateViewLayout(existingView, existingParams)
        } catch (t: Throwable) {
            // The overlay may have been torn down concurrently; drop and rebuild next time.
            overlayView = null
            overlayLayoutParams = null
        }
    }

    private fun createToolbar(keyboardBounds: Rect) {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.toolbar_overlay, null)

        view.findViewById<View>(R.id.undoButton).setOnClickListener { performUndo() }
        view.findViewById<View>(R.id.redoButton).setOnClickListener { performRedo() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = keyboardBounds.left
        params.y = keyboardBounds.top

        // The toolbar's height is unknown until it is measured. Correct the
        // vertical position as soon as the first layout pass completes so the
        // toolbar sits flush above the keyboard rather than overlapping it.
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val measuredHeight = view.height
                if (measuredHeight > 0) {
                    params.y = (keyboardBounds.top - measuredHeight).coerceAtLeast(0)
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (t: Throwable) {
                        // Ignore: view may already be detached.
                    }
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })

        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayLayoutParams = params
        } catch (t: Throwable) {
            overlayView = null
            overlayLayoutParams = null
        }
    }

    private fun removeToolbar() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // Already removed; nothing to do.
        }
        overlayView = null
        overlayLayoutParams = null
    }

    // ---------------------------------------------------------------------
    // Undo / Redo
    // ---------------------------------------------------------------------

    private fun handleTextChanged(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            if (!source.isEditable) return
            val key = nodeKey(source)
            val before = event.beforeText?.toString() ?: ""
            val after = event.text?.joinToString(separator = "") { it.toString() } ?: ""
            history.onTextChanged(key, before, after)
        } catch (t: Throwable) {
            // Gracefully ignore any field that misbehaves.
        }
    }

    private fun performUndo() {
        val node = focusedEditableNodeOrNull() ?: return
        try {
            val key = nodeKey(node)
            val currentText = node.text?.toString() ?: ""
            val previous = history.undo(key, currentText) ?: return
            applyText(node, previous)
        } catch (t: Throwable) {
            // Some fields do not support programmatic text replacement; fail silently.
        }
    }

    private fun performRedo() {
        val node = focusedEditableNodeOrNull() ?: return
        try {
            val key = nodeKey(node)
            val currentText = node.text?.toString() ?: ""
            val next = history.redo(key, currentText) ?: return
            applyText(node, next)
        } catch (t: Throwable) {
            // Some fields do not support programmatic text replacement; fail silently.
        }
    }

    private fun focusedEditableNodeOrNull(): AccessibilityNodeInfo? {
        return try {
            val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node != null && node.isEditable) node else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun applyText(node: AccessibilityNodeInfo, newText: String) {
        val setTextArgs = Bundle()
        setTextArgs.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )
        val applied = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)

        if (applied) {
            val selectionArgs = Bundle()
            selectionArgs.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                newText.length
            )
            selectionArgs.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                newText.length
            )
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            } catch (t: Throwable) {
                // Cursor placement is a nice-to-have; ignore failures.
            }
        }
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String {
        return "${node.windowId}:${node.viewIdResourceName ?: ""}:${node.className ?: ""}"
    }

    /**
     * Keeps a bounded per-field undo/redo history. History resets whenever the
     * user moves focus to a different field, since replaying an unrelated
     * field's history would be confusing and potentially unsafe.
     */
    private class TextHistory {
        private val undoStack = ArrayDeque<String>()
        private val redoStack = ArrayDeque<String>()
        private var trackedKey: String? = null
        private var suppressNext = false

        fun onTextChanged(key: String, before: String, after: String) {
            if (suppressNext) {
                suppressNext = false
                return
            }
            if (key != trackedKey) {
                trackedKey = key
                undoStack.clear()
                redoStack.clear()
            }
            if (before != after) {
                undoStack.addLast(before)
                if (undoStack.size > MAX_HISTORY) {
                    undoStack.removeFirst()
                }
                redoStack.clear()
            }
        }

        fun undo(key: String, currentText: String): String? {
            if (key != trackedKey || undoStack.isEmpty()) return null
            val previous = undoStack.removeLast()
            redoStack.addLast(currentText)
            suppressNext = true
            return previous
        }

        fun redo(key: String, currentText: String): String? {
            if (key != trackedKey || redoStack.isEmpty()) return null
            val next = redoStack.removeLast()
            undoStack.addLast(currentText)
            suppressNext = true
            return next
        }

        companion object {
            private const val MAX_HISTORY = 50
        }
    }
}
