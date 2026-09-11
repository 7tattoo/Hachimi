package com.qing.hachimi.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qing.hachimi.data.model.Song

/**
 * 播放状态共享（供 Compose UI 观察 + 外部查询）。
 * 由 PlaybackService 在主线程更新。
 */
object PlaybackStateHolder {
    var currentSong by mutableStateOf<Song?>(null)
    var isPlaying by mutableStateOf(false)
    var currentLine by mutableStateOf("")
    var queueSize by mutableIntStateOf(0)

    /** 临时播放列表（点歌时自动以整个列表入队） */
    var queue by mutableStateOf<List<Song>>(emptyList())

    /** 当前歌的解析后歌词（原文+译文合并） */
    var lyrics by mutableStateOf<List<LrcLine>>(emptyList())

    /** 当前行下标（300ms tick 刷新） */
    var lineIndex by mutableIntStateOf(-1)

    /** 播放进度（ms），驱动播放页进度条 */
    var positionMs by mutableLongStateOf(0L)

    /** 总时长（ms） */
    var durationMs by mutableLongStateOf(0L)
}
