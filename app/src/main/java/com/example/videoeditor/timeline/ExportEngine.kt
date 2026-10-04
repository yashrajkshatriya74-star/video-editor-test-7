package com.example.videoeditor.timeline

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Turns a list of [TimelineClip]s (+ optional background [AudioTrack]s) into one exported
 * video file: applies per-clip mirroring, fade transitions, and a project-wide output
 * aspect ratio (horizontal/vertical/square), then hands it to Media3's Transformer.
 */
@UnstableApi
class ExportEngine(private val context: Context) {

    fun interface Listener {
        fun onResult(success: Boolean, message: String, outputFile: File?)
    }

    fun export(
        clips: List<TimelineClip>,
        audioTracks: List<AudioTrack>,
        orientation: OutputOrientation,
        outputFile: File,
        listener: Listener
    ) {
        if (clips.isEmpty()) {
            listener.onResult(false, "No clips to export", null)
            return
        }

        // Build the composition off the main thread, then hop back to the main
        // thread to start Transformer (it requires that).
        Thread {
            val editedItems = clips.map { clip -> buildEditedMediaItem(clip, orientation) }
            android.os.Handler(context.mainLooper).post {
                startTransformer(editedItems, audioTracks, outputFile, listener)
            }
        }.start()
    }

    private fun startTransformer(
        editedItems: List<EditedMediaItem>,
        audioTracks: List<AudioTrack>,
        outputFile: File,
        listener: Listener
    ) {
        val videoSequence = EditedMediaItemSequence(editedItems)

        val sequences = mutableListOf(videoSequence)
        if (audioTracks.isNotEmpty()) {
            // Background audio plays as its own parallel sequence, mixed under the video track.
            val audioItems = audioTracks.map { track ->
                EditedMediaItem.Builder(MediaItem.fromUri(track.uri))
                    .setRemoveVideo(true)
                    .build()
                // Per-track volume: apply via an audio processor/effect if your Media3 version
                // exposes one (e.g. a Volume audio processor in setEffects' audio list); wire
                // `track.volume` in there once you pin an exact Media3 version.
            }
            sequences.add(EditedMediaItemSequence(audioItems))
        }

        val composition = Composition.Builder(sequences).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    listener.onResult(true, "Exported to ${outputFile.name}", outputFile)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    listener.onResult(false, exception.message ?: "Export failed", null)
                }
            })
            .build()

        transformer.start(composition, outputFile.absolutePath)
    }

    private fun buildEditedMediaItem(clip: TimelineClip, orientation: OutputOrientation): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build()
            )
            .build()

        val effects = mutableListOf<Effect>()

        // --- Mirror (horizontal flip) ---
        if (clip.mirrored) {
            effects.add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(-1f, 1f)
                    .build()
            )
        }

        // --- Horizontal / vertical / square output ---
        effects.add(Presentation.createForAspectRatio(orientation.aspectRatio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))

        // --- Fade transition out of this clip ---
        if (clip.transitionOut == TransitionType.FADE) {
            val clipDurationUs = clip.trimmedDurationMs * 1000
            val fadeOutDurationUs = TransitionType.FADE.durationMs * 1000
            effects.add(
                FadeEffect(
                    fadeInDurationUs = 0,
                    fadeOutStartUs = (clipDurationUs - fadeOutDurationUs).coerceAtLeast(0),
                    fadeOutDurationUs = fadeOutDurationUs
                )
            )
        }

        // --- 3D model overlay, composited directly into this clip ---
        // Pre-renders the whole overlay animation to bitmaps BEFORE export starts —
        // this call is synchronous/slow, which is why buildEditedMediaItem is only
        // ever invoked from the background thread in export() above.
        clip.overlayModelUri?.let { modelUri ->
            val clipDurationUs = clip.trimmedDurationMs * 1000
            val frames = Model3DFrameRenderer(context).renderFrames(modelUri, clipDurationUs)
            effects.add(Model3DOverlayEffect(frames))
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(androidx.media3.transformer.Effects(emptyList(), effects))
            .build()
    }
}
