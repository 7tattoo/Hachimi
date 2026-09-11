package com.qing.hachimi.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.qing.hachimi.MainActivity
import com.qing.hachimi.data.model.Song
import com.qing.hachimi.data.repository.NeteaseRepository
import com.qing.hachimi.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext

/**
 * 流媒体播放前台服务。
 *
 * 核心职责：
 * 1. MediaPlayer 流媒体播放（队列 / 上一首 / 下一首 / 自动续播）
 * 2. MediaSessionCompat：向系统（vivo 智能车载 carlink / 车机 / 原子通知）发布媒体信息
 * 3. 歌词通道：把整篇 LRC + 当前行注入 MediaSession metadata 与媒体通知 extras
 *    - LYRICS_WHOLE / LYRICS / android.media.metadata.LYRICS：整篇 LRC（带时间戳）
 *    - LYRIC / lyric：当前行（每 300ms 跟踪刷新）
 *    - LYRICS_STATUS = 0
 *    - vivomusicmix.media.metadata.support_event = "31"
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_PLAY_QUEUE = "com.qing.hachimi.player.action.PLAY_QUEUE"
        const val ACTION_TOGGLE = "com.qing.hachimi.player.action.TOGGLE"
        const val ACTION_NEXT = "com.qing.hachimi.player.action.NEXT"
        const val ACTION_PREV = "com.qing.hachimi.player.action.PREV"
        const val ACTION_STOP = "com.qing.hachimi.player.action.STOP"
        const val EXTRA_QUEUE_JSON = "extra_queue_json"
        const val EXTRA_INDEX = "extra_index"

        private const val CHANNEL_ID = "hachimi_playback"
        private const val NOTIFICATION_ID = 41001
        private const val TICK_MS = 300L

        // vivo 歌词 metadata 键（与官方 IoT 版一致）
        private const val KEY_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
        private const val SUPPORT_EVENT_VALUE = "31"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }

    private var session: MediaSessionCompat? = null
    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private var queue: List<Song> = emptyList()
    private var index = -1
    private var pendingPlay = false

    private var loadJob: Job? = null
    private var lrcLines: List<LrcLine> = emptyList()
    private var currentLineIdx = -1
    private var coverBitmap: Bitmap? = null

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            tickLyric()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(ContextCompat.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { f ->
                when (f) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                }
            }
            .build()

        val callback = object : MediaSessionCompat.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onSkipToNext() = loadSong(index + 1, true)
            override fun onSkipToPrevious() = loadSong(index - 1, true)
            override fun onSeekTo(pos: Long) {
                try {
                    player?.seekTo(pos.toInt())
                    publishPlaybackState()
                } catch (e: Exception) {
                    AppLogger.warn("seekTo failed: ${e.message}")
                }
            }

            override fun onStop() {
                stopPlayback()
                stopSelf()
            }
        }

        session = MediaSessionCompat(this, "HachimiPlayback").apply {
            setCallback(callback)
            isActive = true
        }

        val nf = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nf.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW)
        )

        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_QUEUE -> {
                val q = intent.getStringExtra(EXTRA_QUEUE_JSON)?.let {
                    runCatching { json.decodeFromString(ListSerializer(Song.serializer()), it) }.getOrNull()
                }
                val idx = intent.getIntExtra(EXTRA_INDEX, 0)
                if (!q.isNullOrEmpty()) {
                    val sameSong = q.getOrNull(idx)?.id == currentSong()?.id && player != null
                    queue = q
                    if (sameSong) {
                        publishAll()
                    } else {
                        loadSong(idx, true)
                    }
                }
            }
            ACTION_TOGGLE -> if (PlaybackStateHolder.isPlaying) pause() else resume()
            ACTION_NEXT -> loadSong(index + 1, true)
            ACTION_PREV -> loadSong(index - 1, true)
            ACTION_STOP -> {
                stopPlayback()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // startForegroundService 的契约要求：必须立即进入前台
        startForegroundCompat()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(noisyReceiver) }
        stopPlayback()
        session?.release()
        session = null
        serviceScope.cancel()
        PlaybackStateHolder.currentSong = null
        PlaybackStateHolder.isPlaying = false
        PlaybackStateHolder.currentLine = ""
        PlaybackStateHolder.queueSize = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!PlaybackStateHolder.isPlaying) {
            stopSelf()
        }
    }

    // ───────────────────────────── playback ─────────────────────────────

    private fun currentSong(): Song? = queue.getOrNull(index)

    private fun loadSong(newIndex: Int, autoplay: Boolean) {
        if (queue.isEmpty()) return
        val wrapped = ((newIndex % queue.size) + queue.size) % queue.size
        index = wrapped
        pendingPlay = autoplay
        currentLineIdx = -1
        lrcLines = emptyList()
        coverBitmap = null
        PlaybackStateHolder.currentLine = ""

        val song = queue[wrapped]
        PlaybackStateHolder.currentSong = song
        PlaybackStateHolder.queueSize = queue.size
        publishMetadata()
        publishPlaybackState()
        showNotification(song, "")

        loadJob?.cancel()
        loadJob = serviceScope.launch {
            val repo = runCatching { GlobalContext.get().get<NeteaseRepository>() }.getOrNull()

            // 1. 取播放地址（无损优先，失败降级标准）
            val url = repo?.getSongUrl(song.id.toString(), "lossless")
                ?.getOrElse { repo.getSongUrl(song.id.toString(), "standard").getOrNull() }
            if (url.isNullOrBlank() || currentSong()?.id != song.id) {
                AppLogger.warn("no playable url for ${song.id}")
                PlaybackStateHolder.currentLine = "该歌曲暂无可播放音源（可能为 VIP 歌曲）"
                if (queue.size > 1) loadSong(index + 1, pendingPlay)
                return@launch
            }

            // 2. 歌词（失败不阻塞播放）
            launch {
                val full = repo.getLyricFull(song.id.toString())
                val merged = LrcParser.mergeTranslation(
                    LrcParser.parse(full?.lrc.orEmpty()),
                    LrcParser.parse(full?.tlyric.orEmpty())
                )
                lrcLines = if (merged.isNotEmpty()) merged else LrcParser.parse(full?.lrc.orEmpty())
                currentLineIdx = -1
                publishMetadata()
            }

            // 3. 封面（失败不阻塞）
            launch {
                val coverUrl = NeteaseRepository.coverDisplayUrl(song.coverUrl, 500)
                if (coverUrl.isBlank()) return@launch
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = java.net.URL(coverUrl).readBytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                if (bmp != null && currentSong()?.id == song.id) {
                    coverBitmap = bmp
                    publishMetadata()
                }
            }

            // 4. 起播
            if (currentSong()?.id != song.id) return@launch
            startPlayer(url, autoplay)
        }
    }

    private fun isPlayingNow(): Boolean = try {
        player?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    private fun startPlayer(url: String, autoplay: Boolean) {
        releasePlayer()
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(url)
            p.setOnPreparedListener { mp ->
                if (player !== mp) return@setOnPreparedListener
                if (pendingPlay) {
                    requestFocus()
                    mp.start()
                    PlaybackStateHolder.isPlaying = true
                    startTick()
                }
                publishMetadata()
                publishPlaybackState()
                startForegroundCompat()
                currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
            }
            p.setOnCompletionListener {
                if (queue.size > 1) {
                    loadSong(index + 1, true)
                } else {
                    PlaybackStateHolder.isPlaying = false
                    publishPlaybackState()
                    stopTick()
                    currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
                }
            }
            p.setOnErrorListener { _, what, extra ->
                AppLogger.warn("MediaPlayer error what=$what extra=$extra")
                if (queue.size > 1) {
                    loadSong(index + 1, pendingPlay)
                } else {
                    PlaybackStateHolder.isPlaying = false
                    publishPlaybackState()
                    stopTick()
                }
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            AppLogger.warn("startPlayer failed: ${e.message}")
            if (queue.size > 1) loadSong(index + 1, pendingPlay)
        }
    }

    private fun releasePlayer() {
        stopTick()
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun resume() {
        val p = player ?: run {
            if (queue.isNotEmpty()) loadSong(index, true)
            return
        }
        try {
            if (!p.isPlaying) {
                requestFocus()
                p.start()
            }
            pendingPlay = true
            PlaybackStateHolder.isPlaying = true
            publishPlaybackState()
            publishMetadata()
            startTick()
            currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
        } catch (e: Exception) {
            AppLogger.warn("resume failed: ${e.message}")
        }
    }

    private fun pause() {
        val p = player ?: return
        try {
            if (p.isPlaying) p.pause()
        } catch (_: Exception) {
        }
        PlaybackStateHolder.isPlaying = false
        abandonFocus()
        publishPlaybackState()
        publishMetadata()
        stopTick()
        currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
    }

    private fun stopPlayback() {
        releasePlayer()
        abandonFocus()
        PlaybackStateHolder.isPlaying = false
        session?.isActive = false
    }

    private fun requestFocus() {
        try {
            focusRequest?.let { audioManager?.requestAudioFocus(it) }
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        try {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
    }

    // ───────────────────────────── lyric tick ─────────────────────────────

    private fun startTick() {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
    }

    private fun stopTick() {
        mainHandler.removeCallbacks(tickRunnable)
    }

    private fun tickLyric() {
        if (lrcLines.isEmpty()) return
        val pos = try {
            player?.currentPosition?.toLong() ?: return
        } catch (_: Exception) {
            return
        }
        val idx = LrcParser.lineIndexFor(lrcLines, pos, currentLineIdx)
        if (idx != currentLineIdx) {
            currentLineIdx = idx
            val line = lrcLines.getOrNull(idx)?.text ?: ""
            PlaybackStateHolder.currentLine = line
            publishMetadata()
            currentSong()?.let { showNotification(it, line) }
        }
    }

    // ───────────────────── session / notification ─────────────────────

    private fun buildLrcWhole(): String {
        if (lrcLines.isEmpty()) return ""
        return buildString {
            for (l in lrcLines) {
                val min = TimeUnit.MILLISECONDS.toMinutes(l.timeMs)
                val sec = (l.timeMs % 60000) / 1000
                val ms = l.timeMs % 1000
                append('[')
                append(min)
                append(':')
                append(String.format("%02d.%03d", sec, ms))
                append(']')
                append(l.text)
                append('\n')
            }
        }
    }

    private fun buildMetadataBuilder(): MediaMetadataCompat.Builder {
        val song = currentSong()
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song?.name ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song?.artists ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song?.album ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song?.id?.toString() ?: "")
        val dur = try {
            player?.duration?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
        if (dur > 0) b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
        coverBitmap?.let { b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }

        if (song != null && lrcLines.isNotEmpty()) {
            val whole = buildLrcWhole()
            // 标准键 + vivo 车机/原子通知键（与官方 IoT 版通道一致）
            b.putString(MediaMetadataCompat.METADATA_KEY_LYRICS, whole)
            b.putString("LYRICS_WHOLE", whole)
            b.putString("LYRICS", whole)
            b.putString("lyrics", whole)
            b.putLong("LYRICS_STATUS", 0L)
            b.putString(KEY_SUPPORT_EVENT, SUPPORT_EVENT_VALUE)
            val line = lrcLines.getOrNull(currentLineIdx)?.text.orEmpty()
            if (line.isNotBlank()) {
                b.putString("LYRIC", line)
                b.putString("lyric", line)
            }
        }
        return b
    }

    private fun publishMetadata() {
        val s = session ?: return
        try {
            s.setMetadata(buildMetadataBuilder().build())
        } catch (e: Exception) {
            AppLogger.warn("publishMetadata failed: ${e.message}")
        }
    }

    private fun publishPlaybackState() {
        val s = session ?: return
        val pos = try {
            player?.currentPosition?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
        val state = if (PlaybackStateHolder.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        try {
            s.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, pos, if (PlaybackStateHolder.isPlaying) 1f else 0f)
                    .build()
            )
        } catch (e: Exception) {
            AppLogger.warn("publishPlaybackState failed: ${e.message}")
        }
    }

    private fun publishAll() {
        publishMetadata()
        publishPlaybackState()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(song: Song, line: String): Notification {
        val playing = PlaybackStateHolder.isPlaying
        val text = line.ifBlank { song.artists }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song.name)
            .setContentText(text)
            .setSubText(song.album)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "上一首", actionIntent(ACTION_PREV, 1))
            .addAction(0, if (playing) "暂停" else "播放", actionIntent(ACTION_TOGGLE, 2))
            .addAction(0, "下一首", actionIntent(ACTION_NEXT, 3))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        // 原子通知/系统媒体面板可能从通知 extras 读取歌词
        n.extras.putString("LYRIC", line)
        n.extras.putString("lyric", line)
        n.extras.putString("LYRICS_WHOLE", buildLrcWhole())
        n.extras.putLong("LYRICS_STATUS", 0L)
        return n.build()
    }

    private fun showNotification(song: Song, line: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(song, line))
        } catch (e: Exception) {
            AppLogger.warn("showNotification failed: ${e.message}")
        }
    }

    private fun startForegroundCompat() {
        val song = currentSong() ?: return
        try {
            startForeground(NOTIFICATION_ID, buildNotification(song, PlaybackStateHolder.currentLine))
        } catch (e: Exception) {
            AppLogger.warn("startForeground failed: ${e.message}")
        }
    }
}
