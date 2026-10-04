package com.example.videoeditor.timeline

import android.net.Uri
import java.util.UUID

/** One clip on the video track. */
data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    var sourceDurationMs: Long = 0,   // full duration of the underlying file
    var trimStartMs: Long = 0,
    var trimEndMs: Long = 0,          // 0 == "not yet resolved", set to sourceDurationMs on load
    var mirrored: Boolean = false,
    var transitionOut: TransitionType = TransitionType.NONE,
    var overlayModelUri: Uri? = null   // optional 3D model composited on top of this clip
) {
    val trimmedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)
}

/**
 * Transitions actually implemented here are fade-based (fade the outgoing clip to black,
 * fade the incoming clip in from black). A true cross-dissolve — blending two overlapping
 * decoded video streams into one frame — needs a custom multi-input compositor that goes
 * beyond Media3 Transformer's sequential-clip model; NONE/FADE below is what's real.
 */
enum class TransitionType(val label: String, val durationMs: Long) {
    NONE("Cut", 0),
    FADE("Fade to black", 400)
}

enum class OutputOrientation(val label: String, val aspectRatio: Float) {
    LANDSCAPE("Horizontal (16:9)", 16f / 9f),
    PORTRAIT("Vertical (9:16)", 9f / 16f),
    SQUARE("Square (1:1)", 1f)
}

/** A single background-audio track (music/voiceover) mixed under the whole video sequence. */
data class AudioTrack(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    var volume: Float = 1.0f
)
