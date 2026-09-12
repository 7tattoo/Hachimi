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
import com.qing.hachimi.R
import com.qing.hachimi.data.model.Song
import com.qing.hachimi.data.repository.NeteaseRepository
import com.qing.hachimi.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        const val ACTION_RESTORE = "com.qing.hachimi.player.action.RESTORE"
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

        // ── 状态持久化 key ──
        private const val PREFS_NAME = "playback_state"
        private const val KEY_QUEUE = "queue_json"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position_ms"
        private const val KEY_PLAYING = "is_playing"
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

    /** 恢复播放时待 seek 的进度（onPrepared 消费一次后清零） */
    private var pendingSeekMs = -1L

    /** 本次 loadSong 是否由用户手动点歌触发：失败时只停下提示，不自动跳歌 */
    private var manualSelect = false

    /** tick 计数：每 ~1s 向 session 推一次 PlaybackState */
    private var tickCounter = 0

    /** 上次落盘播放进度的时间（elapsedRealtime） */
    private var lastStateSaveMs = 0L

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

        // 恢复上次的播放状态
        restorePlaybackState()

        val callback = object : MediaSessionCompat.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onSkipToNext() = loadSong(index + 1, true, manualSelect = true)
            override fun onSkipToPrevious() = loadSong(index - 1, true, manualSelect = true)
            override fun onSeekTo(pos: Long) {
                try {
                    player?.seekTo(pos.toInt())
                    currentLineIdx = -1
                    publishPlaybackState()
                    savePlaybackState()
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
                        loadSong(idx, true, manualSelect = true)
                    }
                }
            }
            ACTION_TOGGLE -> if (PlaybackStateHolder.isPlaying) pause() else resume()
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_POSITION, -1L)
                if (pos >= 0) {
                    try {
                        player?.seekTo(pos.toInt())
                        currentLineIdx = -1  // 让 tick 重新对齐到新位置
                        publishPlaybackState()
                        savePlaybackState()
                    } catch (e: Exception) {
                        AppLogger.warn("seek failed: ${e.message}")
                    }
                }
            }
            ACTION_NEXT -> { consecutiveFailures = 0; loadSong(index + 1, true, manualSelect = true) }
            ACTION_PREV -> { consecutiveFailures = 0; loadSong(index - 1, true, manualSelect = true) }
            ACTION_RESTORE -> {
                // 冷启动时 onCreate 已 restorePlaybackState；服务存活时在此同步 UI。
                // 仅当有歌曲时进入前台（保持与既有"有状态即前台"行为一致），
                // 无状态时不强制前台，避免空通知。
                if (queue.isNotEmpty()) {
                    PlaybackStateHolder.queue = queue
                    PlaybackStateHolder.queueSize = queue.size
                    PlaybackStateHolder.currentSong = currentSong()
                    publishMetadata()
                    publishPlaybackState()
                    if (wholeLrc.isNotBlank()) {
                        publishExtras(atomicEvent = true, line = lineTextAt(positionMs()), whole = wholeLrc)
                    }
                    currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
                    startForegroundCompat()
                }
                return START_NOT_STICKY
            }
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
        savePlaybackState()  // 退出前持久化状态
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
        savePlaybackState()
        if (!PlaybackStateHolder.isPlaying) {
            stopSelf()
        }
    }

    // ───────────────────────────── playback ─────────────────────────────

    private fun currentSong(): Song? = queue.getOrNull(index)

    private fun loadSong(newIndex: Int, autoplay: Boolean, manualSelect: Boolean = false) {
        if (queue.isEmpty()) return
        val wrapped = ((newIndex % queue.size) + queue.size) % queue.size
        index = wrapped
        playGen++  // 失效所有旧 player 回调（onCompletion/onError/prepared）
        this.manualSelect = manualSelect
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

        // 切歌后立即持久化新队列/索引（位置取 pendingSeekMs 或 0）
        savePlaybackState()

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
                    // 自动切歌时 vivo 车机控制器不重新读 metadata → 歌词卡在第一行；
                    // 状态跳变 PAUSED→PLAYING 强制它重新拉取（含 LYRICS_WHOLE）。
                    if (!manualSelect) nudgeCarController()
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
                if (!manualSelect) nudgeCarController()
                AppLogger.debug("lyric loaded id=${song.id} lines=${lines.size} cached=${lrcCache.containsKey(song.id)} lrcPreview=${wholeLrc.take(50)}")
            }

            // 1. 取播放地址。getSongUrl 内部已做 lossless→exhigh→higher→standard 全音质降级，
            //    不再额外重复请求 standard（旧实现每次失败要打 6 个 eapiPost，连点触发反爬限流返回空 URL）。
            var url = repo?.getSongUrl(song.id.toString(), "lossless")?.getOrNull()
            // 连点/瞬时反爬限流可能让 URL 返回空：等一小段时间重试一次再判定
            if (url.isNullOrBlank() && !standardRetried) {
                standardRetried = true
                AppLogger.warn("no url for ${song.id}, retrying after short delay (anti-abuse?)")
                delay(600)
                if (currentSong()?.id != song.id) return@launch
                url = repo?.getSongUrl(song.id.toString(), "lossless")?.getOrNull()
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
                    // 封面就绪后同步刷新通知，否则原子通知一直显示默认音乐符号
                    refreshNotificationWithCover()
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
        // 手动点歌失败时只停下提示，不自动跳歌（否则连点会 +2/+3 跳过若干首）
        if (manualSelect) {
            manualSelect = false
            stopWithMessage(message)
            return
        }
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

    /** 手动点歌失败：停下并提示，不跳歌。 */
    private fun stopWithMessage(message: String?) {
        PlaybackStateHolder.isPlaying = false
        PlaybackStateHolder.currentLine = message ?: "该歌曲暂无可播放音源"
        publishPlaybackState()
        stopTick()
        runCatching { player?.release() }
        player = null
        consecutiveFailures = 0
        currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
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
                    if (pendingSeekMs > 0) {
                        runCatching { mp.seekTo(pendingSeekMs.toInt()) }
                        PlaybackStateHolder.positionMs = pendingSeekMs
                    }
                    pendingSeekMs = -1L
                    mp.start()
                    PlaybackStateHolder.isPlaying = true
                    startTick()
                } else {
                    pendingSeekMs = -1L
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
            if (queue.isNotEmpty()) loadSong(index, true, manualSelect = true)
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
            // 切回播放时只更新 metadata（有歌词才带 LYRICS_WHOLE），不发 extras
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
        savePlaybackState()
        // pause 也不发 extras，避免干扰车机状态
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
        val pos = positionMs()
        PlaybackStateHolder.positionMs = pos

        // 周期性向 MediaSession 推送 PlaybackState（约 1s 一次）。
        // vivo 车机按 PlaybackState 进度滚动整篇 LRC 并刷新进度条；
        // 后台自动换歌后只推一次（pos=0）会让车机进度/歌词卡死在开头。
        tickCounter++
        if (tickCounter % 3 == 0 && PlaybackStateHolder.isPlaying) {
            publishPlaybackState()
        }
        // 每 ~5s 落盘一次进度，防止进程被系统杀死时丢失播放状态
        val now = SystemClock.elapsedRealtime()
        if (now - lastStateSaveMs > 5000L) {
            lastStateSaveMs = now
            savePlaybackState()
        }

        if (lrcLines.isEmpty()) return
        val idx = LrcParser.lineIndexFor(lrcLines, pos, currentLineIdx)
        // 抑制小幅回退抖动（MediaPlayer.currentPosition 偶发非单调）：
        // 仅当真正大幅后退（seek）时才允许歌词回退，避免 app 内看到歌词"回退"
        val finalIdx = if (idx < currentLineIdx && currentLineIdx >= 0) {
            val curLineTime = lrcLines.getOrNull(currentLineIdx)?.timeMs ?: 0L
            if (curLineTime - pos > 2000L) idx else currentLineIdx
        } else {
            idx
        }
        PlaybackStateHolder.lineIndex = finalIdx
        if (finalIdx != currentLineIdx) {
            currentLineIdx = finalIdx
            val line = lrcLines.getOrNull(finalIdx)?.text ?: ""
            PlaybackStateHolder.currentLine = line
            // 行变化也同步一次 PlaybackState，让车机立刻切到新行
            if (PlaybackStateHolder.isPlaying) publishPlaybackState()
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
                // 标准 LRC 格式 [mm:ss.SSS]，分钟必须双位（参考酷我音乐 12.0.8.0）
                // 我们之前写的是 [m:ss.SSS]，车机不认导致歌词不显示
                append('[')
                append(String.format("%02d", min))
                append(':')
                append(String.format("%02d.%03d", sec, ms))
                append(']')
                append(l.text)
                append('\n')
            }
        }
    }

    /**
     * MediaSession Extras 发布（vivomusicmix 原子随身听通道）。
     * 只在切歌时（atomicEvent=true）发 lrc_change 整篇 LRC 事件，
     * 以及 1/2/4/8/15s 重发 + 25s keepalive 兜底。
     *
     * 切歌后每300ms tick 只更新 MediaMetadata（LYRICS_WHOLE + LYRICS_STATUS），
     * 绝不调用 setExtras() —— 文档铁律：extras 每次推送都会把车机压回单行。
     */
    private fun publishExtras(atomicEvent: Boolean, line: String, whole: String) {
        val s = session ?: return
        try {
            val b = Bundle()
            b.putBoolean(EXTRA_ALLOWED, true)
            b.putString(EXTRA_LINE, line ?: "")
            b.putBoolean(EXTRA_NOTICE, true)
            // 只有原子随身听 lrc_change 事件才写 action；车联投屏走 metadata，不走 extras
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
        val liveDur = try {
            player?.duration?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
        val dur = if (liveDur > 0) liveDur else PlaybackStateHolder.durationMs
        if (dur > 0) b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
        coverBitmap?.let { b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }

        // 能力位：合作控制器原样读取，必须是 long（7 播控 | 8 歌词 | 16 进度条）
        b.putLong(KEY_SUPPORT_EVENT, SUPPORT_EVENT_ALL)

        if (song != null && wholeLrc.isNotBlank()) {
            // ucar 协议：整段 LRC + 状态 0 = 有歌词。
            // 文档铁律：绝不能写 LYRICS_LINE（单行模式信号）或 music.media.extras.*
            // 车机自己按 PlaybackState 进度滚动整段 LRC
            // 参考：酷我音乐 12.0.8.0 只写 LYRICS_WHOLE，我们用 LYRICS_STATUS=0 明确告知有歌词
            b.putString("ucar.media.metadata.LYRICS_WHOLE", wholeLrc)
            b.putLong("ucar.media.metadata.LYRICS_STATUS", 0L)
            // 可选：显式设置车机标题，帮助歌词卡片布局
            b.putString("ucar.media.metadata.UCAR_TITLE", song.name)
            b.putString("ucar.media.metadata.UCAR_ARTIST", song.artists)
        }
        return b
    }

    private fun publishMetadata() {
        val s = session ?: return
        try {
            val meta = buildMetadataBuilder().build()
            val hasLrc = meta.getString("ucar.media.metadata.LYRICS_WHOLE") != null
            AppLogger.debug("publishMetadata hasLyrics=$hasLrc dur=${meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)} lrcLen=${meta.getString("ucar.media.metadata.LYRICS_WHOLE")?.length ?: 0}")
            s.setMetadata(meta)
            // 每次 metadata 更新后立刻补推一次 PlaybackState：
            // vivo 车机收到新 metadata 会把进度重置为上次 PlaybackState 位置，
            // 若不补推，封面/歌词就绪时多次 setMetadata 会让车机进度卡在 0
            publishPlaybackState()
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

    /**
     * vivo 车机控制器在 App 主动切歌（非用户操作）时往往不重新读取 metadata，
     * 导致自动换歌后歌词卡在第一行。短暂推送 PAUSED→PLAYING 状态跳变，
     * 强制控制器重新拉取 metadata（含 LYRICS_WHOLE）与 PlaybackState。
     * 只改 session 状态，不调用 player.pause()，不影响实际播放。
     */
    private fun nudgeCarController() {
        val s = session ?: return
        val pos = positionMs()
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        try {
            s.setPlaybackState(
                PlaybackStateCompat.Builder().setActions(actions)
                    .setState(PlaybackStateCompat.STATE_PAUSED, pos, 0f).build()
            )
            s.setPlaybackState(
                PlaybackStateCompat.Builder().setActions(actions)
                    .setState(PlaybackStateCompat.STATE_PLAYING, pos, 1f).build()
            )
            publishMetadata()
            // 同步补推一次整段 LRC（lrc_change），防止车机偶发回退到单行模式
            if (wholeLrc.isNotBlank()) {
                publishExtras(atomicEvent = true, line = lineTextAt(pos), whole = wholeLrc)
            }
            AppLogger.debug("nudgeCarController: PAUSED→PLAYING @${pos}ms")
        } catch (e: Exception) {
            AppLogger.warn("nudgeCarController failed: ${e.message}")
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
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song.name)
            .setContentText(text)
            .setSubText(song.album)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_notif_prev, "上一首", actionIntent(ACTION_PREV, 1))
            .addAction(
                if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                if (playing) "暂停" else "播放",
                actionIntent(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_notif_next, "下一首", actionIntent(ACTION_NEXT, 3))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        // 封面写入通知 extras，让原子通知卡片能显示专辑图
        coverBitmap?.let { b.setLargeIcon(it) }
        // 歌词 extras（原子通知可能从这里读）
        b.extras.putString("LYRIC", line)
        b.extras.putString("lyric", line)
        b.extras.putString("LYRICS_WHOLE", wholeLrc)
        b.extras.putLong("LYRICS_STATUS", 0L)
        return b.build()
    }

    private fun showNotification(song: Song, line: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(song, line))
        } catch (e: Exception) {
            AppLogger.warn("showNotification failed: ${e.message}")
        }
    }

    /** 封面加载完后同步刷新通知（否则原子通知一直显示默认音乐符号）。 */
    private fun refreshNotificationWithCover() {
        currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
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

    // ───────────────────────────── state persistence ─────────────────────────────

    private fun savePlaybackState() {
        if (queue.isEmpty()) return
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            prefs.putString(KEY_QUEUE, json.encodeToString(ListSerializer(Song.serializer()), queue))
            prefs.putInt(KEY_INDEX, index)
            val pos = if (pendingSeekMs > 0) pendingSeekMs else positionMs()
            prefs.putLong(KEY_POSITION, pos)
            prefs.putBoolean(KEY_PLAYING, PlaybackStateHolder.isPlaying)
            prefs.apply()
            AppLogger.debug("saved playback state: queue=${queue.size} idx=$index playing=${PlaybackStateHolder.isPlaying}")
        } catch (e: Exception) {
            AppLogger.warn("savePlaybackState failed: ${e.message}")
        }
    }

    private fun clearPlaybackState() {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            AppLogger.warn("clearPlaybackState failed: ${e.message}")
        }
    }

    private fun restorePlaybackState() {
        if (queue.isNotEmpty()) return  // 已有内存状态（服务存活），不重复恢复
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val queueJson = prefs.getString(KEY_QUEUE, null) ?: return
            val restoredQueue = runCatching {
                json.decodeFromString(ListSerializer(Song.serializer()), queueJson)
            }.getOrNull() ?: return
            if (restoredQueue.isEmpty()) return

            queue = restoredQueue
            index = prefs.getInt(KEY_INDEX, -1).coerceIn(0, queue.size - 1)
            val savedPos = prefs.getLong(KEY_POSITION, 0L)
            val wasPlaying = prefs.getBoolean(KEY_PLAYING, false)

            PlaybackStateHolder.queue = queue
            PlaybackStateHolder.queueSize = queue.size
            PlaybackStateHolder.currentSong = queue[index]
            PlaybackStateHolder.isPlaying = wasPlaying
            PlaybackStateHolder.positionMs = savedPos

            AppLogger.debug("restored playback state: queue=${queue.size} idx=$index pos=$savedPos playing=$wasPlaying")

            // 如果之前是播放状态，重新加载歌曲并跳到上次进度
            if (wasPlaying) {
                pendingSeekMs = savedPos
                loadSong(index, true, manualSelect = true)
            } else {
                // 暂停状态：只更新 UI，不自动播放
                publishMetadata()
                publishPlaybackState()
                currentSong()?.let { showNotification(it, PlaybackStateHolder.currentLine) }
            }
        } catch (e: Exception) {
            AppLogger.warn("restorePlaybackState failed: ${e.message}")
        }
    }
}
