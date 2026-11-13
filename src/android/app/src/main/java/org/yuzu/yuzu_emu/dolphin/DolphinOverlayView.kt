// SPDX-FileCopyrightText: Copyright 2026 Lichii Android Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.dolphin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.dolphinemu.dolphinemu.NativeLibrary
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class DolphinOverlayView(
    context: Context,
    val isWiiMode: Boolean,
    private val onOpenQuickMenu: () -> Unit
) : View(context) {

    private data class VirtualButton(
        val id: Int,
        val label: String,
        val rect: RectF = RectF(),
        val isRound: Boolean = true,
        val customColor: Int? = null,
        var pressed: Boolean = false
    )

    private data class VirtualJoystick(
        val isLeft: Boolean,
        val isCStick: Boolean = false,
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

    private val buttons = mutableListOf<VirtualButton>()
    private val pointerToButton = mutableMapOf<Int, VirtualButton>()

    private val mainStick = VirtualJoystick(isLeft = true)
    private val cStick = VirtualJoystick(isLeft = false, isCStick = true)

    private val btnFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 28, 33, 40)
    }

    private val btnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 180, 255)
    }

    private val btnStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(160, 255, 255, 255)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 32f
        isFakeBoldText = true
    }

    private val stickBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 28, 33, 40)
    }

    private val stickKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(160, 180, 200, 225)
    }

    private val cStickKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 255, 215, 0) // GameCube Yellow C-Stick
    }

    companion object {
        private const val ACTION_TOGGLE_HIDE_UI = -201
        private const val ACTION_OPEN_MENU = -202

        // GameCube Virtual Button IDs
        const val GC_BUTTON_A = 0
        const val GC_BUTTON_B = 1
        const val GC_BUTTON_START = 2
        const val GC_BUTTON_X = 3
        const val GC_BUTTON_Y = 4
        const val GC_BUTTON_Z = 5
        const val GC_DPAD_UP = 6
        const val GC_DPAD_DOWN = 7
        const val GC_DPAD_LEFT = 8
        const val GC_DPAD_RIGHT = 9
        const val GC_TRIGGER_L = 20
        const val GC_TRIGGER_R = 21

        // Wii Virtual Button IDs
        const val WII_BUTTON_A = 100
        const val WII_BUTTON_B = 101
        const val WII_BUTTON_MINUS = 102
        const val WII_BUTTON_PLUS = 103
        const val WII_BUTTON_HOME = 104
        const val WII_BUTTON_ONE = 105
        const val WII_BUTTON_TWO = 106
        const val WII_DPAD_UP = 107
        const val WII_DPAD_DOWN = 108
        const val WII_DPAD_LEFT = 109
        const val WII_DPAD_RIGHT = 110
        const val WII_NUNCHUK_C = 111
        const val WII_NUNCHUK_Z = 112
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls(w.toFloat(), h.toFloat())
    }

    private fun layoutControls(w: Float, h: Float) {
        buttons.clear()
        val unit = min(w, h)
        val btnRadius = unit * 0.058f
        val dpadSize = unit * 0.10f
        textPaint.textSize = unit * 0.032f

        // Main Joystick (bottom-left)
        mainStick.radius = unit * 0.135f
        mainStick.centerX = w * 0.14f
        mainStick.centerY = h * 0.73f
        mainStick.knobX = mainStick.centerX
        mainStick.knobY = mainStick.centerY

        // D-Pad (left-center)
        val dpadCenterX = w * 0.28f
        val dpadCenterY = h * 0.52f
        val dpadOffset = dpadSize * 0.90f
        val upId = if (isWiiMode) WII_DPAD_UP else GC_DPAD_UP
        val downId = if (isWiiMode) WII_DPAD_DOWN else GC_DPAD_DOWN
        val leftId = if (isWiiMode) WII_DPAD_LEFT else GC_DPAD_LEFT
        val rightId = if (isWiiMode) WII_DPAD_RIGHT else GC_DPAD_RIGHT
        addRectButton(upId, "▲", dpadCenterX, dpadCenterY - dpadOffset, dpadSize * 0.44f, dpadSize * 0.44f, false)
        addRectButton(downId, "▼", dpadCenterX, dpadCenterY + dpadOffset, dpadSize * 0.44f, dpadSize * 0.44f, false)
        addRectButton(leftId, "◀", dpadCenterX - dpadOffset, dpadCenterY, dpadSize * 0.44f, dpadSize * 0.44f, false)
        addRectButton(rightId, "▶", dpadCenterX + dpadOffset, dpadCenterY, dpadSize * 0.44f, dpadSize * 0.44f, false)

        if (!isWiiMode) {
            // GameCube Layout
            // C-Stick (bottom-right inner)
            cStick.radius = unit * 0.115f
            cStick.centerX = w * 0.73f
            cStick.centerY = h * 0.77f
            cStick.knobX = cStick.centerX
            cStick.knobY = cStick.centerY

            // ABXY GameCube Cluster (Iconic large green A, red B, grey X/Y)
            val clusterCenterX = w * 0.88f
            val clusterCenterY = h * 0.48f

            // A: Big green center button
            addRectButton(GC_BUTTON_A, "A", clusterCenterX, clusterCenterY, btnRadius * 1.35f, btnRadius * 1.35f, true, Color.argb(160, 46, 204, 113))
            // B: Red button at bottom-left of A
            addRectButton(GC_BUTTON_B, "B", clusterCenterX - btnRadius * 1.55f, clusterCenterY + btnRadius * 0.85f, btnRadius * 0.88f, btnRadius * 0.88f, true, Color.argb(160, 231, 76, 60))
            // X: Grey button to the right of A
            addRectButton(GC_BUTTON_X, "X", clusterCenterX + btnRadius * 1.55f, clusterCenterY - btnRadius * 0.25f, btnRadius * 0.82f, btnRadius * 1.25f, false)
            // Y: Grey button above A
            addRectButton(GC_BUTTON_Y, "Y", clusterCenterX - btnRadius * 0.35f, clusterCenterY - btnRadius * 1.60f, btnRadius * 1.25f, btnRadius * 0.82f, false)
            // Z: Purple trigger button top right
            addRectButton(GC_BUTTON_Z, "Z", w * 0.92f, h * 0.12f, unit * 0.08f, unit * 0.05f, false, Color.argb(160, 155, 89, 182))

            // L & R triggers
            val shoulderW = unit * 0.10f
            val shoulderH = unit * 0.052f
            addRectButton(GC_TRIGGER_L, "L", w * 0.11f, h * 0.11f, shoulderW, shoulderH, false)
            addRectButton(GC_TRIGGER_R, "R", w * 0.80f, h * 0.11f, shoulderW, shoulderH, false)

            // Start / Pause (Center)
            addRectButton(GC_BUTTON_START, "START", w * 0.50f, h * 0.88f, unit * 0.075f, unit * 0.040f, false)
        } else {
            // Wii Mode Layout
            // Wiimote A & B
            val wiiClusterX = w * 0.88f
            val wiiClusterY = h * 0.48f
            addRectButton(WII_BUTTON_A, "A", wiiClusterX, wiiClusterY, btnRadius * 1.2f, btnRadius * 1.2f, true, Color.argb(160, 0, 174, 239))
            addRectButton(WII_BUTTON_B, "B (Trig)", wiiClusterX, wiiClusterY + btnRadius * 2.1f, unit * 0.09f, unit * 0.05f, false)

            // 1 & 2 buttons
            addRectButton(WII_BUTTON_ONE, "1", wiiClusterX - btnRadius * 1.5f, wiiClusterY + btnRadius * 0.8f, btnRadius * 0.85f, btnRadius * 0.85f, true)
            addRectButton(WII_BUTTON_TWO, "2", wiiClusterX - btnRadius * 1.5f, wiiClusterY + btnRadius * 2.3f, btnRadius * 0.85f, btnRadius * 0.85f, true)

            // Plus, Minus, Home
            addRectButton(WII_BUTTON_MINUS, "−", w * 0.42f, h * 0.88f, btnRadius * 0.75f, btnRadius * 0.75f, true)
            addRectButton(WII_BUTTON_HOME, "HOME", w * 0.50f, h * 0.88f, unit * 0.070f, unit * 0.038f, false, Color.argb(160, 0, 174, 239))
            addRectButton(WII_BUTTON_PLUS, "+", w * 0.58f, h * 0.88f, btnRadius * 0.75f, btnRadius * 0.75f, true)

            // Nunchuk C & Z on top left
            addRectButton(WII_NUNCHUK_C, "C", w * 0.09f, h * 0.11f, unit * 0.075f, unit * 0.048f, false)
            addRectButton(WII_NUNCHUK_Z, "Z", w * 0.20f, h * 0.11f, unit * 0.075f, unit * 0.048f, false)
        }

        // Top Utility Buttons: HIDE UI & MENU
        val pillW = unit * 0.072f
        val pillH = unit * 0.038f
        val topY = h * 0.065f
        addRectButton(ACTION_TOGGLE_HIDE_UI, "OVERLAY", w * 0.45f, topY, pillW, pillH, false)
        addRectButton(ACTION_OPEN_MENU, "MENU", w * 0.55f, topY, pillW, pillH, false)
    }

    private fun addRectButton(
        id: Int,
        label: String,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
        isRound: Boolean,
        customColor: Int? = null
    ) {
        buttons.add(
            VirtualButton(
                id = id,
                label = label,
                rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH),
                isRound = isRound,
                customColor = customColor
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
            val fill = if (btn.pressed) {
                btnPressedPaint
            } else if (btn.customColor != null) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = btn.customColor
                }
            } else {
                btnFillPaint
            }

            if (btn.isRound) {
                val r = min(btn.rect.width(), btn.rect.height()) / 2f
                canvas.drawCircle(btn.rect.centerX(), btn.rect.centerY(), r, fill)
                canvas.drawCircle(btn.rect.centerX(), btn.rect.centerY(), r, btnStrokePaint)
            } else {
                val corner = min(btn.rect.width(), btn.rect.height()) * 0.32f
                canvas.drawRoundRect(btn.rect, corner, corner, fill)
                canvas.drawRoundRect(btn.rect, corner, corner, btnStrokePaint)
            }
            canvas.drawText(btn.label, btn.rect.centerX(), btn.rect.centerY() - textOffset, textPaint)
        }

        if (controlsVisible) {
            drawJoystick(canvas, mainStick, stickKnobPaint)
            if (!isWiiMode) {
                drawJoystick(canvas, cStick, cStickKnobPaint)
            }
        }
    }

    private fun drawJoystick(canvas: Canvas, stick: VirtualJoystick, knobPaint: Paint) {
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, stickBasePaint)
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, btnStrokePaint)
        val knobRadius = stick.radius * 0.44f
        canvas.drawCircle(stick.knobX, stick.knobY, knobRadius, knobPaint)
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
                    if (isInsideStick(mainStick, x, y) && mainStick.activePointerId == MotionEvent.INVALID_POINTER_ID) {
                        mainStick.activePointerId = pointerId
                        updateStickPosition(mainStick, x, y)
                        invalidate()
                        return true
                    }
                    if (!isWiiMode && isInsideStick(cStick, x, y) && cStick.activePointerId == MotionEvent.INVALID_POINTER_ID) {
                        cStick.activePointerId = pointerId
                        updateStickPosition(cStick, x, y)
                        invalidate()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    if (pid == mainStick.activePointerId) {
                        updateStickPosition(mainStick, px, py)
                    } else if (!isWiiMode && pid == cStick.activePointerId) {
                        updateStickPosition(cStick, px, py)
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(pointerId)
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                for (btn in pointerToButton.values) {
                    btn.pressed = false
                    if (btn.id >= 0) {
                        NativeLibrary.onGamePadEvent("Touchscreen", btn.id, 0)
                    }
                }
                pointerToButton.clear()
                resetStick(mainStick)
                if (!isWiiMode) {
                    resetStick(cStick)
                }
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
            ACTION_TOGGLE_HIDE_UI -> {
                controlsVisible = !controlsVisible
            }
            ACTION_OPEN_MENU -> {
                onOpenQuickMenu()
            }
            else -> {
                btn.pressed = true
                pointerToButton[pointerId] = btn
                NativeLibrary.onGamePadEvent("Touchscreen", btn.id, 1)
            }
        }
    }

    private fun releasePointer(pointerId: Int) {
        val btn = pointerToButton.remove(pointerId)
        if (btn != null) {
            btn.pressed = false
            if (btn.id >= 0) {
                NativeLibrary.onGamePadEvent("Touchscreen", btn.id, 0)
            }
            return
        }
        if (pointerId == mainStick.activePointerId) {
            resetStick(mainStick)
            return
        }
        if (!isWiiMode && pointerId == cStick.activePointerId) {
            resetStick(cStick)
            return
        }
    }

    private fun isInsideStick(stick: VirtualJoystick, x: Float, y: Float): Boolean {
        return hypot(x - stick.centerX, y - stick.centerY) <= stick.radius * 1.35f
    }

    private fun updateStickPosition(stick: VirtualJoystick, x: Float, y: Float) {
        val dx = x - stick.centerX
        val dy = y - stick.centerY
        val dist = hypot(dx, dy)
        val maxDist = stick.radius
        val clampedDist = min(dist, maxDist)
        val angleRad = atan2(dy, dx)

        stick.knobX = stick.centerX + cos(angleRad) * clampedDist
        stick.knobY = stick.centerY + sin(angleRad) * clampedDist

        val normX = (dx / maxDist).coerceIn(-1f, 1f)
        val normY = (dy / maxDist).coerceIn(-1f, 1f)

        emitStickAxes(stick.isCStick, normX, -normY) // Dolphin Y axis: positive is up
    }

    private fun resetStick(stick: VirtualJoystick) {
        stick.activePointerId = MotionEvent.INVALID_POINTER_ID
        stick.knobX = stick.centerX
        stick.knobY = stick.centerY
        emitStickAxes(stick.isCStick, 0f, 0f)
    }

    private fun emitStickAxes(isCStick: Boolean, x: Float, y: Float) {
        if (!isCStick) {
            NativeLibrary.onGamePadMoveEvent("Touchscreen", 10, x)
            NativeLibrary.onGamePadMoveEvent("Touchscreen", 11, y)
        } else {
            NativeLibrary.onGamePadMoveEvent("Touchscreen", 15, x)
            NativeLibrary.onGamePadMoveEvent("Touchscreen", 16, y)
        }
    }
}
