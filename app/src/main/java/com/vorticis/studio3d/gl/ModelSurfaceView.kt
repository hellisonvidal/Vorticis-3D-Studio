package com.vorticis.studio3d.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.vorticis.studio3d.core.model.Scene

/**
 * Visualizador 3D: 1 dedo gira, 2 dedos aproximam/afastam e movem,
 * toque duplo reinicia a câmera. Gira sozinho até o primeiro toque.
 */
@SuppressLint("ClickableViewAccessibility")
class ModelSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer = ModelRenderer { requestRender() }
    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var currentScene: Scene? = null

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.zoom = (renderer.zoom * d.scaleFactor).coerceIn(0.2f, 12f)
            requestRender()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer.resetView(); renderer.autoRotate = true; requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setScene(scene: Scene) {
        if (scene === currentScene) return
        currentScene = scene
        renderer.setScene(scene)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; renderer.autoRotate = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) { lastMidX = (e.getX(0) + e.getX(1)) / 2; lastMidY = (e.getY(0) + e.getY(1)) / 2 }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scale.isInProgress) {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    renderer.yaw -= dx / width * 4f
                    renderer.pitch = (renderer.pitch + dy / height * 3f).coerceIn(-1.2f, 1.45f)
                    lastX = e.x; lastY = e.y
                } else if (e.pointerCount >= 2) {
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    renderer.panX -= (mx - lastMidX) / width * 2f / renderer.zoom
                    renderer.panY += (my - lastMidY) / height * 2f / renderer.zoom
                    lastMidX = mx; lastMidY = my
                }
                requestRender()
            }
        }
        return true
    }
}
