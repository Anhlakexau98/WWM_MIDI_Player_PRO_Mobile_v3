package com.wwm.midiplayer.pro

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

data class KeyCoordinate(
    val noteIndex: Int,
    val label: String,
    val octaveRow: Int,
    val isSharp: Boolean,
    var x: Float,
    var y: Float
)

data class RawMidiNote(
    val startMs: Long,
    val durationMs: Long,
    val midiNote: Int,
    val channel: Int,
    val trackIndex: Int,
    val velocity: Int
)

data class ScheduledChord(
    val timeMs: Long = 0L,
    val delayAfterMs: Long = 260L,
    val keyIndices: List<Int> = emptyList(),
    val durationMs: Long = 45L
) {
    val delayMs: Long get() = delayAfterMs

    constructor(delayMs: Long, keyIndices: List<Int>, durationMs: Long) : this(
        timeMs = 0L,
        delayAfterMs = delayMs,
        keyIndices = keyIndices,
        durationMs = durationMs
    )
}

object TouchPointManager {
    private const val PREF_NAME = "wewish_touch_layout_prefs"
    private const val KEY_LAYOUT_21 = "layout_21_keys"
    private const val KEY_LAYOUT_36_STAGGERED = "layout_36_staggered"
    private const val KEY_LAYOUT_36_LINEAR = "layout_36_linear"
    private const val BACKUP_FILENAME = "WeWish_MIDI_KeyBackup.json"

    const val REF_LANDSCAPE_WIDTH = 2670f
    const val REF_LANDSCAPE_HEIGHT = 1200f

    private val COLS_7_X = floatArrayOf(441f, 739f, 1037f, 1335f, 1633f, 1931f, 2229f)
    private val ROWS_3_Y = floatArrayOf(648f, 834f, 1020f)

    val NOTE_LABELS_21 = listOf(
        "1̇", "2̇", "3̇", "4̇", "5̇", "6̇", "7̇",
        "1",  "2",  "3",  "4",  "5",  "6",  "7",
        "1̣", "2̣", "3̣", "4̣", "5̣", "6̣", "7̣"
    )

    private val SEMITONE_NAMES = listOf("1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "#6", "7")
    private val NATURAL_COL_MAP = mapOf(0 to 0, 2 to 1, 4 to 2, 5 to 3, 7 to 4, 9 to 5, 11 to 6)
    private val SHARP_COL_MAP = mapOf(1 to 0.5f, 3 to 1.5f, 6 to 3.5f, 8 to 4.5f, 10 to 5.5f)

    private val WHITE_PITCH_CLASSES = setOf(0, 2, 4, 5, 7, 9, 11)
    private val PITCH_CLASS_TO_WHITE_COL = intArrayOf(
        0, 0, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6
    )

    @Volatile
    var appLanguage: String = "vi"

    @Volatile
    var currentSongTitle: String = "Lan Đình Tự (Lanting Xu)"

    @Volatile
    var currentSongRawNotes: List<RawMidiNote> = buildBuiltinLantingXu()

    @Volatile
    var currentSongSchedule: List<ScheduledChord> = emptyList()

    @Volatile
    var playbackSpeedMultiplier: Float = 1.0f

    @Volatile
    var currentPresetMode: Int = 0 // 0 = 21 phím, 1 = 36 phím lệch #, 2 = 36 phím lưới

    @Volatile
    var adaptiveKeyMapping: Boolean = true

    @Volatile
    var simplifyChords: Boolean = false

    @Volatile
    var respectNoteOff: Boolean = false

    @Volatile
    var loopPlayback: Boolean = false

    @Volatile
    var lastAutoTransposeSemitones: Int = 0

    init {
        rebuildActiveSchedule()
    }

    fun mapMidiNoteToKeyIndex(midiNote: Int, master36: Boolean, adaptive: Boolean): Int? {
        val shift = if (adaptive) lastAutoTransposeSemitones else 0
        return mapSingleNoteSmart(midiNote + shift, master36)
    }

    @Synchronized
    fun rebuildActiveSchedule() {
        val raw = currentSongRawNotes.filter { it.channel != 9 }
        if (raw.isEmpty()) {
            currentSongSchedule = emptyList()
            return
        }

        val master36 = currentPresetMode > 0
        val bestShift = if (adaptiveKeyMapping) {
            findOptimalTranspose(raw, master36)
        } else {
            0
        }
        lastAutoTransposeSemitones = bestShift

        val sorted = raw.sortedBy { it.startMs }
        val buckets = mutableListOf<MutableList<RawMidiNote>>()
        for (note in sorted) {
            if (buckets.isEmpty()) {
                buckets.add(mutableListOf(note))
            } else {
                val lastBucket = buckets.last()
                val bucketStart = lastBucket.first().startMs
                if (note.startMs - bucketStart <= 25L) {
                    lastBucket.add(note)
                } else {
                    buckets.add(mutableListOf(note))
                }
            }
        }

        val result = mutableListOf<ScheduledChord>()
        for (i in buckets.indices) {
            val bucket = buckets[i]
            val chordTimeMs = bucket.first().startMs
            val nextTimeMs = if (i + 1 < buckets.size) buckets[i + 1].first().startMs else (chordTimeMs + 450L)
            val stepDelay = (nextTimeMs - chordTimeMs).coerceAtLeast(35L)

            // 1. Tính năng Đơn giản hóa hợp âm (Chords Simplification):
            // Khi bật: rút gọn hợp âm phức tạp thành 1 nốt giai điệu chính (cao nhất) để chơi mượt và dễ hơn
            val filteredNotes = if (simplifyChords) {
                val highest = bucket.maxByOrNull { it.midiNote }!!
                listOf(highest)
            } else {
                bucket.sortedByDescending { it.midiNote }.take(4)
            }

            val keyIndices = mutableListOf<Int>()
            var maxNoteDur = 40L
            for (n in filteredNotes) {
                val mappedIdx = mapSingleNoteSmart(n.midiNote + bestShift, master36)
                if (mappedIdx != null && !keyIndices.contains(mappedIdx)) {
                    keyIndices.add(mappedIdx)
                }
                maxNoteDur = maxOf(maxNoteDur, n.durationMs)
            }

            // 2. Tính năng Ngắt nhịp theo nốt tắt (Respect Note Off Events):
            // - Khi bật: giữ phím đúng bằng thời lượng nốt trong file MIDI (dừng khi file MIDI chỉ định Note Off)
            // - Khi tắt: gảy nốt ngắn gọn (38ms) để nốt tự ngân tự nhiên tới nốt kế tiếp
            val tapDuration = if (respectNoteOff) {
                maxNoteDur.coerceIn(45L, (stepDelay - 12L).coerceAtLeast(45L)).coerceAtMost(1800L)
            } else {
                38L
            }

            if (keyIndices.isNotEmpty()) {
                result.add(
                    ScheduledChord(
                        timeMs = chordTimeMs,
                        delayAfterMs = stepDelay,
                        keyIndices = keyIndices,
                        durationMs = tapDuration
                    )
                )
            }
        }
        currentSongSchedule = result
    }

    private fun findOptimalTranspose(notes: List<RawMidiNote>, master36: Boolean): Int {
        var bestShift = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (shift in -24..24) {
            val isOctaveMultiple = (shift % 12 == 0)
            var inRangeCount = 0.0
            var whiteKeyCount = 0.0
            var totalWeight = 0.0

            for (n in notes) {
                val weight = (n.durationMs.coerceIn(50L, 1000L) / 100.0)
                val shifted = n.midiNote + shift
                val pitchClass = ((shifted % 12) + 12) % 12

                if (shifted in 48..83) {
                    inRangeCount += weight
                }
                if (WHITE_PITCH_CLASSES.contains(pitchClass)) {
                    whiteKeyCount += weight
                }
                totalWeight += weight
            }

            if (totalWeight <= 0.0) continue
            val inRangeRatio = inRangeCount / totalWeight
            val whiteKeyRatio = whiteKeyCount / totalWeight

            val score = if (!master36) {
                (whiteKeyRatio * 100.0) + (inRangeRatio * 65.0) - (abs(shift) * 0.35)
            } else {
                (inRangeRatio * 100.0) + (if (isOctaveMultiple) 15.0 else 0.0) - (abs(shift) * 0.4)
            }

            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
        }
        return bestShift
    }

    fun mapSingleNoteSmart(shiftedMidiNote: Int, master36: Boolean): Int? {
        var n = shiftedMidiNote
        while (n < 48) n += 12
        while (n > 83) n -= 12

        val octaveFromC3 = ((n - 48) / 12).coerceIn(0, 2)
        val semitone = ((n - 48) % 12 + 12) % 12
        val screenRow = (2 - octaveFromC3).coerceIn(0, 2)

        return if (master36) {
            screenRow * 12 + semitone
        } else {
            val whiteCol = PITCH_CLASS_TO_WHITE_COL[semitone]
            screenRow * 7 + whiteCol
        }
    }

    fun parseStandardMidiBytes(bytes: ByteArray): List<RawMidiNote> {
        if (bytes.size < 14) return emptyList()
        if ((bytes[0].toInt() and 0xFF) != 0x4D || (bytes[1].toInt() and 0xFF) != 0x54 ||
            (bytes[2].toInt() and 0xFF) != 0x68 || (bytes[3].toInt() and 0xFF) != 0x64
        ) {
            return emptyList()
        }

        val headerLen = readInt32(bytes, 4)
        val numTracks = readInt16(bytes, 10)
        val division = readInt16(bytes, 12)
        val ticksPerQuarter = if ((division and 0x8000) == 0 && division > 0) division else 480

        data class TempoChange(val tick: Long, val microsPerQuarter: Long)
        data class TickNoteEvent(
            val startTick: Long,
            val endTick: Long,
            val note: Int,
            val channel: Int,
            val track: Int,
            val velocity: Int
        )

        val tempoChanges = mutableListOf(TempoChange(0L, 500_000L))
        val rawTickNotes = mutableListOf<TickNoteEvent>()

        var pos = 8 + headerLen
        var trackIdx = 0

        while (pos + 8 <= bytes.size && trackIdx < numTracks) {
            if ((bytes[pos].toInt() and 0xFF) != 0x4D || (bytes[pos + 1].toInt() and 0xFF) != 0x54 ||
                (bytes[pos + 2].toInt() and 0xFF) != 0x72 || (bytes[pos + 3].toInt() and 0xFF) != 0x6B
            ) {
                pos++
                continue
            }
            val trackLen = readInt32(bytes, pos + 4)
            val trackEnd = (pos + 8 + trackLen).coerceAtMost(bytes.size)
            var p = pos + 8
            var currentTick = 0L
            var runningStatus = 0
            val activeNotes = HashMap<Int, Pair<Long, Int>>()

            while (p < trackEnd) {
                var delta = 0L
                while (p < trackEnd) {
                    val b = bytes[p++].toInt() and 0xFF
                    delta = (delta shl 7) or (b and 0x7F).toLong()
                    if ((b and 0x80) == 0) break
                }
                currentTick += delta
                if (p >= trackEnd) break

                var status = bytes[p].toInt() and 0xFF
                if (status < 0x80) {
                    status = runningStatus
                } else {
                    p++
                    if (status < 0xF0) {
                        runningStatus = status
                    }
                }

                when (status and 0xF0) {
                    0x90 -> {
                        if (p + 1 >= trackEnd) break
                        val ch = status and 0x0F
                        val note = bytes[p++].toInt() and 0x7F
                        val vel = bytes[p++].toInt() and 0x7F
                        val key = (ch shl 8) or note
                        if (vel > 0) {
                            activeNotes[key] = Pair(currentTick, vel)
                        } else {
                            activeNotes.remove(key)?.let { (st, v) ->
                                rawTickNotes.add(
                                    TickNoteEvent(st, currentTick.coerceAtLeast(st + 20L), note, ch, trackIdx, v)
                                )
                            }
                        }
                    }
                    0x80 -> {
                        if (p + 1 >= trackEnd) break
                        val ch = status and 0x0F
                        val note = bytes[p++].toInt() and 0x7F
                        p++
                        val key = (ch shl 8) or note
                        activeNotes.remove(key)?.let { (st, v) ->
                            rawTickNotes.add(
                                TickNoteEvent(st, currentTick.coerceAtLeast(st + 20L), note, ch, trackIdx, v)
                            )
                        }
                    }
                    0xA0, 0xB0, 0xE0 -> p += 2
                    0xC0, 0xD0 -> p += 1
                    0xF0 -> {
                        if (status == 0xFF) {
                            if (p >= trackEnd) break
                            val metaType = bytes[p++].toInt() and 0xFF
                            var metaLen = 0
                            while (p < trackEnd) {
                                val b = bytes[p++].toInt() and 0xFF
                                metaLen = (metaLen shl 7) or (b and 0x7F)
                                if ((b and 0x80) == 0) break
                            }
                            if (metaType == 0x51 && metaLen == 3 && p + 3 <= trackEnd) {
                                val b0 = bytes[p].toInt() and 0xFF
                                val b1 = bytes[p + 1].toInt() and 0xFF
                                val b2 = bytes[p + 2].toInt() and 0xFF
                                val mpq = ((b0 shl 16) or (b1 shl 8) or b2).toLong()
                                if (mpq > 0L) {
                                    tempoChanges.add(TempoChange(currentTick, mpq))
                                }
                            }
                            p += metaLen
                        } else if (status == 0xF0 || status == 0xF7) {
                            var sysexLen = 0
                            while (p < trackEnd) {
                                val b = bytes[p++].toInt() and 0xFF
                                sysexLen = (sysexLen shl 7) or (b and 0x7F)
                                if ((b and 0x80) == 0) break
                            }
                            p += sysexLen
                        } else {
                            p++
                        }
                    }
                    else -> p++
                }
            }

            for ((key, pair) in activeNotes) {
                val ch = (key shr 8) and 0x0F
                val note = key and 0x7F
                val endT = pair.first + (ticksPerQuarter / 2).toLong()
                rawTickNotes.add(TickNoteEvent(pair.first, endT, note, ch, trackIdx, pair.second))
            }

            pos = trackEnd
            trackIdx++
        }

        if (rawTickNotes.isEmpty()) return emptyList()

        val sortedTempos = tempoChanges.sortedBy { it.tick }
        val tpqLong = ticksPerQuarter.toLong()

        fun tickToMs(targetTick: Long): Long {
            var totalMicros = 0L
            var prevTick = 0L
            var currentMpq = 500_000L
            for (tc in sortedTempos) {
                if (tc.tick >= targetTick) break
                if (tc.tick > prevTick) {
                    totalMicros += ((tc.tick - prevTick) * currentMpq) / tpqLong
                    prevTick = tc.tick
                }
                currentMpq = tc.microsPerQuarter
            }
            if (targetTick > prevTick) {
                totalMicros += ((targetTick - prevTick) * currentMpq) / tpqLong
            }
            return totalMicros / 1000L
        }

        val converted = rawTickNotes.map { ev ->
            val stMs = tickToMs(ev.startTick)
            val endMs = tickToMs(ev.endTick)
            RawMidiNote(
                startMs = stMs,
                durationMs = (endMs - stMs).coerceIn(35L, 2500L),
                midiNote = ev.note,
                channel = ev.channel,
                trackIndex = ev.track,
                velocity = ev.velocity
            )
        }.sortedBy { it.startMs }

        val firstMs = converted.first().startMs
        return converted.map { it.copy(startMs = (it.startMs - firstMs).coerceAtLeast(0L)) }
    }

    private fun readInt32(b: ByteArray, offset: Int): Int {
        if (offset + 4 > b.size) return 0
        return ((b[offset].toInt() and 0xFF) shl 24) or
               ((b[offset + 1].toInt() and 0xFF) shl 16) or
               ((b[offset + 2].toInt() and 0xFF) shl 8) or
               (b[offset + 3].toInt() and 0xFF)
    }

    private fun readInt16(b: ByteArray, offset: Int): Int {
        if (offset + 2 > b.size) return 0
        return ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)
    }

    private fun buildBuiltinLantingXu(): List<RawMidiNote> {
        val melody = listOf(
            Pair(64, 300L), Pair(67, 300L), Pair(69, 300L), Pair(72, 450L), Pair(71, 150L),
            Pair(69, 300L), Pair(71, 300L), Pair(69, 300L), Pair(67, 300L), Pair(64, 600L),
            Pair(62, 300L), Pair(64, 300L), Pair(67, 300L), Pair(69, 450L), Pair(67, 150L),
            Pair(64, 300L), Pair(62, 300L), Pair(60, 300L), Pair(62, 900L),
            Pair(69, 300L), Pair(72, 300L), Pair(74, 300L), Pair(76, 450L), Pair(74, 150L),
            Pair(72, 300L), Pair(74, 300L), Pair(72, 300L), Pair(69, 300L), Pair(67, 600L),
            Pair(64, 300L), Pair(67, 300L), Pair(69, 450L), Pair(72, 150L), Pair(71, 300L),
            Pair(69, 300L), Pair(67, 300L), Pair(69, 900L)
        )
        var t = 0L
        return melody.map { (note, dur) ->
            val r = RawMidiNote(t, dur - 30L, note, 0, 0, 100)
            t += dur
            r
        }
    }

    private fun getExternalBackupFile(): File? {
        return try {
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dlDir.exists()) dlDir.mkdirs()
            File(dlDir, BACKUP_FILENAME)
        } catch (e: Exception) {
            null
        }
    }

    fun loadXiaomi15Layout(
        context: Context,
        mode: Int,
        currentScreenWidth: Int = REF_LANDSCAPE_WIDTH.toInt(),
        currentScreenHeight: Int = REF_LANDSCAPE_HEIGHT.toInt()
    ): MutableList<KeyCoordinate> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefKey = when (mode) {
            1 -> KEY_LAYOUT_36_STAGGERED
            2 -> KEY_LAYOUT_36_LINEAR
            else -> KEY_LAYOUT_21
        }
        var savedJson = prefs.getString(prefKey, null)

        // Nếu SharedPreferences trống (ví dụ vừa cài lại app), tự động kiểm tra file backup trong thư mục Download
        if (savedJson == null) {
            try {
                val backupFile = getExternalBackupFile()
                if (backupFile != null && backupFile.exists()) {
                    val rootObj = JSONObject(backupFile.readText())
                    if (rootObj.has(prefKey)) {
                        savedJson = rootObj.getString(prefKey)
                        prefs.edit().putString(prefKey, savedJson).apply()
                    }
                }
            } catch (_: Exception) {}
        }

        if (savedJson != null) {
            try {
                val arr = JSONArray(savedJson)
                val list = mutableListOf<KeyCoordinate>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        KeyCoordinate(
                            noteIndex = obj.getInt("noteIndex"),
                            label = obj.getString("label"),
                            octaveRow = obj.getInt("octaveRow"),
                            isSharp = obj.optBoolean("isSharp", false),
                            x = obj.getDouble("x").toFloat(),
                            y = obj.getDouble("y").toFloat()
                        )
                    )
                }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return generateDefaultPreset(mode, currentScreenWidth, currentScreenHeight)
    }

    fun generateDefaultPreset(
        mode: Int,
        screenWidth: Int = REF_LANDSCAPE_WIDTH.toInt(),
        screenHeight: Int = REF_LANDSCAPE_HEIGHT.toInt()
    ): MutableList<KeyCoordinate> {
        val scaleX = screenWidth.toFloat() / REF_LANDSCAPE_WIDTH
        val scaleY = screenHeight.toFloat() / REF_LANDSCAPE_HEIGHT
        val list = mutableListOf<KeyCoordinate>()

        if (mode == 0) {
            for (r in 0..2) {
                for (c in 0..6) {
                    val idx = r * 7 + c
                    list.add(
                        KeyCoordinate(
                            noteIndex = idx,
                            label = NOTE_LABELS_21[idx],
                            octaveRow = r,
                            isSharp = false,
                            x = COLS_7_X[c] * scaleX,
                            y = ROWS_3_Y[r] * scaleY
                        )
                    )
                }
            }
        } else if (mode == 1) {
            for (r in 0..2) {
                val baseY = ROWS_3_Y[r]
                val suffix = if (r == 0) "̇" else if (r == 2) "̣" else ""
                for (s in 0..11) {
                    val idx = r * 12 + s
                    val isSharp = SHARP_COL_MAP.containsKey(s)
                    val rawX = if (!isSharp) {
                        441f + (NATURAL_COL_MAP[s] ?: 0) * 298f
                    } else {
                        441f + (SHARP_COL_MAP[s] ?: 0f) * 298f
                    }
                    val rawY = if (!isSharp) baseY else (baseY - 64f)
                    list.add(
                        KeyCoordinate(
                            noteIndex = idx,
                            label = SEMITONE_NAMES[s] + suffix,
                            octaveRow = r,
                            isSharp = isSharp,
                            x = rawX * scaleX,
                            y = rawY * scaleY
                        )
                    )
                }
            }
        } else {
            for (r in 0..2) {
                val baseY = ROWS_3_Y[r]
                val suffix = if (r == 0) "̇" else if (r == 2) "̣" else ""
                for (s in 0..11) {
                    val idx = r * 12 + s
                    val rawX = 335f + s * (2000f / 11f)
                    list.add(
                        KeyCoordinate(
                            noteIndex = idx,
                            label = SEMITONE_NAMES[s] + suffix,
                            octaveRow = r,
                            isSharp = SHARP_COL_MAP.containsKey(s),
                            x = rawX * scaleX,
                            y = baseY * scaleY
                        )
                    )
                }
            }
        }
        return list
    }

    fun saveXiaomi15Layout(context: Context, mode: Int, keys: List<KeyCoordinate>) {
        val prefKey = when (mode) {
            1 -> KEY_LAYOUT_36_STAGGERED
            2 -> KEY_LAYOUT_36_LINEAR
            else -> KEY_LAYOUT_21
        }
        val arr = JSONArray()
        keys.forEach { k ->
            val obj = JSONObject()
            obj.put("noteIndex", k.noteIndex)
            obj.put("label", k.label)
            obj.put("octaveRow", k.octaveRow)
            obj.put("isSharp", k.isSharp)
            obj.put("x", k.x.toDouble())
            obj.put("y", k.y.toDouble())
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(prefKey, arr.toString()).apply()

        // Tự động ghi bản sao lưu ra thư mục Download để không bao giờ mất vị trí nút
        autoBackupToDownload(context)
    }

    fun exportAllLayoutsJson(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val root = JSONObject()
        prefs.getString(KEY_LAYOUT_21, null)?.let { root.put(KEY_LAYOUT_21, it) }
        prefs.getString(KEY_LAYOUT_36_STAGGERED, null)?.let { root.put(KEY_LAYOUT_36_STAGGERED, it) }
        prefs.getString(KEY_LAYOUT_36_LINEAR, null)?.let { root.put(KEY_LAYOUT_36_LINEAR, it) }
        return root.toString()
    }

    fun importAllLayoutsJson(context: Context, jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            val editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            if (root.has(KEY_LAYOUT_21)) editor.putString(KEY_LAYOUT_21, root.getString(KEY_LAYOUT_21))
            if (root.has(KEY_LAYOUT_36_STAGGERED)) editor.putString(KEY_LAYOUT_36_STAGGERED, root.getString(KEY_LAYOUT_36_STAGGERED))
            if (root.has(KEY_LAYOUT_36_LINEAR)) editor.putString(KEY_LAYOUT_36_LINEAR, root.getString(KEY_LAYOUT_36_LINEAR))
            editor.apply()
            autoBackupToDownload(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun autoBackupToDownload(context: Context) {
        try {
            val backupFile = getExternalBackupFile() ?: return
            backupFile.writeText(exportAllLayoutsJson(context))
        } catch (_: Exception) {}
    }
}
