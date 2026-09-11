package com.qing.hachimi.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.qing.hachimi.data.model.Song
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * UI 侧播放控制入口。
 */
object PlayerController {

    private val json = Json { ignoreUnknownKeys = true }

    fun playQueue(context: Context, songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        val safeIndex = index.coerceIn(0, songs.size - 1)
        val intent = Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY_QUEUE)
            .putExtra(PlaybackService.EXTRA_QUEUE_JSON, json.encodeToString(ListSerializer(Song.serializer()), songs))
            .putExtra(PlaybackService.EXTRA_INDEX, safeIndex)
        ContextCompat.startForegroundService(context, intent)
    }

    fun playOne(context: Context, song: Song) = playQueue(context, listOf(song), 0)

    fun toggle(context: Context) = send(context, PlaybackService.ACTION_TOGGLE)

    fun next(context: Context) = send(context, PlaybackService.ACTION_NEXT)

    fun previous(context: Context) = send(context, PlaybackService.ACTION_PREV)

    fun stop(context: Context) = send(context, PlaybackService.ACTION_STOP)

    private fun send(context: Context, action: String) {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }
}
