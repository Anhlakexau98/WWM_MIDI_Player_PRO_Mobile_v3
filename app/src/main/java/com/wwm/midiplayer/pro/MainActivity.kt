package com.wwm.midiplayer.pro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val folderMidiFiles = mutableListOf<Pair<String, Uri>>()

    // 1. Bộ chọn Thư mục chứa MIDI (Chọn 1 lần, quét toàn bộ file .mid trong thư mục)
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                getSharedPreferences("wewish_app_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("saved_midi_folder_uri", treeUri.toString())
                    .apply()
            } catch (_: Exception) {}
            scanMidiFolderUri(treeUri)
        }
    }

    // 2. Bộ chọn File .APK cập nhật trực tiếp ngay trong ứng dụng (Không mất vị trí nút)
    private val apkUpdatePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            installUpdateFromUri(uri)
        }
    }

    // 3. Bộ chọn file MIDI lẻ dự phòng
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    uris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                uris.add(data.data!!)
            }

            if (uris.isNotEmpty()) {
                uris.forEach { u ->
                    val name = getFileNameFromUri(u)
                    if (name.lowercase().endsWith(".mid") || name.lowercase().endsWith(".midi")) {
                        folderMidiFiles.add(0, Pair(name, u))
                    }
                }
                sendFolderListToWeb("Thư mục đã chọn")
                loadMidiUriDirectly(uris.first())
            }
            filePathCallback?.onReceiveValue(uris.toTypedArray())
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = "Bai_Nhac.mid"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIdx)
                }
            }
        } catch (_: Exception) {}
        return fileName
    }

    private fun scanMidiFolderUri(treeUri: Uri) {
        try {
            val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return
            val folderName = dir.name ?: "Thư mục MIDI"
            folderMidiFiles.clear()
            dir.listFiles().forEach { doc ->
                val n = doc.name ?: ""
                if (doc.isFile && (n.lowercase().endsWith(".mid") || n.lowercase().endsWith(".midi"))) {
                    folderMidiFiles.add(Pair(n, doc.uri))
                }
            }
            folderMidiFiles.sortBy { it.first.lowercase() }
            sendFolderListToWeb(folderName)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendFolderListToWeb(folderName: String) {
        val arr = JSONArray()
        folderMidiFiles.forEachIndexed { index, pair ->
            val obj = JSONObject()
            obj.put("index", index)
            obj.put("name", pair.first)
            arr.put(obj)
        }
        val safeFolder = folderName.replace("'", "\'")
        val safeJson = arr.toString().replace("\", "\\").replace("'", "\'")
        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onNativeFolderScanned) window.onNativeFolderScanned('$safeFolder', '$safeJson');",
                null
            )
        }
    }

    private fun loadMidiUriDirectly(uri: Uri) {
        try {
            val fileName = getFileNameFromUri(uri)
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val parsedNotes = TouchPointManager.parseStandardMidiBytes(bytes)
            if (parsedNotes.isNotEmpty()) {
                TouchPointManager.currentSongTitle = fileName
                TouchPointManager.currentSongRawNotes = parsedNotes
                TouchPointManager.rebuildActiveSchedule()
                val chordCount = TouchPointManager.currentSongSchedule.size
                val shift = TouchPointManager.lastAutoTransposeSemitones
                val safeName = fileName.replace("'", "\'")
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.onNativeMidiLoaded) window.onNativeMidiLoaded('$safeName', ${parsedNotes.size}, $chordCount, $shift);",
                        null
                    )
                    Toast.makeText(
                        this,
                        "✅ Đã nạp '$fileName': ${parsedNotes.size} nốt ($chordCount nhịp, dịch $shift bán cung)!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "⚠️ File '$fileName' không đúng định dạng MIDI (.mid)!", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun installUpdateFromUri(uri: Uri) {
        try {
            // Tự động sao lưu toàn bộ vị trí nút trước khi cập nhật
            TouchPointManager.exportAllLayoutsJson(this)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    this,
                    "Vui lòng cho phép quyền 'Cài đặt ứng dụng không xác định' để cập nhật trực tiếp!",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return
            }

            val apkCacheFile = File(cacheDir, "wewish_update.apk")
            contentResolver.openInputStream(uri)?.use { input ->
                apkCacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkCacheFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi mở bản cập nhật: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                filePickerLauncher.launch(intent)
                return true
            }
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidNative")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        // Tự động cập nhật trạng thái quyền Trợ năng & Cửa sổ nổi lên giao diện mỗi khi quay lại app
        webView.postDelayed({
            val accOn = (MidiAutoTapService.instance != null)
            val overlayOn = Settings.canDrawOverlays(this)
            webView.evaluateJavascript(
                "if(window.onPermissionStatusUpdate) window.onPermissionStatusUpdate($accOn, $overlayOn);",
                null
            )
        }, 300)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun pickMidiFolder() {
            runOnUiThread {
                folderPickerLauncher.launch(null)
            }
        }

        @JavascriptInterface
        fun loadSavedFolderIfAvailable() {
            runOnUiThread {
                val savedUriStr = getSharedPreferences("wewish_app_prefs", Context.MODE_PRIVATE)
                    .getString("saved_midi_folder_uri", null)
                if (savedUriStr != null) {
                    try {
                        scanMidiFolderUri(Uri.parse(savedUriStr))
                    } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun loadSongFromFolderList(index: Int) {
            runOnUiThread {
                val item = folderMidiFiles.getOrNull(index)
                if (item != null) {
                    loadMidiUriDirectly(item.second)
                } else {
                    Toast.makeText(this@MainActivity, "Đã nạp bài mặc định vào trình phát!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun startFloatingOverlay() {
            runOnUiThread {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác'!",
                        Toast.LENGTH_LONG
                    ).show()
                    openOverlaySettings()
                } else {
                    val intent = Intent(this@MainActivity, MidiOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "✅ Đã hiện thanh điều khiển nổi!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun stopFloatingOverlay() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, MidiOverlayService::class.java))
            }
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        @JavascriptInterface
        fun openOverlaySettings() {
            runOnUiThread {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun pickApkFileForUpdate() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.android.package-archive"
                }
                apkUpdatePickerLauncher.launch(intent)
            }
        }

        @JavascriptInterface
        fun openExternalUpdateLink(url: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Không thể mở đường dẫn: $url", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun backupKeyPositions(): String {
            return TouchPointManager.exportAllLayoutsJson(this@MainActivity)
        }

        @JavascriptInterface
        fun restoreKeyPositions(jsonStr: String): Boolean {
            val ok = TouchPointManager.importAllLayoutsJson(this@MainActivity, jsonStr)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this@MainActivity, "✅ Đã khôi phục vị trí 21/36 nút cảm ứng thành công!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "⚠️ Dữ liệu vị trí nút không hợp lệ!", Toast.LENGTH_SHORT).show()
                }
            }
            return ok
        }

        @JavascriptInterface
        fun resetDefaultKeyPositions() {
            runOnUiThread {
                getSharedPreferences("wewish_touch_layout_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
                Toast.makeText(this@MainActivity, "Đã đặt lại vị trí nút về mặc định!", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun updateMidiSchedule(jsonSchedule: String, speedPercent: Int, presetMode: Int) {
            try {
                TouchPointManager.playbackSpeedMultiplier = (speedPercent / 100f).coerceIn(0.1f, 3.0f)
                TouchPointManager.currentPresetMode = presetMode
                val arr = JSONArray(jsonSchedule)
                val rawList = mutableListOf<RawMidiNote>()
                var currentTimeMs = 0L
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val delayMs = obj.optLong("delayMs", 260L)
                    val durationMs = obj.optLong("durationMs", 40L)
                    val notesArr = obj.getJSONArray("midiNotes")
                    for (j in 0 until notesArr.length()) {
                        val midiNote = notesArr.getInt(j)
                        rawList.add(RawMidiNote(currentTimeMs, durationMs, midiNote, 0, 0, 100))
                    }
                    currentTimeMs += delayMs
                }
                if (rawList.isNotEmpty()) {
                    TouchPointManager.currentSongRawNotes = rawList
                    TouchPointManager.rebuildActiveSchedule()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun updateSmartOptions(
            presetMode: Int,
            adaptive: Boolean,
            simplify: Boolean,
            noteOff: Boolean,
            loop: Boolean,
            speedPercent: Int
        ): String {
            TouchPointManager.currentPresetMode = presetMode
            TouchPointManager.adaptiveKeyMapping = adaptive
            TouchPointManager.simplifyChords = simplify
            TouchPointManager.respectNoteOff = noteOff
            TouchPointManager.loopPlayback = loop
            TouchPointManager.playbackSpeedMultiplier = (speedPercent / 100f).coerceIn(0.1f, 3.0f)
            TouchPointManager.rebuildActiveSchedule()

            val obj = JSONObject()
            obj.put("songTitle", TouchPointManager.currentSongTitle)
            obj.put("rawNotes", TouchPointManager.currentSongRawNotes.size)
            obj.put("chords", TouchPointManager.currentSongSchedule.size)
            obj.put("transpose", TouchPointManager.lastAutoTransposeSemitones)
            return obj.toString()
        }

        @JavascriptInterface
        fun loadPresetSongJson(title: String, notesJson: String): String {
            try {
                val arr = JSONArray(notesJson)
                val list = mutableListOf<RawMidiNote>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        RawMidiNote(
                            startMs = o.getLong("startMs"),
                            durationMs = o.optLong("durationMs", 240L),
                            midiNote = o.getInt("midiNote"),
                            channel = 0,
                            trackIndex = 0,
                            velocity = 100
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    TouchPointManager.currentSongTitle = title
                    TouchPointManager.currentSongRawNotes = list
                    TouchPointManager.rebuildActiveSchedule()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val res = JSONObject()
            res.put("chords", TouchPointManager.currentSongSchedule.size)
            res.put("transpose", TouchPointManager.lastAutoTransposeSemitones)
            return res.toString()
        }
    }
}
