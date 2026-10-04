package com.example.videoeditor.timeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Renders a rigged/animated .glb model to a sequence of ARGB bitmaps, sampled at
 * [frameRateFps] across [durationUs]. The scene background is filled with a solid,
 * distinct key color (bright green) instead of relying on alpha transparency — the
 * previous version tried to use `SwapChain.CONFIG_TRANSPARENT`, which doesn't exist
 * in this Filament version and failed to compile. [Model3DOverlayEffect] then keys
 * out that green background the same way [com.example.videoeditor.chroma.ChromaKeyRenderer]
 * keys out a green screen — reusing a technique already proven to work in this project,
 * rather than depending on an uncertain transparency API.
 *
 * Note: rendering is synchronous and holds all frames as bitmaps in memory — fine for
 * short overlays (a few seconds), not for long ones. For longer overlays, write each
 * frame to a PNG in cache and stream them back instead.
 */
@UnstableApi
class Model3DFrameRenderer(private val context: Context) {

    data class RenderedFrame(val presentationTimeUs: Long, val bitmap: Bitmap)

    companion object {
        // Bright, saturated green — matches ChromaKeyRenderer's default key color,
        // chosen specifically so it's unlikely to appear in an actual 3D model's
        // material colors.
        const val KEY_COLOR_R = 0f
        const val KEY_COLOR_G = 1f
        const val KEY_COLOR_B = 0f
    }

    fun renderFrames(
        modelUri: Uri,
        durationUs: Long,
        frameRateFps: Int = 15,
        width: Int = 720,
        height: Int = 1280
    ): List<RenderedFrame> {
        val engine = Engine.create()
        val entityManager = EntityManager.get()
        val renderer = engine.createRenderer()
        val scene = engine.createScene()
        val view = engine.createView()
        val cameraEntity = entityManager.create()
        val camera: Camera = engine.createCamera(cameraEntity)

        // No special flags — a plain offscreen swap chain (0L = default). We don't need
        // real alpha transparency since Model3DOverlayEffect keys out the solid background
        // color. NOTE: calling this with just (width, height) and no flags argument is what
        // caused the "Type mismatch: inferred type is Int but Long was expected" error —
        // Kotlin matched a different 2-arg overload (width, flags: Long) instead of the
        // intended 3-arg one, since no height-only overload exists. Passing flags explicitly
        // forces the right overload.
        val swapChain = engine.createSwapChain(width, height, 0L)

        // Fill the background with a solid key color instead of leaving it undefined.
        val skybox = Skybox.Builder()
            .color(KEY_COLOR_R, KEY_COLOR_G, KEY_COLOR_B, 1f)
            .build(engine)
        scene.skybox = skybox

        view.scene = scene
        view.camera = camera
        view.viewport = Viewport(0, 0, width, height)

        val assetLoader = AssetLoader(engine, UbershaderProvider(engine), entityManager)
        val resourceLoader = ResourceLoader(engine)

        val buffer = readUriToBuffer(modelUri)
        val asset: FilamentAsset = assetLoader.createAsset(buffer)
            ?: throw IllegalStateException("Could not load model for overlay: $modelUri")
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)

        // Let Kotlin infer the animator's type rather than naming it explicitly —
        // the previous version guessed the type name wrong ("AnimatorInstance",
        // which doesn't exist here) and that single wrong name broke the whole file.
        val animator = asset.instance.animator
        val animationDurationSec = if (animator != null && animator.animationCount > 0) {
            animator.getAnimationDuration(0)
        } else 0f

        // Basic framing: center the camera on the asset's bounding box.
        val center = asset.boundingBox.center
        val halfExtent = asset.boundingBox.halfExtent
        val radius = maxOf(halfExtent[0], halfExtent[1], halfExtent[2]) * 3f
        camera.lookAt(
            center[0].toDouble(), center[1].toDouble(), (center[2] + radius).toDouble(),
            center[0].toDouble(), center[1].toDouble(), center[2].toDouble(),
            0.0, 1.0, 0.0
        )
        camera.setProjection(45.0, width.toDouble() / height, 0.1, radius * 10.0, Camera.Fov.VERTICAL)

        val frameCount = ((durationUs / 1_000_000.0) * frameRateFps).toInt().coerceAtLeast(1)
        val frames = mutableListOf<RenderedFrame>()

        for (i in 0 until frameCount) {
            val tSec = i.toFloat() / frameRateFps
            if (animator != null && animationDurationSec > 0) {
                animator.applyAnimation(0, tSec % animationDurationSec)
                animator.updateBoneMatrices()
            }

            renderer.beginFrame(swapChain, System.nanoTime())
            renderer.render(view)
            val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
            renderer.readPixels(
                0, 0, width, height,
                Texture.PixelBufferDescriptor(pixelBuffer, Texture.Format.RGBA, Texture.Type.UBYTE)
            )
            renderer.endFrame()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixelBuffer.rewind()
            bitmap.copyPixelsFromBuffer(pixelBuffer)

            val presentationTimeUs = (i.toLong() * 1_000_000L) / frameRateFps
            frames.add(RenderedFrame(presentationTimeUs, bitmap))
        }

        assetLoader.destroyAsset(asset)
        resourceLoader.destroy()
        assetLoader.destroy()
        engine.destroySkybox(skybox)
        engine.destroySwapChain(swapChain)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(cameraEntity)
        entityManager.destroy(cameraEntity)
        engine.destroy()

        return frames
    }

    private fun readUriToBuffer(uri: Uri): ByteBuffer {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
    }
}
