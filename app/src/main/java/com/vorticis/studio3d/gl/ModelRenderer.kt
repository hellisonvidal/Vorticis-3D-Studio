package com.vorticis.studio3d.gl

import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.photo.Cloud
import com.vorticis.studio3d.core.model.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Renderizador OpenGL ES 3.0 com iluminação PBR simplificada, chão em grade e sombra suave. */
class ModelRenderer(private val requestRender: () -> Unit) : GLSurfaceView.Renderer {

    private class GpuMesh(val vao: Int, val vbo: Int, val ibo: Int, val count: Int, val color: FloatArray, val metallic: Float, val roughness: Float, val tex: Int, val photo: Boolean)

    private val pending = AtomicReference<Scene?>(null)
    private val pendingCloud = AtomicReference<Pair<Cloud?, Boolean>?>(null) // (nuvem, nova sessão?)

    // ---- animação de processamento (nuvem de pontos) ----
    private var cloudProg = 0
    private var cloudVao = 0
    private var cloudVbo = 0
    private var cloudCount = 0
    private var cloudStart = 0L        // quando a nuvem apareceu
    private var maskStart = -1L        // quando o recorte chegou
    private var endStart = -1L         // quando o modelo final chegou (nuvem some)
    private var meshFadeStart = -1L
    private var cloudYMin = 0f
    private var cloudYMax = 1f
    private val animating: Boolean get() = cloudCount > 0
    private val meshes = ArrayList<GpuMesh>()
    private val textures = IdentityHashMap<Texture, Int>()
    private var white = 0
    private var prog = 0
    private var gridProg = 0
    private var gridVao = 0

    // câmera orbital
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.35f
    @Volatile var zoom = 1f
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    /** Sem rotação automática (causava tontura); o usuário gira com o dedo. */
    @Volatile var autoRotate = false
    private var target = floatArrayOf(0f, 0.5f, 0f)
    private var radius = 1f
    private var sceneMinY = 0f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private var width = 1
    private var height = 1
    private var lastFrame = 0L

    fun setScene(s: Scene) {
        pending.set(s)
        requestRender()
    }

    /** Mostra/atualiza a nuvem de pontos da foto em processamento (null = encerrar). */
    fun setCloud(c: Cloud?, newSession: Boolean) {
        pendingCloud.set(c to newSession)
        requestRender()
    }

    fun resetView() {
        yaw = 0.6f; pitch = 0.35f; zoom = 1f; panX = 0f; panY = 0f
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GL.glClearColor(0.063f, 0.071f, 0.09f, 1f)
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glEnable(GL.GL_CULL_FACE)
        prog = program(VS, FS)
        gridProg = program(GRID_VS, GRID_FS)
        cloudProg = program(CLOUD_VS, CLOUD_FS)
        val cv = IntArray(1); GL.glGenVertexArrays(1, cv, 0); cloudVao = cv[0]
        val cb = IntArray(1); GL.glGenBuffers(1, cb, 0); cloudVbo = cb[0]
        cloudCount = 0
        white = uploadTexture(Texture(1, 1, intArrayOf(-1)))
        // quad do chão
        val quad = floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f)
        val ids = IntArray(1); GL.glGenVertexArrays(1, ids, 0); gridVao = ids[0]
        GL.glBindVertexArray(gridVao)
        val vb = IntArray(1); GL.glGenBuffers(1, vb, 0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vb[0])
        GL.glBufferData(GL.GL_ARRAY_BUFFER, quad.size * 4, floatBuffer(quad), GL.GL_STATIC_DRAW)
        GL.glEnableVertexAttribArray(0)
        GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 12, 0)
        GL.glBindVertexArray(0)
        // o contexto foi recriado: recarrega a cena atual
        meshes.clear(); textures.clear()
        lastScene?.let { pending.compareAndSet(null, it) }
    }

    private var lastScene: Scene? = null

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        GL.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        pendingCloud.getAndSet(null)?.let { (c, fresh) -> uploadCloud(c, fresh, now) }
        pending.getAndSet(null)?.let {
            upload(it)
            if (animating) { if (endStart < 0) endStart = now; meshFadeStart = now; framePreviewFromScene = true }
        }
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        if (autoRotate) yaw += dt * 0.35f

        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        val aspect = width.toFloat() / height
        val fov = 40f
        val dist = radius / sin(Math.toRadians(fov / 2.0).toFloat()) * 1.15f / zoom * (if (aspect < 1f) 1f / aspect.coerceAtLeast(0.5f) else 1f)
        Matrix.perspectiveM(proj, 0, fov, aspect, dist * 0.02f, dist * 20f + radius * 10f)
        // alvo com deslocamento (pan) no plano da tela
        val rx = cos(yaw); val rz = -sin(yaw)
        val tx = target[0] + panX * rx * radius; val ty = target[1] + panY * radius; val tz = target[2] + panX * rz * radius
        val ex = tx + dist * cos(pitch) * sin(yaw)
        val ey = ty + dist * sin(pitch)
        val ez = tz + dist * cos(pitch) * cos(yaw)
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // chão
        GL.glUseProgram(gridProg)
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(gridProg, "uVP"), 1, false, vp, 0)
        GL.glUniform4f(GL.glGetUniformLocation(gridProg, "uGround"), target[0], sceneMinY - 0.001f, target[2], radius)
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
        GL.glDepthMask(false)
        GL.glDisable(GL.GL_CULL_FACE)
        GL.glBindVertexArray(gridVao)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 6)
        GL.glDepthMask(true)
        GL.glDisable(GL.GL_BLEND)
        GL.glEnable(GL.GL_CULL_FACE)

        // modelos (surgem com fade quando vêm depois da animação)
        val meshAlpha = if (meshFadeStart < 0) 1f else ((now - meshFadeStart) / 0.9e9f).coerceIn(0f, 1f)
        if (meshAlpha >= 1f) meshFadeStart = -1L
        if (meshAlpha < 1f) { GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA) }
        GL.glUseProgram(prog)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), meshAlpha)
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(prog, "uVP"), 1, false, vp, 0)
        GL.glUniform3f(GL.glGetUniformLocation(prog, "uCam"), ex, ey, ez)
        val uColor = GL.glGetUniformLocation(prog, "uColor")
        val uMR = GL.glGetUniformLocation(prog, "uMR")
        val uPhoto = GL.glGetUniformLocation(prog, "uPhoto")
        GL.glUniform1i(GL.glGetUniformLocation(prog, "uTex"), 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        for (m in meshes) {
            GL.glUniform4fv(uColor, 1, m.color, 0)
            GL.glUniform2f(uMR, m.metallic, m.roughness)
            GL.glUniform1f(uPhoto, if (m.photo) 1f else 0f)
            GL.glBindTexture(GL.GL_TEXTURE_2D, m.tex)
            GL.glBindVertexArray(m.vao)
            GL.glDrawElements(GL.GL_TRIANGLES, m.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glBindVertexArray(0)
        if (meshAlpha < 1f) GL.glDisable(GL.GL_BLEND)

        // nuvem de pontos da animação
        if (animating) {
            val t = { start: Long -> if (start < 0) -1f else (now - start) / 1e9f }
            val tEnd = t(endStart)
            if (tEnd > 1.0f) { cloudCount = 0; endStart = -1L; maskStart = -1L } else {
                GL.glUseProgram(cloudProg)
                GL.glUniformMatrix4fv(GL.glGetUniformLocation(cloudProg, "uVP"), 1, false, vp, 0)
                GL.glUniform1f(GL.glGetUniformLocation(cloudProg, "uAppear"), t(cloudStart))
                GL.glUniform1f(GL.glGetUniformLocation(cloudProg, "uMask"), t(maskStart))
                GL.glUniform1f(GL.glGetUniformLocation(cloudProg, "uEnd"), tEnd)
                GL.glUniform1f(GL.glGetUniformLocation(cloudProg, "uTime"), t(cloudStart))
                GL.glUniform1f(GL.glGetUniformLocation(cloudProg, "uPointSize"), height * 0.0055f * dist)
                GL.glUniform2f(GL.glGetUniformLocation(cloudProg, "uYRange"), cloudYMin, cloudYMax)
                GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
                GL.glDepthMask(false)
                GL.glBindVertexArray(cloudVao)
                GL.glDrawArrays(GL.GL_POINTS, 0, cloudCount)
                GL.glBindVertexArray(0)
                GL.glDepthMask(true)
                GL.glDisable(GL.GL_BLEND)
            }
        }
        if (autoRotate || animating || meshFadeStart >= 0) requestRender()
    }

    private var framePreviewFromScene = false

    private fun uploadCloud(c: Cloud?, fresh: Boolean, now: Long) {
        if (c == null) {                      // encerrar (erro ou cancelamento): some suavemente
            if (animating && endStart < 0) endStart = now
            return
        }
        GL.glBindVertexArray(cloudVao)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, cloudVbo)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, c.data.size * 4, floatBuffer(c.data), GL.GL_DYNAMIC_DRAW)
        val st = Cloud.STRIDE * 4
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 3, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 2, GL.GL_FLOAT, false, st, 36)
        GL.glBindVertexArray(0)
        cloudCount = c.count
        if (fresh || cloudStart == 0L) { cloudStart = now; maskStart = -1L; endStart = -1L }
        if (c.masked && maskStart < 0) maskStart = now
        // enquadra a câmera na foto (plano) e no objeto (alvo)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < c.data.size) {
            for (o in intArrayOf(0, 3)) {
                minX = minOf(minX, c.data[i + o]); maxX = maxOf(maxX, c.data[i + o])
                minY = minOf(minY, c.data[i + o + 1]); maxY = maxOf(maxY, c.data[i + o + 1])
            }
            i += Cloud.STRIDE * 7 // amostra
        }
        if (fresh) {
            target = floatArrayOf((minX + maxX) / 2, (minY + maxY) / 2, 0f)
            radius = max(0.3f, kotlin.math.hypot(maxX - minX, maxY - minY) / 2f)
            sceneMinY = minY
            yaw = 0f; pitch = 0.12f; zoom = 1f; panX = 0f; panY = 0f
        }
        cloudYMin = minY; cloudYMax = maxY
    }

    private fun upload(scene: Scene) {
        lastScene = scene
        for (m in meshes) {
            GL.glDeleteVertexArrays(1, intArrayOf(m.vao), 0)
            GL.glDeleteBuffers(2, intArrayOf(m.vbo, m.ibo), 0)
        }
        meshes.clear()
        val used = scene.nodes.mapNotNull { it.material.texture }.toSet()
        textures.entries.removeAll { (t, id) -> if (t !in used) { GL.glDeleteTextures(1, intArrayOf(id), 0); true } else false }

        for (n in scene.nodes) {
            val mesh = n.mesh
            if (mesh.triangleCount == 0) continue
            val inter = FloatArray(mesh.vertexCount * 8)
            for (i in 0 until mesh.vertexCount) {
                inter[i * 8] = mesh.positions[i * 3]; inter[i * 8 + 1] = mesh.positions[i * 3 + 1]; inter[i * 8 + 2] = mesh.positions[i * 3 + 2]
                inter[i * 8 + 3] = mesh.normals[i * 3]; inter[i * 8 + 4] = mesh.normals[i * 3 + 1]; inter[i * 8 + 5] = mesh.normals[i * 3 + 2]
                inter[i * 8 + 6] = mesh.uvs[i * 2]; inter[i * 8 + 7] = mesh.uvs[i * 2 + 1]
            }
            val ids = IntArray(1); GL.glGenVertexArrays(1, ids, 0)
            val bufs = IntArray(2); GL.glGenBuffers(2, bufs, 0)
            GL.glBindVertexArray(ids[0])
            GL.glBindBuffer(GL.GL_ARRAY_BUFFER, bufs[0])
            GL.glBufferData(GL.GL_ARRAY_BUFFER, inter.size * 4, floatBuffer(inter), GL.GL_STATIC_DRAW)
            GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 32, 0)
            GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, 32, 12)
            GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, 32, 24)
            GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, bufs[1])
            val ib = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            ib.put(mesh.indices).position(0)
            GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, ib, GL.GL_STATIC_DRAW)
            GL.glBindVertexArray(0)
            val mat = n.material
            val tex = mat.texture?.let { t -> textures.getOrPut(t) { uploadTexture(t) } } ?: white
            meshes += GpuMesh(ids[0], bufs[0], bufs[1], mesh.indices.size, floatArrayOf(mat.r, mat.g, mat.b, 1f), mat.metallic, mat.roughness, tex, mat.name == "Foto")
        }
        val b = scene.bounds()
        if (animating) { yaw = 0.35f; pitch = 0.15f }
        if (!b.isEmpty) {
            target = floatArrayOf(b.centerX, b.centerY, b.centerZ)
            radius = max(0.3f, kotlin.math.sqrt(b.sizeX * b.sizeX + b.sizeY * b.sizeY + b.sizeZ * b.sizeZ) / 2f)
            sceneMinY = b.minY
        } else {
            target = floatArrayOf(0f, 0.5f, 0f); radius = 1f; sceneMinY = 0f
        }
    }

    private fun uploadTexture(t: Texture): Int {
        val bb = ByteBuffer.allocateDirect(t.width * t.height * 4).order(ByteOrder.nativeOrder())
        for (c in t.pixels) { bb.put((c shr 16).toByte()); bb.put((c shr 8).toByte()); bb.put(c.toByte()); bb.put(255.toByte()) }
        bb.position(0)
        val ids = IntArray(1); GL.glGenTextures(1, ids, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, ids[0])
        GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, t.width, t.height, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bb)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT)
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        return ids[0]
    }

    private fun floatBuffer(a: FloatArray) =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun shader(type: Int, src: String): Int {
        val s = GL.glCreateShader(type)
        GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Vorticis", "Shader: " + GL.glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GL.glCreateProgram()
        GL.glAttachShader(p, shader(GL.GL_VERTEX_SHADER, vs))
        GL.glAttachShader(p, shader(GL.GL_FRAGMENT_SHADER, fs))
        GL.glBindAttribLocation(p, 0, "aPos"); GL.glBindAttribLocation(p, 1, "aNor"); GL.glBindAttribLocation(p, 2, "aUv")
        GL.glLinkProgram(p)
        val ok = IntArray(1); GL.glGetProgramiv(p, GL.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Vorticis", "Program: " + GL.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNor;
layout(location=2) in vec2 aUv;
uniform mat4 uVP;
out vec3 vPos; out vec3 vNor; out vec2 vUv;
void main() {
    vPos = aPos; vNor = aNor; vUv = aUv;
    gl_Position = uVP * vec4(aPos, 1.0);
}"""

        private const val FS = """#version 300 es
precision highp float;
in vec3 vPos; in vec3 vNor; in vec2 vUv;
uniform vec4 uColor; uniform vec2 uMR; uniform vec3 uCam; uniform sampler2D uTex; uniform float uAlpha; uniform float uPhoto;
out vec4 frag;
vec3 toLin(vec3 c) { return pow(c, vec3(2.2)); }
vec3 sky(vec3 d) {
    float t = clamp(d.y * 0.5 + 0.5, 0.0, 1.0);
    return mix(vec3(0.20, 0.19, 0.18), vec3(0.75, 0.85, 1.0), t);
}
void main() {
    vec3 base = toLin(uColor.rgb) * toLin(texture(uTex, vUv).rgb);
    float metal = uMR.x; float rough = clamp(uMR.y, 0.04, 1.0);
    vec3 N = normalize(vNor);
    vec3 V = normalize(uCam - vPos);
    if (dot(N, V) < 0.0) N = -N;
    // iluminação de estúdio: luz principal, preenchimento, contra-luz e ambiente hemisférico
    vec3 L1 = normalize(vec3(0.45, 0.8, 0.75));
    vec3 L2 = normalize(vec3(-0.75, 0.35, 0.3));
    vec3 L3 = normalize(vec3(0.0, 0.5, -1.0));
    vec3 F0 = mix(vec3(0.04), base, metal);
    vec3 diff = base * (1.0 - metal);
    float shin = mix(256.0, 6.0, rough);
    vec3 col = vec3(0.0);
    vec3 Ls[3]; Ls[0] = L1; Ls[1] = L2; Ls[2] = L3;
    vec3 Lc[3]; Lc[0] = vec3(1.55, 1.5, 1.42); Lc[1] = vec3(0.45, 0.5, 0.6); Lc[2] = vec3(0.55, 0.6, 0.7);
    for (int i = 0; i < 3; i++) {
        vec3 L = Ls[i];
        float nl = max(dot(N, L), 0.0);
        // difusa "embrulhada": sombras suaves, sem lado totalmente preto
        float wrap = max((dot(N, L) + 0.35) / 1.35, 0.0);
        vec3 H = normalize(L + V);
        float spec = pow(max(dot(N, H), 0.0), shin) * (shin + 8.0) / 40.0;
        col += (diff * wrap + F0 * spec * nl) * Lc[i];
    }
    vec3 R = reflect(-V, N);
    float fres = pow(1.0 - max(dot(N, V), 0.0), 5.0);
    vec3 F = F0 + (1.0 - F0) * fres * (1.0 - rough);
    col += diff * sky(N) * 0.28;
    col += F * sky(R) * (1.0 - rough * 0.7) * 0.5;
    // fotos: preserva a cor original (a textura já tem a luz real da cena)
    col = mix(col, base * 1.05, uPhoto * 0.55);
    // tonemap ACES (contraste e saturação naturais)
    col *= 1.1;
    col = clamp((col * (2.51 * col + 0.03)) / (col * (2.43 * col + 0.59) + 0.14), 0.0, 1.0);
    frag = vec4(pow(col, vec3(1.0 / 2.2)), uAlpha);
}"""

        private const val CLOUD_VS = """#version 300 es
layout(location=0) in vec3 aPlane;
layout(location=1) in vec3 aTarget;
layout(location=2) in vec3 aColor;
layout(location=3) in vec2 aMeta;
uniform mat4 uVP;
uniform float uAppear; uniform float uMask; uniform float uEnd; uniform float uTime;
uniform float uPointSize; uniform vec2 uYRange;
out vec4 vColor;
void main() {
    float fg = aMeta.x; float seed = aMeta.y;
    // 1) os pontos chegam espalhados e formam a foto
    // chegam de trás (nunca passam perto da câmera)
    vec3 scatter = aPlane * 0.3 + vec3(sin(seed * 91.7) * 1.3, cos(seed * 53.1) * 0.8 + 0.6, -abs(sin(seed * 17.3)) * 1.6 - 0.4);
    float a = smoothstep(0.0, 1.0, uAppear * 1.3 - seed * 0.6);
    vec3 p = mix(scatter, aPlane, a);
    float alpha = a;
    // 2) recorte: o fundo cai e some; o objeto ganha volume
    if (uMask >= 0.0) {
        float k = smoothstep(0.0, 1.0, uMask * 1.1 - seed * 0.5);
        if (fg > 0.5) p = mix(aPlane, aTarget, k);
        else { p = aPlane + vec3(0.0, -k * 0.5, -k * 0.4); alpha *= 1.0 - k; }
    }
    // respiração sutil enquanto processa
    p += vec3(0.0, 0.0, 0.006 * sin(uTime * 4.0 + seed * 30.0));
    // 3) modelo pronto: os pontos se desfazem
    if (uEnd >= 0.0) { alpha *= 1.0 - smoothstep(0.0, 0.9, uEnd); p += normalize(p - vec3(0.0, 0.6, 0.0) + 0.001) * uEnd * 0.15; }
    // linha de varredura que percorre o objeto
    float ny = (p.y - uYRange.x) / max(uYRange.y - uYRange.x, 0.001);
    float sy = 1.0 - fract(uTime * 0.45);
    float scan = exp(-pow((ny - sy) * 14.0, 2.0));
    vColor = vec4(mix(aColor, vec3(0.45, 0.95, 1.0), scan * 0.75) + scan * 0.25, alpha);
    gl_Position = uVP * vec4(p, 1.0);
    gl_PointSize = clamp(uPointSize * (1.0 + scan * 0.9) / gl_Position.w, 1.5, 22.0);
}"""

        private const val CLOUD_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 frag;
void main() {
    vec2 c = gl_PointCoord - 0.5;
    float d = dot(c, c);
    if (d > 0.25) discard;
    frag = vec4(vColor.rgb, vColor.a * (1.0 - d * 2.0));
}"""

        private const val GRID_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP; uniform vec4 uGround;
out vec2 vXZ;
void main() {
    float size = max(uGround.w * 6.0, 4.0);
    vec3 p = vec3(uGround.x + aPos.x * size, uGround.y, uGround.z + aPos.z * size);
    vXZ = p.xz;
    gl_Position = uVP * vec4(p, 1.0);
}"""

        private const val GRID_FS = """#version 300 es
precision highp float;
in vec2 vXZ;
uniform vec4 uGround;
out vec4 frag;
float line(vec2 p, float s) {
    vec2 g = abs(fract(p / s - 0.5) - 0.5) / fwidth(p / s);
    return 1.0 - min(min(g.x, g.y), 1.0);
}
void main() {
    vec2 c = vXZ - uGround.xz;
    float d = length(c) / max(uGround.w * 5.0, 3.0);
    float fade = 1.0 - smoothstep(0.3, 1.0, d);
    float minor = line(vXZ, 0.1) * 0.25;
    float major = line(vXZ, 1.0) * 0.6;
    float shadow = exp(-pow(length(c) / max(uGround.w * 0.8, 0.2), 2.0)) * 0.55;
    vec3 col = vec3(0.45, 0.55, 0.8);
    float a = max(minor, major) * fade * 0.6;
    frag = vec4(mix(col, vec3(0.0), shadow / max(a + shadow, 0.001)), clamp(a + shadow, 0.0, 0.85));
}"""
    }
}
