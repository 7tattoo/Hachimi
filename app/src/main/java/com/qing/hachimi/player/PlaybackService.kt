package com.qing.hachimi.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
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
 * 流媒体播放前台服务（MediaBrowserService）。
 *
 * vivo 歌词协议（依据 huang6668/apple-music-vivo-car-lyrics 对
 * 原子随身听 6.2.5.6 / 车联 6.0.8.3 的逆向结论）：
 *
 * 1. Manifest 声明 action `com.vivo.musicwidgetmix.support.service`
 *    → 原子随身听选择合作控制器，support_event 才会原样读取 metadata。
 * 2. MediaMetadata：`vivomusicmix.media.metadata.support_event` = 31L（7 播控 | 8 歌词 | 16 进度条）。
 * 3. MediaSession Extras（onExtrasChanged 通道）：
 *    - 常驻：music.media.extras.LYRIC / LYRIC_IS_ALLOWED / NOTICE_CAR
 *    - 歌词事件（切歌清空 + 整篇 LRC + 周期重发）：
 *      vivomusicmix.meida.extra.key.action = "vivomusicmix.extra.lrc_change"
 *      vivomusicmix.extra.key.meidia_id    = METADATA_KEY_MEDIA_ID 同值
 *      vivomusicmix.extra.key.lyric        = 完整带时间戳 LRC
 *      （`meida`/`meidia` 为协议真实拼写，不能改）
 * 4. 逐行变化只更新 extras，绝不重发 MediaMetadata（重发会重置进度条）。
 * 5. 原子/车机自行按播放位置从整篇 LRC 切行；重发调度兜底控制器晚连接。
 */
class PlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val ACTION_PLAY_QUEUE = "com.qing.hachimi.player.action.PLAY_QUEUE"
        const val ACTION_TOGGLE = "com.qing.hachimi.player.action.TOGGLE"
        const val ACTION_NEXT = "com.qing.hachimi.player.action.NEXT"
        const val ACTION_PREV = "com.qing.hachimi.player.action.PREV"
        const val ACTION_STOP = "com.qing.hachimi.player.action.STOP"
        const val ACTION_SEEK = "com.qing.hachimi.player.action.SEEK"
        const val EXTRA_QUEUE_JSON = "extra_queue_json"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_POSITION = "extra_position"

        private const val CHANNEL_ID = "hachimi_playback"
        private const val NOTIFICATION_ID = 41001
        private const val TICK_MS = 300L

        // ── vivo 协议常量（拼写照抄官方逆向结果，勿改） ──
        private const val EXTRA_LINE = "music.media.extras.LYRIC"
        private const val EXTRA_ALLOWED = "music.media.extras.LYRIC_IS_ALLOWED"
        private const val EXTRA_NOTICE = "music.media.extras.NOTICE_CAR"
        private const val ATOMIC_ACTION_KEY = "vivomusicmix.meida.extra.key.action"
        private const val ATOMIC_LRC_CHANGE = "vivomusicmix.extra.lrc_change"
        private const val ATOMIC_MEDIA_ID = "vivomusicmix.extra.key.meidia_id"
        private const val ATOMIC_LYRIC = "vivomusicmix.extra.key.lyric"
        private const val KEY_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
        private const val SUPPORT_EVENT_ALL = 7L or 8L or 16L  // 播控 | 歌词 | 进度条/seek

        private val ATOMIC_REPLAY_DELAYS_MS = longArrayOf(1000L, 2000L, 4000L, 8000L, 15000L)
        private const val ATOMIC_KEEPALIVE_MS = 25000L
        private const val SEEK_REFRESH_MS = 120L

        // 自动跳歌熔断参数
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MIN_PLAY_MS_BEFORE_NORMAL_END = 4000L
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
    private var wholeLrc: String = ""
    private var currentLineIdx = -1
    private var coverBitmap: Bitmap? = null

    /** 世代号：每次切歌 +1。MediaPlayer 回调捕获当时的世代号，不匹配则忽略（防旧 player 回调误触发切歌/跳歌）。 */
    private var playGen = 0

    /** 歌词内存缓存：songId -> (解析行, 整篇 LRC)。来回切歌秒回，避免重复网络请求。 */
    private val lrcCache = HashMap<Long, Pair<List<LrcLine>, String>>()

    // 自动跳歌熔断：连续失败（取不到 URL / 播放出错 / 秒切）达到上限就停止切歌
    private var consecutiveFailures = 0
    private var lastStartElapsedMs = 0L

    /** 当前这首歌是否已经降级重试过标准音质（每首歌只重试一次） */
    private var standardRetried = false

    /** 重发调度令牌：切歌时撤销旧歌的所有待发任务 */
    private var extrasToken = Any()

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
        audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
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
                    // seek 后立即发布对应行，120ms 后再刷一次（对齐官方适配的 SeekRefreshTask）
                    val line = lineTextAt(pos)
                    publishExtras(atomicEvent = false, line = line, whole = "")
                    mainHandler.postDelayed({ publishExtras(false, lineTextAt(positionMs()), "") }, SEEK_REFRESH_MS)
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
        // MediaBrowserService 契约：同一 session token，原子随身听经此建立合作控制器
        session?.sessionToken?.let { setSessionToken(it) }

        val nf = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nf.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW)
        )

        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot("hachimi_root", null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(null)  // 不提供浏览内容，仅作为协议入口
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
                    consecutiveFailures = 0  // 手动点歌重置熔断
                    if (sameSong) {
                        publishAll()
                    } else {
                        loadSong(idx, true)
                    }
                }
            }
            ACTION_TOGGLE -> if (PlaybackStateHolder.isPlaying) pause() else resume()
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_POSITION, -1L)
                if (pos >= 0) {
                    try {
                        player?.seekTo(pos.toInt())
                        publishPlaybackState()
                        val line = lineTextAt(pos)
                        publishExtras(atomicEvent = false, line = line, whole = "")
                        mainHandler.postDelayed({ publishExtras(false, lineTextAt(positionMs()), "") }, SEEK_REFRESH_MS)
                    } catch (e: Exception) {
                        AppLogger.warn("seek failed: ${e.message}")
                    }
                }
            }
            ACTION_NEXT -> { consecutiveFailures = 0; loadSong(index + 1, true) }
            ACTION_PREV -> { consecutiveFailures = 0; loadSong(index - 1, true) }
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

    override fun onBind(intent: Intent?): IBinder = super.onBind(intent)!!

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
        playGen++  // 失效所有旧 player 回调（onCompletion/onError/prepared）
        AppLogger.warn("loadSong id=${queue[wrapped].id} idx=$wrapped q=${queue.size} failStreak=$consecutiveFailures gen=$playGen")
        pendingPlay = autoplay
        currentLineIdx = -1
        lrcLines = emptyList()
        wholeLrc = ""
        coverBitmap = null
        standardRetried = false
        PlaybackStateHolder.currentLine = ""

        // 立即释放旧 player：否则旧 player 的 onCompletion/onError 会在新 URL 加载期间
        // 触发 loadSong(index+1) / retryWithStandardOrAdvance，把刚切的歌跳过去
        releasePlayer()

        val song = queue[wrapped]
        PlaybackStateHolder.currentSong = song
        PlaybackStateHolder.queueSize = queue.size
        PlaybackStateHolder.queue = queue
        PlaybackStateHolder.lyrics = emptyList()
        PlaybackStateHolder.lineIndex = -1
        PlaybackStateHolder.positionMs = 0L

        // 撤销上一首歌的所有 extras 重发任务
        mainHandler.removeCallbacksAndMessages(extrasToken)
        extrasToken = Any()

        publishMetadata()
        publishPlaybackState()
        // 切歌清空事件：lrc_change + 新曲 ID + 空 lyric（清掉原子内存里的上一首）
        publishExtras(atomicEvent = true, line = "", whole = "")
        showNotification(song, "")

        loadJob?.cancel()
        loadJob = serviceScope.launch {
            val repo = runCatching { GlobalContext.get().get<NeteaseRepository>() }.getOrNull()

            // 0. 歌词先并行取（不阻塞 URL/播放；有缓存则秒回）
            launch {
                val cached = lrcCache[song.id]
                if (cached != null) {
                    lrcLines = cached.first
                    wholeLrc = cached.second
                    currentLineIdx = -1
                    PlaybackStateHolder.lyrics = cached.first
                    publishMetadata()
                    if (wholeLrc.isNotBlank()) {
                        publishExtras(atomicEvent = true, line = lineTextAt(positionMs()), whole = wholeLrc)
                        scheduleAtomicReplays(wholeLrc)
                        AppLogger.debug("lyric cache hit id=${song.id} lines=${cached.first.size}")
                    }
                    return@launch
                }
                val full = repo?.getLyricFull(song.id.toString())
                val merged = LrcParser.mergeTranslation(
                    LrcParser.parse(full?.lrc.orEmpty()),
                    LrcParser.parse(full?.tlyric.orEmpty())
                )
                val lines = if (merged.isNotEmpty()) merged else LrcParser.parse(full?.lrc.orEmpty())
                if (currentSong()?.id != song.id) return@launch
                lrcLines = lines
                wholeLrc = buildLrcWhole()
                currentLineIdx = -1
                PlaybackStateHolder.lyrics = lines
                if (lines.isNotEmpty()) lrcCache[song.id] = lines to wholeLrc
                publishMetadata()
                if (wholeLrc.isNotBlank()) {
                    publishExtras(atomicEvent = true, line = lineTextAt(positionMs()), whole = wholeLrc)
                    scheduleAtomicReplays(wholeLrc)
                }
                AppLogger.debug("lyric loaded id=${song.id} lines=${lines.size} cached=${lrcCache.containsKey(song.id)}")
            }

            // 1. 取播放地址（无损优先，失败降级标准；URL 层面就拿不到的走同歌降级重试）
            var url = repo?.getSongUrl(song.id.toString(), "lossless")
                ?.getOrElse { repo.getSongUrl(song.id.toString(), "standard").getOrNull() }
            if (url.isNullOrBlank() && !standardRetried) {
                standardRetried = true
                AppLogger.warn("no url for ${song.id} at lossless, retrying standard")
                url = repo?.getSongUrl(song.id.toString(), "standard")?.getOrNull()
            }
            if (url.isNullOrBlank() || currentSong()?.id != song.id) {
                AppLogger.warn("no playable url for ${song.id}")
                onAutoAdvanceFailure("该歌曲暂无可播放音源（可能为 VIP 歌曲）")
                return@launch
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

    /**
     * 自动切换下一首失败时的统一入口：带熔断，防止歌单里 VIP/灰色歌曲
     * 连片失败导致无限跳歌（迷你播放条歌名狂闪）。
     */
    private fun onAutoAdvanceFailure(message: String?) {
        consecutiveFailures++
        AppLogger.warn("auto-advance failure #$consecutiveFailures")
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES || queue.size <= 1) {
            // 停止自动切歌
            PlaybackStateHolder.isPlaying = false
            PlaybackStateHolder.currentLine = message
                ?: "连续 ${consecutiveFailures} 首无法播放，已停止自动切歌"
            publishPlaybackState()
            stopTick()
            // 释放错误态 player，否则 resume() 会在死 player 上 start 失败、点播放无反应
            runCatching { player?.release() }
            player = null
            currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
        } else {
            PlaybackStateHolder.currentLine = message.orEmpty()
            loadSong(index + 1, pendingPlay)
        }
    }

    private fun startPlayer(url: String, autoplay: Boolean) {
        releasePlayer()
        val p = MediaPlayer()
        player = p
        val gen = playGen  // 捕获本次切歌世代号
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(url)
            p.setOnPreparedListener { mp ->
                if (player !== mp || gen != playGen) return@setOnPreparedListener
                consecutiveFailures = 0
                lastStartElapsedMs = android.os.SystemClock.elapsedRealtime()
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
                if (gen != playGen) return@setOnCompletionListener
                // 播了不到 4 秒就"完成"视为异常切换
                val playedMs = SystemClock.elapsedRealtime() - lastStartElapsedMs
                if (playedMs < MIN_PLAY_MS_BEFORE_NORMAL_END) {
                    AppLogger.warn("track ended too fast (${playedMs}ms) gen=$gen playGen=$playGen")
                    retryWithStandardOrAdvance(null)
                } else {
                    consecutiveFailures = 0
                    if (queue.size > 1) {
                        loadSong(index + 1, true)
                    } else {
                        PlaybackStateHolder.isPlaying = false
                        publishPlaybackState()
                        stopTick()
                        currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
                    }
                }
            }
            p.setOnErrorListener { _, what, extra ->
                if (gen != playGen) return@setOnErrorListener true
                AppLogger.warn("MediaPlayer error what=$what extra=$extra gen=$gen playGen=$playGen")
                retryWithStandardOrAdvance(null)
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            AppLogger.warn("startPlayer failed: ${e.message}")
            retryWithStandardOrAdvance(null)
        }
    }

    /**
     * MediaPlayer 播不动（FLAC/high-res 报错、秒完成）时：
     * 优先对【同一首歌】降级重试标准音质（MP3），而不是直接跳到下一首。
     * 每首歌只降级一次；降级后再失败才走熔断/跳歌。
     */
    private fun retryWithStandardOrAdvance(message: String?) {
        val song = currentSong()
        if (song != null && !standardRetried) {
            standardRetried = true
            AppLogger.warn("retrying ${song.id} with standard quality")
            loadJob?.cancel()
            loadJob = serviceScope.launch {
                val repo = runCatching { GlobalContext.get().get<NeteaseRepository>() }.getOrNull()
                val url = repo?.getSongUrl(song.id.toString(), "standard")?.getOrNull()
                if (url.isNullOrBlank() || currentSong()?.id != song.id) {
                    // 标准音质也拿不到 → 走普通失败
                    onAutoAdvanceFailure(message ?: "标准音质不可用")
                } else {
                    startPlayer(url, pendingPlay)
                }
            }
        } else {
            onAutoAdvanceFailure(message)
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
            publishExtras(atomicEvent = false, line = lineTextAt(positionMs()), whole = "")
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
        publishExtras(atomicEvent = false, line = lineTextAt(positionMs()), whole = "")
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

    private fun positionMs(): Long = try {
        player?.currentPosition?.toLong() ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun lineTextAt(posMs: Long): String {
        val idx = LrcParser.lineIndexFor(lrcLines, posMs, currentLineIdx)
        return if (idx >= 0) lrcLines[idx].text else ""
    }

    private fun tickLyric() {
        PlaybackStateHolder.positionMs = positionMs()
        if (lrcLines.isEmpty()) return
        val pos = positionMs()
        val idx = LrcParser.lineIndexFor(lrcLines, pos, currentLineIdx)
        // 播放页高亮/滚动依赖 lineIndex，之前漏写导致永远 -1（播放页歌词不滚动=图二问题）
        PlaybackStateHolder.lineIndex = idx
        if (idx != currentLineIdx) {
            currentLineIdx = idx
            val line = lrcLines.getOrNull(idx)?.text ?: ""
            PlaybackStateHolder.currentLine = line
            // 逐行变化只发 extras（legacy 车联键），不碰 metadata
            publishExtras(atomicEvent = false, line = line, whole = "")
            currentSong()?.let { showNotification(it, line) }
        }
    }

    // ────────────────── vivo session extras / metadata ──────────────────

    private fun buildLrcWhole(): String {
        if (lrcLines.isEmpty()) return ""
        return buildString {
            for (l in lrcLines) {
                val min = l.timeMs / 60000L
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

    /**
     * MediaSession Extras 发布（onExtrasChanged 通道）。
     * atomicEvent=true 时携带 lrc_change 整篇 LRC 事件（原子随身听与车联歌词的真正来源）；
     * atomicEvent=false 时只更新常驻车机键（action 置空，防止旧事件被重复消费）。
     */
    private fun publishExtras(atomicEvent: Boolean, line: String, whole: String) {
        val s = session ?: return
        try {
            val b = Bundle()
            b.putBoolean(EXTRA_ALLOWED, true)
            b.putString(EXTRA_LINE, line ?: "")
            b.putBoolean(EXTRA_NOTICE, true)
            b.putString(ATOMIC_ACTION_KEY, if (atomicEvent) ATOMIC_LRC_CHANGE else "")
            b.putString(ATOMIC_MEDIA_ID, currentSong()?.id?.toString() ?: "")
            b.putString(ATOMIC_LYRIC, if (atomicEvent) (whole ?: "") else "")
            s.setExtras(b)
        } catch (e: Exception) {
            AppLogger.warn("publishExtras failed: ${e.message}")
        }
    }

    /** 整篇 LRC 发布后的重发调度：兜底原子随身听/车联晚连接与重连。 */
    private fun scheduleAtomicReplays(whole: String) {
        val token = extrasToken
        for (delay in ATOMIC_REPLAY_DELAYS_MS) {
            mainHandler.postDelayed({
                if (token === extrasToken && whole == wholeLrc && lrcLines.isNotEmpty()) {
                    publishExtras(atomicEvent = true, line = lineTextAt(positionMs()), whole = whole)
                }
            }, token, delay)
        }
        // keepalive：25s 周期重发，覆盖车机重连；内容不变不会触发车端封面重载
        object : Runnable {
            override fun run() {
                if (token !== extrasToken) return
                if (whole == wholeLrc && lrcLines.isNotEmpty()) {
                    publishExtras(atomicEvent = true, line = lineTextAt(positionMs()), whole = whole)
                }
                mainHandler.postDelayed(this, ATOMIC_KEEPALIVE_MS)
            }
        }.also { mainHandler.postDelayed(it, token, ATOMIC_KEEPALIVE_MS) }
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

        // 能力位：合作控制器原样读取，必须是 long（7 播控 | 8 歌词 | 16 进度条）
        b.putLong(KEY_SUPPORT_EVENT, SUPPORT_EVENT_ALL)

        if (song != null && wholeLrc.isNotBlank()) {
            // 兼容键：部分车联版本从 MediaMetadata 读整篇 LRC
            b.putString("ucar.media.metadata.LYRICS_WHOLE", wholeLrc)
            val line = lrcLines.getOrNull(currentLineIdx)?.text.orEmpty()
            if (line.isNotBlank()) b.putString("ucar.media.metadata.LYRICS_LINE", line)
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
        val pos = positionMs()
        PlaybackStateHolder.positionMs = pos
        val dur = try {
            player?.duration?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
        if (dur > 0) PlaybackStateHolder.durationMs = dur
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
        if (wholeLrc.isNotBlank()) {
            publishExtras(atomicEvent = true, line = lineTextAt(positionMs()), whole = wholeLrc)
        }
    }

    // ───────────────────────── notification ─────────────────────────

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
        // 原子通知可能从通知 extras 读取当前行
        n.extras.putString("LYRIC", line)
        n.extras.putString("lyric", line)
        n.extras.putString("LYRICS_WHOLE", wholeLrc)
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
        try {
            val song = currentSong()
            if (song != null) {
                startForeground(NOTIFICATION_ID, buildNotification(song, PlaybackStateHolder.currentLine))
            } else {
                val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Hachimi")
                    .build()
                startForeground(NOTIFICATION_ID, placeholder)
            }
        } catch (e: Exception) {
            AppLogger.warn("startForeground failed: ${e.message}")
        }
    }
}
