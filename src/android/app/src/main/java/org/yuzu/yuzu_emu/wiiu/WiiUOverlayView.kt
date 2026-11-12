// SPDX-FileCopyrightText: Copyright 2026 Lichii Android Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.wiiu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeInput.VPADButton
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class WiiUOverlayView(
    context: Context,
    private val onTogglePadView: () -> Boolean,
    private val onOpenQuickMenu: () -> Unit,
    private val onUnhandledTouch: (action: Int, x: Int, y: Int) -> Unit
) : View(context) {

    private data class VirtualButton(
        val id: Int,
        val label: String,
        val rect: RectF = RectF(),
        val isRound: Boolean = true,
        var pressed: Boolean = false
    )

    private data class VirtualJoystick(
        val isLeft: Boolean,
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var radius: Float = 0f,
        var knobX: Float = 0f,
        var knobY: Float = 0f,
        var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    )

    var controlsVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isPadViewActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val buttons = mutableListOf<VirtualButton>()
    private val pointerToButton = mutableMapOf<Int, VirtualButton>()
    private var touchScreenPointerId: Int = MotionEvent.INVALID_POINTER_ID

    private val leftStick = VirtualJoystick(isLeft = true)
    private val rightStick = VirtualJoystick(isLeft = false)

    private val btnFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(85, 22, 27, 34)
    }

    private val btnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(165, 0, 170, 255)
    }

    private val btnStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(150, 255, 255, 255)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 32f
        isFakeBoldText = true
    }

    private val stickBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(65, 22, 27, 34)
    }

    private val stickKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(145, 200, 220, 255)
    }

    companion object {
        private const val ACTION_TOGGLE_TV_PAD = -100
        private const val ACTION_TOGGLE_HIDE_UI = -101
        private const val ACTION_OPEN_MENU = -102
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls(w.toFloat(), h.toFloat())
    }

    private fun layoutControls(w: Float, h: Float) {
        buttons.clear()
        val unit = min(w, h)
        val btnRadius = unit * 0.058f
        val dpadSize = unit * 0.105f
        textPaint.textSize = unit * 0.032f

        // Left Joystick (bottom-left)
        leftStick.radius = unit * 0.135f
        leftStick.centerX = w * 0.15f
        leftStick.centerY = h * 0.74f
        leftStick.knobX = leftStick.centerX
        leftStick.knobY = leftStick.centerY

        // Right Joystick (bottom-right, inner)
        rightStick.radius = unit * 0.135f
        rightStick.centerX = w * 0.72f
        rightStick.centerY = h * 0.77f
        rightStick.knobX = rightStick.centerX
        rightStick.knobY = rightStick.centerY

        // D-Pad (above-right of left stick or mid-left)
        val dpadCenterX = w * 0.28f
        val dpadCenterY = h * 0.52f
        val dpadOffset = dpadSize * 0.92f
        addRectButton(VPADButton.UP, "▲", dpadCenterX, dpadCenterY - dpadOffset, dpadSize * 0.45f, dpadSize * 0.45f, false)
        addRectButton(VPADButton.DOWN, "▼", dpadCenterX, dpadCenterY + dpadOffset, dpadSize * 0.45f, dpadSize * 0.45f, false)
        addRectButton(VPADButton.LEFT, "◀", dpadCenterX - dpadOffset, dpadCenterY, dpadSize * 0.45f, dpadSize * 0.45f, false)
        addRectButton(VPADButton.RIGHT, "▶", dpadCenterX + dpadOffset, dpadCenterY, dpadSize * 0.45f, dpadSize * 0.45f, false)

        // ABXY Face Buttons (Nintendo Wii U layout: A right, B down, X up, Y left)
        val abxyCenterX = w * 0.86f
        val abxyCenterY = h * 0.52f
        val abxyOffset = btnRadius * 1.95f
        addRectButton(VPADButton.A, "A", abxyCenterX + abxyOffset, abxyCenterY, btnRadius, btnRadius, true)
        addRectButton(VPADButton.B, "B", abxyCenterX, abxyCenterY + abxyOffset, btnRadius, btnRadius, true)
        addRectButton(VPADButton.X, "X", abxyCenterX, abxyCenterY - abxyOffset, btnRadius, btnRadius, true)
        addRectButton(VPADButton.Y, "Y", abxyCenterX - abxyOffset, abxyCenterY, btnRadius, btnRadius, true)

        // Shoulder & Trigger Buttons (ZL, L on top-left; R, ZR on top-right)
        val shoulderW = unit * 0.095f
        val shoulderH = unit * 0.052f
        addRectButton(VPADButton.ZL, "ZL", w * 0.09f, h * 0.11f, shoulderW, shoulderH, false)
        addRectButton(VPADButton.L, "L", w * 0.21f, h * 0.11f, shoulderW, shoulderH, false)
        addRectButton(VPADButton.R, "R", w * 0.79f, h * 0.11f, shoulderW, shoulderH, false)
        addRectButton(VPADButton.ZR, "ZR", w * 0.91f, h * 0.11f, shoulderW, shoulderH, false)

        // L3 / R3 Stick Clicks
        val l3W = unit * 0.055f
        val l3H = unit * 0.042f
        addRectButton(VPADButton.STICKL, "L3", w * 0.06f, h * 0.42f, l3W, l3H, false)
        addRectButton(VPADButton.STICKR, "R3", w * 0.94f, h * 0.42f, l3W, l3H, false)

        // Minus / Plus
        addRectButton(VPADButton.MINUS, "−", w * 0.36f, h * 0.90f, btnRadius * 0.75f, btnRadius * 0.75f, true)
        addRectButton(VPADButton.PLUS, "+", w * 0.64f, h * 0.90f, btnRadius * 0.75f, btnRadius * 0.75f, true)

        // Top Center Utility Pill Buttons (TV/Gamepad Swap, Hide UI, Menu)
        val pillW = unit * 0.075f
        val pillH = unit * 0.042f
        val topY = h * 0.07f
        addRectButton(ACTION_TOGGLE_TV_PAD, "TV/PAD", w * 0.41f, topY, pillW, pillH, false)
        addRectButton(ACTION_TOGGLE_HIDE_UI, "PAD UI", w * 0.50f, topY, pillW, pillH, false)
        addRectButton(ACTION_OPEN_MENU, "MENU", w * 0.59f, topY, pillW, pillH, false)
    }

    private fun addRectButton(
        id: Int,
        label: String,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
        isRound: Boolean
    ) {
        buttons.add(
            VirtualButton(
                id = id,
                label = label,
                rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH),
                isRound = isRound
            )
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val textOffset = (textPaint.descent() + textPaint.ascent()) / 2f

        for (btn in buttons) {
            val isUtility = btn.id < 0
            if (!controlsVisible && !isUtility) {
                continue
            }
            val fill = if (btn.pressed || (btn.id == ACTION_TOGGLE_TV_PAD && isPadViewActive)) {
                btnPressedPaint
            } else {
                btnFillPaint
            }
            if (btn.isRound) {
                val r = min(btn.rect.width(), btn.rect.height()) / 2f
                canvas.drawCircle(btn.rect.centerX(), btn.rect.centerY(), r, fill)
                canvas.drawCircle(btn.rect.centerX(), btn.rect.centerY(), r, btnStrokePaint)
            } else {
                val corner = min(btn.rect.width(), btn.rect.height()) * 0.3f
                canvas.drawRoundRect(btn.rect, corner, corner, fill)
                canvas.drawRoundRect(btn.rect, corner, corner, btnStrokePaint)
            }
            val displayLabel = if (btn.id == ACTION_TOGGLE_TV_PAD) {
                if (isPadViewActive) "PAD" else "TV"
            } else {
                btn.label
            }
            canvas.drawText(displayLabel, btn.rect.centerX(), btn.rect.centerY() - textOffset, textPaint)
        }

        if (controlsVisible) {
            drawJoystick(canvas, leftStick)
            drawJoystick(canvas, rightStick)
        }
    }

    private fun drawJoystick(canvas: Canvas, stick: VirtualJoystick) {
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, stickBasePaint)
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, btnStrokePaint)
        val knobRadius = stick.radius * 0.44f
        canvas.drawCircle(stick.knobX, stick.knobY, knobRadius, stickKnobPaint)
        canvas.drawCircle(stick.knobX, stick.knobY, knobRadius, btnStrokePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)

                val hitButton = findHitButton(x, y)
                if (hitButton != null) {
                    handleButtonDown(pointerId, hitButton)
                    invalidate()
                    return true
                }

                if (controlsVisible) {
                    if (isInsideStick(leftStick, x, y) && leftStick.activePointerId == MotionEvent.INVALID_POINTER_ID) {
                        leftStick.activePointerId = pointerId
                        updateStickPosition(leftStick, x, y)
                        invalidate()
                        return true
                    }
                    if (isInsideStick(rightStick, x, y) && rightStick.activePointerId == MotionEvent.INVALID_POINTER_ID) {
                        rightStick.activePointerId = pointerId
                        updateStickPosition(rightStick, x, y)
                        invalidate()
                        return true
                    }
                }

                // Forward to Wii U GamePad touchscreen
                if (touchScreenPointerId == MotionEvent.INVALID_POINTER_ID) {
                    touchScreenPointerId = pointerId
                    onUnhandledTouch(MotionEvent.ACTION_DOWN, x.toInt(), y.toInt())
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    if (pid == leftStick.activePointerId) {
                        updateStickPosition(leftStick, px, py)
                    } else if (pid == rightStick.activePointerId) {
                        updateStickPosition(rightStick, px, py)
                    } else if (pid == touchScreenPointerId) {
                        onUnhandledTouch(MotionEvent.ACTION_MOVE, px.toInt(), py.toInt())
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                releasePointer(pointerId, x.toInt(), y.toInt())
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                for (btn in pointerToButton.values) {
                    btn.pressed = false
                    if (btn.id >= 0) {
                        NativeInput.onOverlayButton(0, btn.id, false)
                    }
                }
                pointerToButton.clear()
                resetStick(leftStick)
                resetStick(rightStick)
                touchScreenPointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
        }
        return true
    }

    private fun findHitButton(x: Float, y: Float): VirtualButton? {
        for (btn in buttons) {
            if (!controlsVisible && btn.id >= 0) {
                continue
            }
            if (btn.rect.contains(x, y)) {
                return btn
            }
        }
        return null
    }

    private fun handleButtonDown(pointerId: Int, btn: VirtualButton) {
        when (btn.id) {
            ACTION_TOGGLE_TV_PAD -> {
                isPadViewActive = onTogglePadView()
            }
            ACTION_TOGGLE_HIDE_UI -> {
                controlsVisible = !controlsVisible
            }
            ACTION_OPEN_MENU -> {
                onOpenQuickMenu()
            }
            else -> {
                btn.pressed = true
                pointerToButton[pointerId] = btn
                NativeInput.onOverlayButton(0, btn.id, true)
            }
        }
    }

    private fun releasePointer(pointerId: Int, x: Int, y: Int) {
        val btn = pointerToButton.remove(pointerId)
        if (btn != null) {
            btn.pressed = false
            if (btn.id >= 0) {
                NativeInput.onOverlayButton(0, btn.id, false)
            }
            return
        }
        if (pointerId == leftStick.activePointerId) {
            resetStick(leftStick)
            return
        }
        if (pointerId == rightStick.activePointerId) {
            resetStick(rightStick)
            return
        }
        if (pointerId == touchScreenPointerId) {
            touchScreenPointerId = MotionEvent.INVALID_POINTER_ID
            onUnhandledTouch(MotionEvent.ACTION_UP, x, y)
        }
    }

    private fun isInsideStick(stick: VirtualJoystick, x: Float, y: Float): Boolean {
        return hypot(x - stick.centerX, y - stick.centerY) <= stick.radius * 1.25f
    }

    private fun updateStickPosition(stick: VirtualJoystick, x: Float, y: Float) {
        val dx = x - stick.centerX
        val dy = y - stick.centerY
        val dist = hypot(dx, dy)
        val maxDist = stick.radius
        val clampedDist = min(dist, maxDist)
        val angleRad = kotlin.math.atan2(dy, dx)

        stick.knobX = stick.centerX + kotlin.math.cos(angleRad) * clampedDist
        stick.knobY = stick.centerY + kotlin.math.sin(angleRad) * clampedDist

        val normX = (dx / maxDist).coerceIn(-1f, 1f)
        val normY = (dy / maxDist).coerceIn(-1f, 1f)

        emitStickAxes(stick.isLeft, normX, normY)
    }

    private fun resetStick(stick: VirtualJoystick) {
        stick.activePointerId = MotionEvent.INVALID_POINTER_ID
        stick.knobX = stick.centerX
        stick.knobY = stick.centerY
        emitStickAxes(stick.isLeft, 0f, 0f)
    }

    private fun emitStickAxes(isLeft: Boolean, x: Float, y: Float) {
        val leftVal = max(0f, -x)
        val rightVal = max(0f, x)
        val upVal = max(0f, -y)
        val downVal = max(0f, y)

        if (isLeft) {
            NativeInput.onOverlayAxis(0, VPADButton.STICKL_LEFT, leftVal)
            NativeInput.onOverlayAxis(0, VPADButton.STICKL_RIGHT, rightVal)
            NativeInput.onOverlayAxis(0, VPADButton.STICKL_UP, upVal)
            NativeInput.onOverlayAxis(0, VPADButton.STICKL_DOWN, downVal)
        } else {
            NativeInput.onOverlayAxis(0, VPADButton.STICKR_LEFT, leftVal)
            NativeInput.onOverlayAxis(0, VPADButton.STICKR_RIGHT, rightVal)
            NativeInput.onOverlayAxis(0, VPADButton.STICKR_UP, upVal)
            NativeInput.onOverlayAxis(0, VPADButton.STICKR_DOWN, downVal)
        }
    }
}
