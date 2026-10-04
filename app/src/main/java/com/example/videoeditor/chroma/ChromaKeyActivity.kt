package com.example.videoeditor.chroma

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Surface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditor.databinding.ActivityChromaKeyBinding

/**
 * Standalone chroma-key tool: pick a green-screen video, preview it live through the
 * keying shader, adjust threshold/smoothing.
 *
 * Previously this screen only had the renderer + sliders with no way to actually get
 * a video frame into it — the SurfaceTexture callback was an empty stub. This version
 * wires a real source: MediaPlayer decodes the picked video and plays directly into
 * the renderer's SurfaceTexture via `MediaPlayer.setSurface`, which is the simplest
 * reliable way to get decoded video frames into an OpenGL texture on Android (no need
 * to hand-roll a MediaCodec decoder loop for this).
 */
class ChromaKeyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChromaKeyBinding
    private lateinit var renderer: ChromaKeyRenderer
    private var mediaPlayer: MediaPlayer? = null
    private var pendingSurfaceTexture: SurfaceTexture? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { playVideo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChromaKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderer = ChromaKeyRenderer { surfaceTexture ->
            // onSurfaceReady fires from the GL thread as soon as the renderer's
            // texture exists. We stash it and attach whatever video the user has
            // already picked (or will pick) via playVideo().
            pendingSurfaceTexture = surfaceTexture
        }

        binding.glSurfaceView.apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        binding.btnImportVideo.setOnClickListener {
            pickVideo.launch("video/*")
        }

        binding.thresholdSlider.addOnChangeListener { _, value, _ ->
            renderer.threshold = value
        }
        binding.smoothingSlider.addOnChangeListener { _, value, _ ->
            renderer.smoothing = value
        }
    }

    private fun playVideo(uri: Uri) {
        val surfaceTexture = pendingSurfaceTexture ?: run {
            // GLSurfaceView hasn't finished setting up its texture yet — try again
            // shortly rather than silently dropping the user's pick.
            binding.glSurfaceView.postDelayed({ playVideo(uri) }, 100)
            return
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, uri)
            setSurface(Surface(surfaceTexture))
            isLooping = true
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, what, extra ->
                binding.statusText.text = "Playback error ($what, $extra)"
                true
            }
            prepareAsync()
        }
        binding.statusText.text = "Playing — adjust threshold/smoothing to key out the green screen"
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
        mediaPlayer?.start()
    }

    override fun onPause() {
        binding.glSurfaceView.onPause()
        mediaPlayer?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}
