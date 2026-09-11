package com.qing.hachimi.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 播放状态共享（供 Compose UI 观察 + 外部查询）。
 * 由 PlaybackService 在主线程更新。
 */
object PlaybackStateHolder {
    var currentSong by mutableStateOf<com.qing.hachimi.data.model.Song?>(null)
    var isPlaying by mutableStateOf(false)
    var currentLine by mutableStateOf("")
    var queueSize by mutableStateOf(0)
}
