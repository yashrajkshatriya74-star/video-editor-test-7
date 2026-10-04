package com.example.videoeditor.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.videoeditor.chroma.ChromaKeyActivity
import com.example.videoeditor.databinding.ActivityMainBinding
import com.example.videoeditor.model3d.Model3DActivity
import com.example.videoeditor.timeline.TimelineActivity

/**
 * Entry point / hub. "New Project" opens the full multi-clip timeline editor
 * (split, mirror, transitions, horizontal/vertical export, add video/audio).
 * The other two buttons jump straight into standalone tools.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNewProject.setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }

        binding.btnChromaKey.setOnClickListener {
            startActivity(Intent(this, ChromaKeyActivity::class.java))
        }

        binding.btnImport3d.setOnClickListener {
            startActivity(Intent(this, Model3DActivity::class.java))
        }
    }
}
