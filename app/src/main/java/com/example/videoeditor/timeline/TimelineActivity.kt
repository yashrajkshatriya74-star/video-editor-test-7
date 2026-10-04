package com.example.videoeditor.timeline

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.videoeditor.databinding.ActivityTimelineBinding
import java.io.File

@OptIn(UnstableApi::class)
class TimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding
    private lateinit var adapter: TimelineAdapter

    private val clips = mutableListOf<TimelineClip>()
    private val audioTracks = mutableListOf<AudioTrack>()
    private var orientation = OutputOrientation.LANDSCAPE

    private val pickVideos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { addClip(it) }
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            audioTracks.add(AudioTrack(uri = it))
            updateStatus("Added background audio (${audioTracks.size} track(s))")
        }
    }

    private var clipAwaitingOverlayPosition: Int = -1
    private val pickOverlayModel = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && clipAwaitingOverlayPosition in clips.indices) {
            clips[clipAwaitingOverlayPosition].overlayModelUri = uri
            adapter.notifyItemChanged(clipAwaitingOverlayPosition)
            updateStatus("3D model attached to clip ${clipAwaitingOverlayPosition + 1}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = TimelineAdapter(clips) { clip, position -> showClipOptionsDialog(clip, position) }
        binding.timelineRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.timelineRecycler.adapter = adapter
        attachDragToReorder()

        binding.btnAddClip.setOnClickListener { pickVideos.launch("video/*") }
        binding.btnAddAudio.setOnClickListener { pickAudio.launch("audio/*") }

        binding.orientationGroup.setOnCheckedChangeListener { _, checkedId ->
            orientation = when (checkedId) {
                binding.btnOrientationPortrait.id -> OutputOrientation.PORTRAIT
                binding.btnOrientationSquare.id -> OutputOrientation.SQUARE
                else -> OutputOrientation.LANDSCAPE
            }
        }

        binding.btnExport.setOnClickListener { exportProject() }

        // Right-side tool rail: chroma key and the 3D model viewer are reachable
        // directly from the main editor screen now, KineMaster-style, instead of
        // only from the separate hub (MainActivity).
        binding.btnChromaKeyTool.setOnClickListener {
            startActivity(Intent(this, com.example.videoeditor.chroma.ChromaKeyActivity::class.java))
        }
        binding.btn3DModelTool.setOnClickListener {
            startActivity(Intent(this, com.example.videoeditor.model3d.Model3DActivity::class.java))
        }
    }

    private fun addClip(uri: Uri) {
        val durationMs = readDurationMs(uri)
        clips.add(TimelineClip(uri = uri, sourceDurationMs = durationMs, trimStartMs = 0, trimEndMs = durationMs))
        adapter.notifyItemInserted(clips.size - 1)
        updateStatus("${clips.size} clip(s) on the timeline")
    }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    /** Tap a clip -> mirror toggle + transition-out picker + remove. Trim range reuses the
     *  RangeSlider pattern from EditorActivity if you want fine trim per clip here too. */
    private fun showClipOptionsDialog(clip: TimelineClip, position: Int) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val mirrorSwitch = Switch(this).apply {
            text = "Mirror (flip horizontally)"
            isChecked = clip.mirrored
        }
        container.addView(mirrorSwitch)

        val transitionLabel = android.widget.TextView(this).apply {
            text = "Transition after this clip"
            setPadding(0, 32, 0, 8)
        }
        container.addView(transitionLabel)

        val transitionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@TimelineActivity,
                android.R.layout.simple_spinner_dropdown_item,
                TransitionType.values().map { it.label }
            )
            setSelection(clip.transitionOut.ordinal)
        }
        container.addView(transitionSpinner)

        val overlayButton = android.widget.Button(this).apply {
            text = if (clip.overlayModelUri != null) "Change 3D model overlay" else "Attach 3D model overlay (.glb)"
            setOnClickListener {
                clipAwaitingOverlayPosition = position
                pickOverlayModel.launch("*/*")
            }
        }
        container.addView(overlayButton)

        AlertDialog.Builder(this)
            .setTitle("Clip options")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                clip.mirrored = mirrorSwitch.isChecked
                clip.transitionOut = TransitionType.values()[transitionSpinner.selectedItemPosition]
                adapter.notifyItemChanged(position)
            }
            .setNeutralButton("Remove clip") { _, _ -> adapter.removeClip(position) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun attachDragToReorder() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveClip(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                // Reserved for swipe-to-delete if you want it in addition to the dialog's Remove button.
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.timelineRecycler)
    }

    private fun exportProject() {
        val outDir = File(cacheDir, "exports").apply { mkdirs() }
        val outputFile = File(outDir, "project_${System.currentTimeMillis()}.mp4")

        updateStatus("Exporting…")
        ExportEngine(this).export(clips, audioTracks, orientation, outputFile) { success, message, _ ->
            runOnUiThread { updateStatus(message) }
        }
    }

    private fun updateStatus(text: String) {
        binding.statusText.text = text
    }
}
