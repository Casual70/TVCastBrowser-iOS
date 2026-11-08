package com.tvcast.browser.auto

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Servizio audio MediaBrowserService standard per Android Auto.
 * Permette all'auto di riconoscere TV Cast Browser come sorgente audio legittima,
 * consentendo di ascoltare flussi video e controllare play/pause dai comandi al volante
 * e dal widget multimediale sul cruscotto.
 */
class TvCastMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat

    companion object {
        private const val ROOT_ID = "tvcast_root"
        private const val MEDIA_ID_ACTIVE_STREAM = "tvcast_active_stream"
    }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "TvCastMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                }

                override fun onPause() {
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                }

                override fun onStop() {
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                }
            })

            setSessionToken(sessionToken)
            isActive = true
        }

        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "TV Cast Browser")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Streaming Web Attivo")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Buffer RAM 100 MB")
            .build()

        mediaSession.setMetadata(metadata)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()

        if (parentId == ROOT_ID) {
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_ACTIVE_STREAM)
                .setTitle("TV Cast Browser — Flusso Web")
                .setSubtitle("Riproduzione audio e streaming continuo")
                .setDescription("Buffer RAM 100 MB • Blocco Dominio attivo")
                .build()

            mediaItems.add(
                MediaBrowserCompat.MediaItem(
                    description,
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        }

        result.sendResult(mediaItems)
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}

