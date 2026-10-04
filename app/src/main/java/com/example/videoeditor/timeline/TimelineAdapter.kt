package com.example.videoeditor.timeline

import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.videoeditor.R
import java.util.Collections

class TimelineAdapter(
    private val clips: MutableList<TimelineClip>,
    private val onClipClicked: (TimelineClip, position: Int) -> Unit
) : RecyclerView.Adapter<TimelineAdapter.ClipViewHolder>() {

    inner class ClipViewHolder(view: FrameLayout) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.clipThumbnail)
        val durationLabel: TextView = view.findViewById(R.id.clipDuration)
        val flagsLabel: TextView = view.findViewById(R.id.clipFlags)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_clip, parent, false) as FrameLayout
        return ClipViewHolder(view)
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        val clip = clips[position]

        holder.durationLabel.text = formatMs(clip.trimmedDurationMs)
        val flags = buildList {
            if (clip.mirrored) add("Mirrored")
            if (clip.transitionOut != TransitionType.NONE) add(clip.transitionOut.label)
            if (clip.overlayModelUri != null) add("3D overlay")
        }
        holder.flagsLabel.text = flags.joinToString(" · ")

        // Cheap thumbnail: grab a frame at the trim-in point. For a production timeline,
        // cache these (a LruCache keyed by clip.id) instead of decoding on every bind.
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(holder.itemView.context, clip.uri)
                val frame = retriever.getFrameAtTime(clip.trimStartMs * 1000)
                holder.thumbnail.setImageBitmap(frame)
            }
        } catch (_: Exception) {
            holder.thumbnail.setImageDrawable(null)
        }

        holder.itemView.setOnClickListener { onClipClicked(clip, holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = clips.size

    fun moveClip(fromPosition: Int, toPosition: Int) {
        Collections.swap(clips, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun removeClip(position: Int) {
        clips.removeAt(position)
        notifyItemRemoved(position)
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    // MediaMetadataRetriever isn't AutoCloseable pre-API 29; small shim so `.use {}` above works everywhere.
    private inline fun MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }
}
