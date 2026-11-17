package com.example.modconverter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var addonNameInput: EditText
    private lateinit var selectJarButton: Button
    private lateinit var shareAddonButton: Button
    private lateinit var logTextView: TextView
    private var lastConvertedFile: File? = null

    private val pickJarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processSelectedJar(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addonNameInput = findViewById(R.id.addonNameInput)
        selectJarButton = findViewById(R.id.selectJarButton)
        shareAddonButton = findViewById(R.id.shareAddonButton)
        logTextView = findViewById(R.id.logTextView)

        selectJarButton.setOnClickListener {
            pickJarLauncher.launch("*/*")
        }

        shareAddonButton.setOnClickListener {
            lastConvertedFile?.let { file -> shareAddon(file) }
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            logTextView.append("
$message")
        }
    }

    private fun processSelectedJar(uri: Uri) {
        val addonName = addonNameInput.text.toString().ifBlank { "PortedJavaMod" }
        logTextView.text = "Starting conversion for: $addonName..."
        shareAddonButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open selected file")

                val outputFile = File(cacheDir, "${addonName.replace(" ", "_")}.mcaddon")
                if (outputFile.exists()) outputFile.delete()

                val stats = ModConverter.convertJarToMcAddon(
                    jarInputStream = inputStream,
                    outputMcAddonFile = outputFile,
                    addonName = addonName,
                    logger = { msg -> appendLog(msg) }
                )

                withContext(Dispatchers.Main) {
                    lastConvertedFile = outputFile
                    shareAddonButton.isEnabled = true
                    appendLog("
=== CONVERSION COMPLETE ===")
                    appendLog("Mod ID detected: ${stats.modId}")
                    appendLog("Item Textures ported: ${stats.itemTextures}")
                    appendLog("Block Textures ported: ${stats.blockTextures}")
                    appendLog("Bedrock Items generated: ${stats.generatedItems}")
                    appendLog("Bedrock Blocks generated: ${stats.generatedBlocks}")
                    appendLog("Output saved: ${outputFile.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("
[ERROR] ${e.message}")
                }
            }
        }
    }

    private fun shareAddon(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export or Open .mcaddon"))
    }
}
