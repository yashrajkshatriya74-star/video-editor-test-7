package com.example.videoeditor.editor

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.videoeditor.databinding.ActivityEditorBinding
import java.io.File

/**
 * Core timeline editor.
 *
 * Responsibilities implemented here:
 *  - Load the picked video into a preview player
 *  - Let the user mark a split point on the timeline (basic seekbar-driven UI;
 *    swap in EditorTimelineView / RecyclerView-based multi-clip timeline as you build it out)
 *  - Use Media3 Transformer to physically split the file into two clips at that point
 *  - Export/trim as a new file
 *
 * This is intentionally the *simplest correct* implementation of split/trim so it's easy
 * to extend into a full multi-clip timeline (list of EditedMediaItem + EditedMediaItemSequence).
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var player: ExoPlayer
    private lateinit var sourceUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourceUri = Uri.parse(intent.getStringExtra(EXTRA_VIDEO_URI) ?: return)

        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player
        player.setMediaItem(MediaItem.fromUri(sourceUri))
        player.prepare()

        binding.btnSplitHere.setOnClickListener {
            val splitMs = player.currentPosition
            splitVideoAt(splitMs)
        }

        binding.btnTrim.setOnClickListener {
            val startMs = binding.trimRangeSlider.values[0].toLong()
            val endMs = binding.trimRangeSlider.values[1].toLong()
            trimVideo(startMs, endMs)
        }
    }

    /**
     * Splits [sourceUri] into two files at [splitMs] using Media3 Transformer's
     * clipping configuration (MediaItem.ClippingConfiguration), one export per half.
     */
    private fun splitVideoAt(splitMs: Long) {
        val outDir = File(cacheDir, "exports").apply { mkdirs() }

        val partA = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(splitMs)
                    .build()
            ).build()

        val partB = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(splitMs)
                    .build() // to end of clip
            ).build()

        exportClip(partA, File(outDir, "part_a.mp4"))
        exportClip(partB, File(outDir, "part_b.mp4"))
    }

    private fun trimVideo(startMs: Long, endMs: Long) {
        val outDir = File(cacheDir, "exports").apply { mkdirs() }
        val trimmed = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            ).build()
        exportClip(trimmed, File(outDir, "trimmed_${System.currentTimeMillis()}.mp4"))
    }

    private fun exportClip(mediaItem: MediaItem, outputFile: File) {
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects.EMPTY) // hook chroma-key / color effects in here later
            .build()

        val composition = Composition.Builder(
            EditedMediaItemSequence(listOf(editedItem))
        ).build()

        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    runOnUiThread {
                        binding.statusText.text = "Exported: ${outputFile.name}"
                    }
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    runOnUiThread {
                        binding.statusText.text = "Export failed: ${exception.message}"
                    }
                }
            })
            .build()

        transformer.start(composition, outputFile.absolutePath)
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }
}
