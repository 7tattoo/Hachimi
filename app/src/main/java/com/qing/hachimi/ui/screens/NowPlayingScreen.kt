package com.qing.hachimi.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qing.hachimi.data.repository.NeteaseRepository.Companion.coverDisplayUrl
import com.qing.hachimi.player.LrcLine
import com.qing.hachimi.player.PlaybackStateHolder
import com.qing.hachimi.player.PlayerController
import com.qing.hachimi.util.HapticLevel
import com.qing.hachimi.util.haptic
import androidx.compose.ui.platform.LocalView

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * 全屏播放页。布局参考 kgka_Music_hl player_page（歌词优先 + 封面模糊背景）
 * 与 NeriPlayer NowPlayingScreen（封面 / 进度条 / 大播控键）。
 */
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val song = PlaybackStateHolder.currentSong ?: return
    val playing = PlaybackStateHolder.isPlaying
    val lyrics = PlaybackStateHolder.lyrics
    val lineIndex = PlaybackStateHolder.lineIndex
    val context = LocalContext.current
    val view = LocalView.current

    BackHandler { onClose() }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101014))) {
        // 封面模糊背景
        AsyncImage(
            model = coverDisplayUrl(song.coverUrl, 300).takeUnless { it.isBlank() },
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(72.dp),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp),
        ) {
            // ── 顶栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "收起",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "正在播放 · 队列 ${PlaybackStateHolder.queueSize} 首",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            // ── 封面 ──
            AsyncImage(
                model = coverDisplayUrl(song.coverUrl, 500).takeUnless { it.isBlank() },
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp)
                    .size(280.dp)
                    .clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop,
            )

            Spacer(Modifier.height(20.dp))

            // ── 歌名 / 歌手 ──
            Text(
                text = song.name,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = song.artists,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            // ── 歌词（滚动 + 当前行高亮） ──
            val lyricState = rememberLazyListState()
            LaunchedEffect(lineIndex, lyrics) {
                if (lyrics.isNotEmpty() && lineIndex >= 0) {
                    runCatching {
                        lyricState.animateScrollToItem(
                            index = lineIndex,
                            scrollOffset = -320,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                if (lyrics.isEmpty()) {
                    Text(
                        text = PlaybackStateHolder.currentLine.ifBlank { "暂无歌词" },
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        state = lyricState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        items(lyrics.size) { i ->
                            val isCurrent = i == lineIndex
                            Text(
                                text = lyrics[i].text,
                                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.45f),
                                fontSize = if (isCurrent) 17.sp else 15.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            // ── 进度条 ──
            val duration = PlaybackStateHolder.durationMs
            var dragFraction by remember { mutableFloatStateOf(Float.NaN) }
            val posMs = PlaybackStateHolder.positionMs
            val sliderValue = if (!dragFraction.isNaN()) dragFraction
            else if (duration > 0) (posMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

            Slider(
                value = sliderValue,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    if (!dragFraction.isNaN() && duration > 0) {
                        view.haptic(HapticLevel.Light)
                        PlayerController.seekTo(context, (dragFraction * duration).toLong())
                    }
                    dragFraction = Float.NaN
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(if (!dragFraction.isNaN() && duration > 0) (dragFraction * duration).toLong() else posMs),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(duration),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }

            // ── 播控 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { view.haptic(HapticLevel.Light); PlayerController.previous(context) }) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.2f))
                        .clickable { view.haptic(HapticLevel.Light); PlayerController.toggle(context) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { view.haptic(HapticLevel.Light); PlayerController.next(context) }) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }
}
