package com.example.videoeditor.model3d

import android.net.Uri
import android.os.Bundle
import android.view.Choreographer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditor.databinding.ActivityModel3dBinding
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Imports and previews a 3D model (.glb/.gltf) so it can be composited into a
 * video project as an overlay layer (AR-style prop, watermark, animated logo, etc.).
 *
 * Uses Filament's ModelViewer, which wraps gltfio (glTF 2.0 loader) + Filament's
 * physically-based renderer. Filament outputs to a regular Android Surface, so its
 * frame can later be composited with the video's OpenGL pipeline (same approach as
 * ChromaKeyRenderer) to render 3D content directly on top of a video track.
 */
class Model3DActivity : AppCompatActivity() {

    companion object {
        init {
            // REQUIRED before constructing a ModelViewer or using anything in
            // com.google.android.filament.utils — this loads Filament's native
            // libraries. Missing this call is exactly what made the activity
            // crash immediately on open (UnsatisfiedLinkError) in the previous
            // build; it's easy to miss because the constructor gives no hint
            // that it depends on this being called first.
            Utils.init()
        }
    }

    private lateinit var binding: ActivityModel3dBinding
    private lateinit var modelViewer: ModelViewer
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            Choreographer.getInstance().postFrameCallback(this)
            modelViewer.render(frameTimeNanos)
        }
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadModel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModel3dBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelViewer = ModelViewer(binding.surfaceView)
        binding.surfaceView.setOnTouchListener { _, event -> modelViewer.onTouchEvent(event); true }

        binding.btnImportModel.setOnClickListener {
            // glTF/GLB filter — Android doesn't have a standard MIME type for these,
            // so accept common containers and filter by extension after pick.
            pickModel.launch("*/*")
        }

        // ModelViewer doesn't render on its own — it needs to be driven by a
        // per-frame callback. Without this, even with the crash fixed, the
        // screen would just stay blank/black after a model loads.
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        modelViewer.destroyModel()
        super.onDestroy()
    }

    private fun loadModel(uri: Uri) {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val buffer = FileInputStreamBuffer(pfd.fileDescriptor)
            when {
                uri.toString().endsWith(".glb", ignoreCase = true) ->
                    modelViewer.loadModelGlb(buffer)
                else ->
                    modelViewer.loadModelGltf(buffer) { uriStr -> resolveGltfResource(uriStr) }
            }
            modelViewer.transformToUnitCube()
        }
    }

    private fun resolveGltfResource(relativeUri: String): ByteBuffer? {
        // For .gltf (not .glb), external buffers/textures referenced by relative
        // path need to be resolved here (e.g. read sibling files from the same
        // folder the user picked, or from a bundled asset directory).
        return null
    }

    private fun FileInputStreamBuffer(fd: java.io.FileDescriptor): ByteBuffer {
        java.io.FileInputStream(fd).channel.use { channel ->
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }
}
