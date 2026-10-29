package com.aura.livewall.render

import android.opengl.Matrix
import android.view.Choreographer
import android.view.Surface
import com.aura.livewall.ai.Reaction
import com.aura.livewall.core.V3
import com.aura.livewall.data.ModelCache
import com.aura.livewall.data.Palette
import com.aura.livewall.data.Prefs
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Filament-рендер: чёрный OLED-фон с градиентом-скайбоксом, .glb через gltfio,
 * LRU живых ассетов (быстрое переключение), spring bones, контроллер персонажа.
 * Все вызовы — строго из main-потока.
 */
class WallpaperRenderer(private val prefs: Prefs) {

    companion object {
        init { Filament.init(); Gltfio.init() }
        const val CAM_Y = 0.9f
        const val CAM_Z = 3.2f
        const val FOV = 30.0
        const val MODEL_H = 1.6f
        const val SKY_N = 32
        private val TAN_HALF = tan(Math.toRadians(FOV / 2)).toFloat()
    }

    private class Model(val path: String, val buf: ByteBuffer, val asset: FilamentAsset) {
        var ready = false
        var spring: SpringBones? = null
        var ctrl: CharacterController? = null
        var animator: Animator? = null
        var cx = 0f; var cy = 0f; var cz = 0f; var hx = 0f; var hy = 0f
    }

    private val em = EntityManager.get()
    private val engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view = engine.createView()
    private val camEntity = em.create()
    private val camera = engine.createCamera(camEntity)
    private var swapChain: SwapChain? = null

    private val materials = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materials, em)
    private val resLoader = ResourceLoader(engine)
    private val choreographer = Choreographer.getInstance()

    private val skyTex: Texture
    private val skybox: Skybox
    private val ibl: IndirectLight
    private val lights = IntArray(2)

    private val models = LinkedHashMap<String, Model>(4, 0.75f, true)
    private val pending = ArrayList<Model>()
    private var current: Model? = null
    private var wanted: Model? = null

    private var vw = 0; private var vh = 0; private var aspect = 1f
    private var scroll = 0f
    private var mTX = 0f; private var mBase = 0f; private var mH = 0f; private var mHW = 0f
    private var touchW: V3? = null
    private var lookX = 0f; private var lookY = 0f

    private val accent = floatArrayOf(124f, 58f, 237f)
    private var phase = 0f
    private val lastSky = intArrayOf(-1, -1, -1)
    private var skyAt = 0L

    private var running = false
    private var lastNs = 0L

    init {
        renderer.setClearOptions(Renderer.ClearOptions().apply { clear = true; clearColor = floatArrayOf(0f, 0f, 0f, 1f) })
        view.setScene(scene); view.setCamera(camera)
        view.setBlendMode(View.BlendMode.OPAQUE)
        view.setDithering(View.Dithering.TEMPORAL)
        view.setAntiAliasing(View.AntiAliasing.FXAA)

        skyTex = Texture.Builder().width(SKY_N).height(SKY_N).levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP).format(Texture.InternalFormat.SRGB8_A8).build(engine)
        skybox = Skybox.Builder().environment(skyTex).showSun(false).build(engine)
        scene.setSkybox(skybox)
        uploadSky(0, 0, 0)

        ibl = IndirectLight.Builder().irradiance(1, floatArrayOf(0.45f, 0.45f, 0.5f)).intensity(25_000f).build(engine)
        scene.setIndirectLight(ibl)

        lights[0] = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, 0.97f, 0.94f).intensity(75_000f)
            .direction(0.25f, -0.35f, -1f).castShadows(false).build(engine, lights[0])
        lights[1] = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(0.5f, 0.2f, 1f).intensity(30_000f)
            .direction(-0.6f, -0.2f, 1f).castShadows(false).build(engine, lights[1])
        scene.addEntity(lights[0]); scene.addEntity(lights[1])

        camera.setExposure(16f, 1f / 125f, 100f)
    }

    // ---------------- surface ----------------
    fun attach(surface: Surface) { detach(); swapChain = engine.createSwapChain(surface) }

    fun detach() {
        swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
        swapChain = null
    }

    fun resize(w: Int, h: Int) {
        vw = w; vh = h; aspect = w.toFloat() / max(1, h)
        view.setViewport(Viewport(0, 0, w, h))
        camera.setProjection(FOV, aspect.toDouble(), 0.1, 20.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, CAM_Y.toDouble(), CAM_Z.toDouble(), 0.0, CAM_Y.toDouble(), 0.0, 0.0, 1.0, 0.0)
    }

    // ---------------- models ----------------
    fun showModel(path: String) {
        if (current?.path == path && wanted == null) return
        val m = models[path] ?: createModel(path) ?: return
        if (m.ready) { activate(m); wanted = null } else wanted = m
        trim()
    }

    private fun createModel(path: String): Model? {
        val buf = runCatching { ModelCache.get(path) }.getOrNull() ?: return null
        val asset = assetLoader.createAsset(buf) ?: return null
        val m = Model(path, buf, asset)
        val b = asset.boundingBox
        m.cx = b.center[0]; m.cy = b.center[1]; m.cz = b.center[2]
        m.hx = b.halfExtent[0]; m.hy = b.halfExtent[1]
        models[path] = m
        resLoader.asyncBeginLoad(asset)
        pending.add(m)
        return m
    }

    private fun finish(m: Model) {
        m.asset.releaseSourceData()
        val rm = engine.renderableManager
        for (e in m.asset.renderableEntities) {
            val i = rm.getInstance(e)
            rm.setCulling(i, false)        // скин-меши + spring bones: bbox не доверяем
            rm.setCastShadows(i, false)
        }
        m.animator = m.asset.instance.animator
        m.spring = SpringBones(engine, m.asset).also { it.build() }
        m.ctrl = CharacterController(engine, m.asset) { s -> m.spring?.kick(s) }
        m.ready = true
        if (wanted === m) { activate(m); wanted = null }
    }

    private fun activate(m: Model) {
        current?.let { scene.removeEntities(it.asset.entities) }
        current = m
        scene.addEntities(m.asset.entities)
        applyTransform(true)
    }

    private fun trim() {
        if (models.size <= 3) return
        val it = models.entries.iterator()
        while (models.size > 3 && it.hasNext()) {
            val m = it.next().value
            if (m === current || m === wanted || m in pending) continue
            it.remove()
            scene.removeEntities(m.asset.entities)
            assetLoader.destroyAsset(m.asset)
        }
    }

    fun applyPrefs() = applyTransform(false)

    fun setScroll(x: Float) { val s = x - 0.5f; if (s != scroll) { scroll = s; applyTransform(false) } }

    private fun applyTransform(reset: Boolean) {
        val m = current ?: return
        val s = (MODEL_H * prefs.scale) / (2f * m.hy).coerceAtLeast(1e-4f)
        val tx = scroll * 0.35f + prefs.offX
        val ty = 0.1f + prefs.offY
        val mat = FloatArray(16)
        Matrix.setIdentityM(mat, 0)
        Matrix.translateM(mat, 0, tx, ty, 0f)
        Matrix.rotateM(mat, 0, prefs.yaw, 0f, 1f, 0f)
        Matrix.scaleM(mat, 0, s, s, s)
        Matrix.translateM(mat, 0, -m.cx, -(m.cy - m.hy), -m.cz)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(m.asset.root), mat)
        mTX = tx; mBase = ty; mH = MODEL_H * prefs.scale; mHW = m.hx * s
        if (reset) m.spring?.reset()
    }

    // ---------------- input / reactions ----------------
    private fun toWorld(px: Float, py: Float): V3 {
        val nx = px / max(1, vw) * 2f - 1f
        val ny = 1f - py / max(1, vh) * 2f
        return V3(nx * TAN_HALF * aspect * CAM_Z, CAM_Y + ny * TAN_HALF * CAM_Z, 0f)
    }

    fun isOnModel(px: Float, py: Float): Boolean {
        if (current == null || vw == 0) return false
        val p = toWorld(px, py)
        return abs(p.x - mTX) <= mHW * 1.1f + 0.05f && p.y >= mBase - 0.05f && p.y <= mBase + mH + 0.05f
    }

    fun touch(px: Float, py: Float) {
        touchW = toWorld(px, py)
        lookX = (px / max(1, vw) * 2f - 1f).coerceIn(-1f, 1f)
        lookY = (1f - py / max(1, vh) * 2f).coerceIn(-1f, 1f)
    }

    fun touchUp() { touchW = null; lookX = 0f; lookY = 0f }
    fun kick() { current?.spring?.kick(1f) }

    fun react(r: Reaction) {
        val c = current?.ctrl ?: return
        c.setEmotion(r.emotion); c.action(r.action)
        if (r.say.isNotBlank()) c.speak(r.say.length * 60L)
    }

    // ---------------- loop ----------------
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            val gap = 1_000_000_000L / prefs.fps.coerceIn(15, 144)
            if (lastNs != 0L && ns - lastNs < gap - 2_500_000L) return
            val dt = if (lastNs == 0L) 0.016f else ((ns - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
            lastNs = ns
            update(dt, ns)
            val sc = swapChain ?: return
            if (renderer.beginFrame(sc, ns)) { renderer.render(view); renderer.endFrame() }
        }
    }

    fun setRunning(on: Boolean) {
        if (on == running) return
        running = on; lastNs = 0L
        if (on) choreographer.postFrameCallback(frameCb) else choreographer.removeFrameCallback(frameCb)
    }

    private fun update(dt: Float, ns: Long) {
        if (pending.isNotEmpty()) {
            resLoader.asyncUpdateLoad()
            if (resLoader.asyncGetLoadProgress() >= 1f) {
                val done = pending.toList(); pending.clear()
                done.forEach { finish(it) }
                resLoader.evictResourceData()
            }
        }
        animatePalette(dt, ns)
        val m = current ?: return
        m.ctrl?.update(dt, lookX, lookY)
        m.spring?.step(dt, prefs.spring, touchW)
        m.animator?.updateBoneMatrices()
    }

    // ---------------- gradient (OLED) ----------------
    private fun animatePalette(dt: Float, ns: Long) {
        val target = Palette.entries[prefs.palette.coerceIn(0, Palette.entries.size - 1)].rgb
        val k = min(1f, dt * 1.4f)
        for (i in 0..2) accent[i] += (target[i] - accent[i]) * k            // плавное перетекание палитр
        phase += dt * prefs.speed * (2f * PI.toFloat())
        val mix = (0.5f - 0.5f * cos(phase)) * prefs.intensity              // чёрный ↔ акцент
        val r = (accent[0] * mix).roundToInt(); val g = (accent[1] * mix).roundToInt(); val b = (accent[2] * mix).roundToInt()
        if ((r != lastSky[0] || g != lastSky[1] || b != lastSky[2]) && ns - skyAt > 60_000_000L) {
            uploadSky(r, g, b); lastSky[0] = r; lastSky[1] = g; lastSky[2] = b; skyAt = ns
        }
        val lm = engine.lightManager
        lm.setColor(lm.getInstance(lights[1]), (accent[0] / 255f).pow(2.2f) * 1.6f, (accent[1] / 255f).pow(2.2f) * 1.6f, (accent[2] / 255f).pow(2.2f) * 1.6f)
    }

    /** Вертикальный градиент в кубмапе: низ строго #000000, верх — текущий акцент. */
    private fun uploadSky(r: Int, g: Int, b: Int) {
        val n = SKY_N
        val buf = ByteBuffer.allocateDirect(6 * n * n * 4).order(ByteOrder.nativeOrder())
        for (face in 0 until 6) for (row in 0 until n) {
            val y = when (face) {
                2 -> 1f
                3 -> -1f
                else -> { val yy = 1f - 2f * (row + 0.5f) / n; yy / sqrt(1f + yy * yy) }
            }
            val x = ((y + 0.30f) / 0.62f).coerceIn(0f, 1f)
            val t = (x * x * (3f - 2f * x)).pow(1.4f)
            val rr = (r * t).roundToInt().toByte(); val gg = (g * t).roundToInt().toByte(); val bb = (b * t).roundToInt().toByte()
            repeat(n) { buf.put(rr); buf.put(gg); buf.put(bb); buf.put(0xFF.toByte()) }
        }
        buf.rewind()
        skyTex.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE),
            IntArray(6) { it * n * n * 4 }
        )
    }

    // ---------------- teardown ----------------
    fun release() {
        setRunning(false); detach()
        models.values.forEach { scene.removeEntities(it.asset.entities); assetLoader.destroyAsset(it.asset) }
        models.clear(); pending.clear(); current = null; wanted = null
        resLoader.destroy(); assetLoader.destroy()
        materials.destroyMaterials(); materials.destroy()
        lights.forEach { engine.lightManager.destroy(it); em.destroy(it) }
        engine.destroyIndirectLight(ibl); engine.destroySkybox(skybox); engine.destroyTexture(skyTex)
        engine.destroyView(view); engine.destroyScene(scene); engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(camEntity); em.destroy(camEntity)
        engine.destroy()
    }
}
