package com.ekranyozuvchi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.content.ContentUris
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that records the screen (and, on Android 10+, the sound
 * the phone itself plays) into an MP4 file inside Movies/ScreenRecorder.
 *
 * Video: H.264 via a surface-input MediaCodec encoder.
 * Audio (optional): AAC. On Android 10+ the source is AudioPlaybackCapture
 * (internal sound, like the system screen recorder); on Android 8-9 it is the
 * microphone. If no audio source can be opened the recording simply continues
 * without sound instead of failing.
 *
 * The foreground service is started with the mediaProjection type only — the
 * microphone FGS type is deliberately NOT used, because on Android 14 starting
 * a microphone-type service can raise a SecurityException, and internal-sound
 * capture does not need the microphone at all.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.ekranyozuvchi.app.ACTION_START"
        const val ACTION_STOP = "com.ekranyozuvchi.app.ACTION_STOP"
        const val ACTION_RECORDING_STOPPED =
            "com.ekranyozuvchi.app.RECORDING_STOPPED"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_RECORDING_SUCCEEDED = "EXTRA_RECORDING_SUCCEEDED"

        // Name of the txt file where detailed error logs are stored. It lands
        // in the Downloads folder so a screen reader can open and read it.
        const val ERROR_LOG_NAME = "EkranYozuvchi_xato_logi.txt"

        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1001

        // If the encoders have not reported end-of-stream this long after the
        // stop request, the service forces a clean finish so the app never
        // stays stuck on "stopping".
        private const val STOP_TIMEOUT_MS = 4000L

        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNELS = 1 // mono
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_CHUNK = 2048 // shorts read per iteration (~46 ms)
        private const val VIDEO_BIT_RATE = 8_000_000
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_I_FRAME_INTERVAL = 1
    }

    // ---- Capture handles (owned by this service) ----
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var audioRecord: AudioRecord? = null

    // ---- Output file (MediaStore) ----
    private var outputUri: Uri? = null
    private var outputFile: ParcelFileDescriptor? = null

    // ---- Codecs & muxer ----
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var codecThread: HandlerThread? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var audioEnabled = false

    private var videoEnded = false
    private var audioEnded = false

    // Set once both encoders have signalled end-of-stream, so finalizeFile
    // (which stops the muxer and publishes the file) runs exactly one time
    // even if a late duplicate end-of-stream callback arrives.
    private var finalizePosted = false

    // Buffers that arrive before the muxer has started are copied here and
    // flushed to the muxer as soon as it starts (MediaMuxer needs every track
    // added before start(), and the two encoders deliver their first samples
    // at different moments).
    private val pendingVideo = ArrayDeque<ByteBuffer>()
    private val pendingVideoInfo = ArrayDeque<MediaCodec.BufferInfo>()
    private val pendingAudio = ArrayDeque<ByteBuffer>()
    private val pendingAudioInfo = ArrayDeque<MediaCodec.BufferInfo>()

    private val engineLock = Any()
    private val isRecording = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Ensures a recording is finalized exactly once — by the normal
    // end-of-stream path or by the stop-timeout safety net below.
    private val finishStarted = AtomicBoolean(false)

    // Armed when a stop is requested; if the encoders never signal
    // end-of-stream (e.g. the audio read blocks on a quiet device), this forces
    // a clean finish so the UI never stays stuck on "stopping".
    private val stopTimeout = object : Runnable {
        override fun run() {
            forceFinishAfterTimeout()
        }
    }

    // If the audio encoder is running but has not produced its track format
    // within this time (e.g. nothing is playing on the device), the audio
    // track is dropped and recording continues with video only.
    private val audioTrackTimeout = object : Runnable {
        override fun run() {
            dropAudioIfStalled()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopAndFinalize()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ //
    // Start path
    // ------------------------------------------------------------------ //

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)

        // Android returns Activity.RESULT_OK (-1) on success; the reliable sign
        // that the user granted screen capture is a non-null data Intent.
        if (data == null) {
            showErrorAndStop("Ekranni yozib olish uchun ruxsat olinmadi")
            return
        }

        try {
            startRecording(resultCode, data)
        } catch (e: Exception) {
            e.printStackTrace()
            abortFailed("Yozishni boshlashda xatolik", e)
        }
    }

    /** Starts the service as a foreground service. The runtime type comes from
     *  the manifest-declared type — mediaProjection only, no microphone type —
     *  which is exactly what this app needs and what Android 14 accepts. */
    private fun startForegroundCompat() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun <T : Parcelable> getParcelableExtraCompat(
        intent: Intent,
        name: String,
        clazz: Class<T>
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(name)
        }
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Android 14 rule: a foreground service of type mediaProjection must be
        // running BEFORE getMediaProjection() is called. Starting it earlier is
        // allowed because the user already granted consent (data is present).
        // Doing it the other way around throws a SecurityException
        // ("Media projections require a foreground service of type ...").
        startForegroundCompat()

        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            showErrorAndStop("Ekranni yozib olish ruxsati olinmadi")
            return
        }
        mediaProjection = projection

        if (!createOutputFile()) {
            showErrorAndStop("Video fayl yaratib bo'lmadi")
            return
        }

        // Screen size must be even for the H.264 encoder.
        val displayMetrics = getDisplaySize()
        if (displayMetrics == null) {
            abortFailed("Ekran o'lchamini aniqlab bo'lmadi", null)
            return
        }
        val width = if (displayMetrics.widthPixels % 2 == 0) {
            displayMetrics.widthPixels
        } else {
            displayMetrics.widthPixels - 1
        }
        val height = if (displayMetrics.heightPixels % 2 == 0) {
            displayMetrics.heightPixels
        } else {
            displayMetrics.heightPixels - 1
        }
        val densityDpi = displayMetrics.densityDpi

        codecThread = HandlerThread("ScreenRecorderCodecs").apply { start() }
        val codecHandler = Handler(codecThread!!.looper)

        val mux = MediaMuxer(
            outputFile!!.fileDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        muxer = mux

        // Fresh session: clear the guard from any previous recording.
        finishStarted.set(false)

        // Must be true before the audio pump starts, or the pump exits at once.
        isRecording.set(true)

        // Audio first: on API 26-28 the mic, on 29+ internal sound. If it can
        // not be opened the recording proceeds silently (audioEnabled=false).
        setupAudio(codecHandler)

        // Video encoder (H.264, surface input).
        val videoFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
        }
        val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = videoCodec.createInputSurface()
        inputSurface = surface
        videoEncoder = videoCodec
        videoCodec.setCallback(encoderCallback, codecHandler)
        videoCodec.start()

        // Android 14 rule: the MediaProjection callback must be registered
        // BEFORE createVirtualDisplay(), otherwise capture is refused with
        // "Must register a callback before starting capture".
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopAndFinalize()
            }
        }, mainHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecorder",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }

    private fun getDisplaySize(): DisplayMetrics? {
        return try {
            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) null else metrics
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ------------------------------------------------------------------ //
    // Audio setup (optional)
    // ------------------------------------------------------------------ //

    /**
     * Builds and starts the audio pipeline. On success audioEnabled is true and
     * [startAudioPump] runs, feeding PCM into the AAC encoder. On any failure
     * the method returns and recording continues without sound.
     */
    private fun setupAudio(codecHandler: Handler) {
        val record = createAudioRecord()
        if (record == null) {
            audioEnabled = false
            audioEnded = true
            return
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            e.printStackTrace()
            safeRelease(record)
            audioEnabled = false
            audioEnded = true
            return
        }

        // Audio encoder (AAC).
        val audioCodec = try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (audioCodec == null) {
            safeRelease(record)
            audioEnabled = false
            audioEnded = true
            return
        }

        audioRecord = record
        audioEncoder = audioCodec
        audioCodec.setCallback(encoderCallback, codecHandler)
        audioCodec.start()
        audioEnabled = true
        audioEnded = false

        // Start reading PCM from the source and feeding it to the AAC encoder.
        startAudioPump(record)

        // Give the AAC encoder a moment to emit its track format; if it never
        // does (no audio is playing), drop audio so the video is not blocked.
        mainHandler.postDelayed(audioTrackTimeout, 2000)
    }

    private fun createAudioRecord(): AudioRecord? {
        return try {
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                AUDIO_CHUNK * 2
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AUDIO_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun dropAudioIfStalled() {
        synchronized(engineLock) {
            if (muxerStarted) return
            if (!audioEnabled) return
            if (audioTrackIndex >= 0) return // audio delivered in time
            // Audio never produced its track: disable it so video can finish.
            // The encoder is deliberately left running until releaseEverything —
            // stopping or releasing it from here could race with its queued
            // callbacks and abort the recording.
            audioEnabled = false
            audioEnded = true
            stopAudioCapture() // makes the audio pump exit and stop feeding audio
            startMuxerIfReadyLocked()
        }
    }

    // ------------------------------------------------------------------ //
    // Audio pump
    // ------------------------------------------------------------------ //

    private fun startAudioPump(record: AudioRecord) {
        val thread = Thread {
            val samples = ShortArray(AUDIO_CHUNK)
            var presentationTimeUs = 0L
            while (isRecording.get()) {
                val read = try {
                    record.read(samples, 0, AUDIO_CHUNK)
                } catch (e: Exception) {
                    break
                }
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION) break
                    continue
                }
                submitAudioSamples(samples, read, presentationTimeUs)
                presentationTimeUs += read * 1_000_000L / AUDIO_SAMPLE_RATE
            }
            signalAudioEndOfStream()
        }
        thread.name = "ScreenRecorderAudio"
        thread.start()
    }

    private fun submitAudioSamples(samples: ShortArray, count: Int, ptsUs: Long) {
        try {
            val codec = audioEncoder ?: return
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex < 0) return
            val inBuffer = codec.getInputBuffer(inIndex) ?: return
            inBuffer.clear()
            inBuffer.order(ByteOrder.LITTLE_ENDIAN) // AudioRecord -> little-endian PCM16
            for (i in 0 until count) {
                inBuffer.putShort(samples[i])
            }
            codec.queueInputBuffer(inIndex, 0, count * 2, ptsUs, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun signalAudioEndOfStream() {
        try {
            val codec = audioEncoder ?: return
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex < 0) return
            val inBuffer = codec.getInputBuffer(inIndex) ?: return
            inBuffer.clear()
            codec.queueInputBuffer(
                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ------------------------------------------------------------------ //
    // Codec callbacks -> muxer
    // ------------------------------------------------------------------ //

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            try {
                synchronized(engineLock) {
                    // Stale callback delivered while/after the engine was torn
                    // down (fields were reset to null) — nothing to add.
                    if (codec !== videoEncoder && codec !== audioEncoder) return
                    if (codec === videoEncoder && videoTrackIndex < 0) {
                        videoTrackIndex = muxer!!.addTrack(format)
                    } else if (codec === audioEncoder && audioTrackIndex < 0) {
                        audioTrackIndex = muxer!!.addTrack(format)
                    }
                    startMuxerIfReadyLocked()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isRecording.get()) {
                    mainHandler.post { abortFailed("Kodlash xatosi", e) }
                }
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // If the codec no longer belongs to this recording session, this is
            // a stale callback from an encoder that was already torn down —
            // drop it without touching the muxer.
            if (codec !== videoEncoder && codec !== audioEncoder) {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                }
                return
            }
            var released = false
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer == null) {
                    // Some devices hand out a null buffer for the empty EOS
                    // frame; the buffer must still be released, and the EOS
                    // flag must still be observed.
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        handleEndOfStream(codec)
                    }
                    codec.releaseOutputBuffer(index, false)
                    released = true
                    return
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Codec-specific data travels inside the track format; the
                    // sample itself must not be written to the muxer.
                    info.size = 0
                }
                if (info.size > 0) {
                    synchronized(engineLock) {
                        val isVideo = codec === videoEncoder
                        val track = if (isVideo) videoTrackIndex else audioTrackIndex
                        if (muxerStarted && track >= 0) {
                            muxer!!.writeSampleData(track, buffer, info)
                        } else if (track >= 0) {
                            // Muxer not started yet: buffer the sample for later.
                            val copy = ByteBuffer.allocateDirect(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            copy.put(buffer)
                            copy.rewind()
                            val copyInfo = MediaCodec.BufferInfo()
                            copyInfo.set(
                                0, info.size, info.presentationTimeUs, info.flags
                            )
                            if (isVideo) {
                                pendingVideo.add(copy)
                                pendingVideoInfo.add(copyInfo)
                            } else {
                                pendingAudio.add(copy)
                                pendingAudioInfo.add(copyInfo)
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
                released = true

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    handleEndOfStream(codec)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (!released) {
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (ignored: Exception) {
                    }
                }
                // Only the first failure may abort; by the time teardown has
                // started (isRecording == false) further errors are expected.
                if (isRecording.get()) {
                    mainHandler.post { abortFailed("Yozish paytida xatolik", e) }
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            e.printStackTrace()
            if (isRecording.get()) {
                mainHandler.post { abortFailed("Kodlash xatosi", e) }
            }
        }
    }

    private fun handleEndOfStream(codec: MediaCodec) {
        synchronized(engineLock) {
            if (codec === videoEncoder) {
                videoEnded = true
            } else if (codec === audioEncoder) {
                audioEnded = true
            }
            if (videoEnded && audioEnded && !finalizePosted) {
                finalizePosted = true
                mainHandler.post { finalizeFile(success = true) }
            }
        }
    }

    private fun startMuxerIfReadyLocked() {
        if (muxerStarted) return
        if (videoTrackIndex < 0) return
        if (audioEnabled && audioTrackIndex < 0) return
        muxer!!.start()
        muxerStarted = true
        flushPendingLocked()
    }

    private fun flushPendingLocked() {
        while (pendingVideo.isNotEmpty() && videoTrackIndex >= 0) {
            val buffer = pendingVideo.removeFirst()
            val info = pendingVideoInfo.removeFirst()
            muxer!!.writeSampleData(videoTrackIndex, buffer, info)
        }
        while (pendingAudio.isNotEmpty() && audioTrackIndex >= 0) {
            val buffer = pendingAudio.removeFirst()
            val info = pendingAudioInfo.removeFirst()
            muxer!!.writeSampleData(audioTrackIndex, buffer, info)
        }
    }

    // ------------------------------------------------------------------ //
    // Stop path
    // ------------------------------------------------------------------ //

    /** Idempotent stop: releases capture and lets the encoders finish cleanly. */
    private fun stopAndFinalize() {
        if (!isRecording.compareAndSet(true, false)) return

        mainHandler.removeCallbacks(audioTrackTimeout)

        // Stop feeding frames into the video encoder.
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null

        // Unblock the audio pump (a stopped record makes read() return).
        stopAudioCapture()

        // Ask the video encoder to drain and emit its end-of-stream buffer.
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            e.printStackTrace()
            synchronized(engineLock) {
                videoEnded = true
                if (videoEnded && audioEnded) {
                    mainHandler.post { finalizeFile(success = true) }
                }
            }
        }
        // The audio pump sees isRecording == false, stops reading and signals
        // its own end-of-stream. finalizeFile runs once both encoders report
        // EOS. If that never happens (blocked audio read, slow encoder), the
        // safety net below forces a clean finish so the UI is not stuck.
        mainHandler.removeCallbacks(stopTimeout)
        mainHandler.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
    }

    private fun stopAudioCapture() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun finalizeFile(success: Boolean) {
        if (!finishStarted.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(stopTimeout)

        val wasStarted = muxerStarted
        var ok = success && wasStarted
        if (ok) {
            try {
                muxer?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
                writeErrorLog("Videoni yakunlashda xatolik", e)
                ok = false
            }
        }
        releaseEverything()
        if (ok) {
            publishOutput()
        } else {
            // Only a started-but-failed muxer is a real error worth logging; a
            // recording that never started (e.g. stopped within a moment) is
            // just cancelled — delete its file silently.
            if (wasStarted) {
                writeErrorLog("Video yakunlanmadi", null)
            }
            deleteOutput()
        }
        sendStoppedBroadcast(ok)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Runs a few seconds after a stop request if the encoders never signalled
     *  end-of-stream. Drops audio if it never produced a track (e.g. nothing
     *  was playing) and finishes as a video-only file, so the app never stays
     *  stuck on "stopping". A no-op if the normal path already finalized. */
    private fun forceFinishAfterTimeout() {
        synchronized(engineLock) {
            if (!muxerStarted && videoTrackIndex >= 0) {
                audioEnabled = false
                audioEnded = true
                startMuxerIfReadyLocked()
            }
        }
        finalizeFile(success = true)
    }

    // ------------------------------------------------------------------ //
    // Cleanup helpers
    // ------------------------------------------------------------------ //

    private fun releaseEverything() {
        stopAudioCapture()
        safeRelease(audioRecord)
        audioRecord = null

        // Reset all engine state under the lock first. From that point no
        // encoder callback (which re-reads the state under the same lock) can
        // write to the muxer any more — this is what keeps a sample from ever
        // being written to a muxer that is being torn down (the old
        // "trackIndex is invalid" race). The native codecs/muxer are then
        // stopped and released OUTSIDE the lock: calling codec.stop() while
        // holding this lock can deadlock — a callback waiting on the same lock
        // would never finish, so stop() would never return and the whole
        // service (and UI) would freeze.
        var audioCodec: MediaCodec? = null
        var videoCodec: MediaCodec? = null
        var mux: MediaMuxer? = null
        synchronized(engineLock) {
            audioCodec = audioEncoder
            videoCodec = videoEncoder
            mux = muxer
            audioEncoder = null
            videoEncoder = null
            muxer = null
            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            videoEnded = false
            audioEnded = false
            finalizePosted = false
            audioEnabled = false
            pendingVideo.clear()
            pendingVideoInfo.clear()
            pendingAudio.clear()
            pendingAudioInfo.clear()
        }

        try {
            audioCodec?.stop()
        } catch (e: Exception) {
        }
        try {
            audioCodec?.release()
        } catch (e: Exception) {
        }
        try {
            videoCodec?.stop()
        } catch (e: Exception) {
        }
        try {
            videoCodec?.release()
        } catch (e: Exception) {
        }
        try {
            mux?.stop()
        } catch (e: Exception) {
        }
        try {
            mux?.release()
        } catch (e: Exception) {
        }

        try {
            inputSurface?.release()
        } catch (e: Exception) {
        }
        inputSurface = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
        }
        mediaProjection = null

        codecThread?.quitSafely()
        codecThread = null

        try {
            outputFile?.close()
        } catch (e: Exception) {
        }
        outputFile = null
    }

    private fun safeRelease(record: AudioRecord?) {
        try {
            record?.stop()
        } catch (e: Exception) {
        }
        try {
            record?.release()
        } catch (e: Exception) {
        }
    }

    private fun publishOutput() {
        val uri = outputUri ?: return
        outputUri = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteOutput() {
        val uri = outputUri ?: return
        outputUri = null
        try {
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createOutputFile(): Boolean {
        return try {
            val fileName = "ScreenRecord_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            outputUri = uri
            val file = contentResolver.openFileDescriptor(uri, "w") ?: run {
                contentResolver.delete(uri, null, null)
                outputUri = null
                return false
            }
            outputFile = file
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun sendStoppedBroadcast(success: Boolean) {
        val broadcast = Intent(ACTION_RECORDING_STOPPED)
        broadcast.setPackage(packageName)
        broadcast.putExtra(EXTRA_RECORDING_SUCCEEDED, success)
        sendBroadcast(broadcast)
    }

    private fun showErrorAndStop(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        sendStoppedBroadcast(success = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Writes a full, detailed error report to a plain-text file in Downloads
     * (EkranYozuvchi_xato_logi.txt) so the user can open it with a file manager
     * and a screen reader can read the whole error text aloud. The file is
     * overwritten on each new error so it always holds the latest report.
     */
    private fun writeErrorLog(userMessage: String, cause: Throwable?) {
        try {
            val sb = StringBuilder()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date())
            sb.append("=== ").append(stamp).append(" ===\n")
            sb.append(userMessage).append("\n")
            if (cause != null) {
                sb.append(cause.javaClass.name)
                    .append(": ").append(cause.message ?: "(no message)").append("\n")
                cause.stackTrace.forEach { sb.append("    at ").append(it).append("\n") }
                var c: Throwable? = cause.cause
                while (c != null) {
                    sb.append("Caused by: ").append(c.javaClass.name)
                        .append(": ").append(c.message ?: "(no message)").append("\n")
                    c.stackTrace.forEach { sb.append("    at ").append(it).append("\n") }
                    c = c.cause
                }
            }
            val data = sb.toString().toByteArray(Charsets.UTF_8)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                var uri: Uri? = null
                contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(ERROR_LOG_NAME),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        uri = ContentUris.withAppendedId(
                            collection, cursor.getLong(0)
                        )
                    }
                }
                val target = if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, ERROR_LOG_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                    }
                    contentResolver.insert(collection, values)
                } else {
                    uri
                }
                if (target != null) {
                    contentResolver.openFileDescriptor(target, "rwt")?.use { pfd ->
                        FileOutputStream(pfd.fileDescriptor).use { fos ->
                            fos.write(data)
                        }
                    }
                }
            } else {
                // Android 8-9: app-specific folder (no storage permission needed).
                val dir = getExternalFilesDir(null) ?: return
                val file = File(dir, ERROR_LOG_NAME)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { fos -> fos.write(data) }
            }
        } catch (e: Exception) {
            // Logging must never break the app itself.
            e.printStackTrace()
        }
    }

    private fun abortFailed(message: String, cause: Throwable?) {
        // Idempotent: only the first failure tears down and writes the log.
        // Duplicate error callbacks during teardown must not overwrite it.
        if (!isRecording.compareAndSet(true, false)) return
        mainHandler.removeCallbacks(audioTrackTimeout)
        // Persist the error report before teardown, so a hang during release
        // can never swallow the only copy of the real error.
        writeErrorLog(message, cause)
        releaseEverything()
        deleteOutput()
        showErrorAndStop("$message\nBatafsil log: Downloads/$ERROR_LOG_NAME")
    }

    // ------------------------------------------------------------------ //
    // Notification
    // ------------------------------------------------------------------ //

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, flags
        )
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_media_pause,
            getString(R.string.stop_recording),
            stopPendingIntent
        )
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setStyle(mediaStyle)
            .addAction(stopAction)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        stopAndFinalize()
        super.onDestroy()
    }
}
