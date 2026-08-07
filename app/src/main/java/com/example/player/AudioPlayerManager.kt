package com.example.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.example.model.EqualizerPreset
import com.example.model.GlassTheme
import com.example.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sin

enum class RepeatMode {
    OFF, ALL, ONE
}

class AudioPlayerManager(private val context: Context) {

    companion object {
        var activeInstance: AudioPlayerManager? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaPlayer: MediaPlayer? = null
    private var equalizerEffect: Equalizer? = null

    // State Flows
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0)
    val currentPositionMs: StateFlow<Int> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(200000)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    // Sleep Timer (seconds remaining)
    private val _sleepTimerSeconds = MutableStateFlow<Int?>(null)
    val sleepTimerSeconds: StateFlow<Int?> = _sleepTimerSeconds.asStateFlow()

    private var sleepTimerJob: Job? = null

    // Theme & Equalizer
    private val _currentTheme = MutableStateFlow(GlassTheme.PurpleBlue)
    val currentTheme: StateFlow<GlassTheme> = _currentTheme.asStateFlow()

    private val _activeEqPreset = MutableStateFlow(EqualizerPreset.Flat)
    val activeEqPreset: StateFlow<EqualizerPreset> = _activeEqPreset.asStateFlow()

    private val _eqBandGains = MutableStateFlow(listOf(0f, 0f, 0f, 0f, 0f))
    val eqBandGains: StateFlow<List<Float>> = _eqBandGains.asStateFlow()

    // Track Queue
    private var playlistQueue = listOf<Track>()
    private var currentIndex = -1

    private var progressJob: Job? = null

    init {
        activeInstance = this
        startProgressLoop()
    }

    private fun updateServiceNotification() {
        try {
            com.example.widget.MusicWidgetManager.updateAllWidgets(context)
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Failed to update widgets", e)
        }

        val track = _currentTrack.value ?: return
        try {
            com.example.service.MediaPlaybackService.updateNotification(
                context = context,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = _durationMs.value,
                positionMs = _currentPositionMs.value,
                albumArtUri = track.albumArtUri,
                gradientIndex = track.coverGradientIndex,
                isPlaying = _isPlaying.value
            )
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Failed to update notification service", e)
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        playlistQueue = tracks
        if (tracks.isNotEmpty() && startIndex in tracks.indices) {
            playTrackAtIndex(startIndex)
        }
    }

    fun playTrack(track: Track) {
        val index = playlistQueue.indexOfFirst { it.id == track.id }
        if (index != -1) {
            playTrackAtIndex(index)
        } else {
            playlistQueue = listOf(track)
            playTrackAtIndex(0)
        }
    }

    private var currentTrackListeningSeconds: Long = 0L
    private var lastFlushTimeMs: Long = 0L
    var onFlushListeningTimeListener: ((trackId: Long, seconds: Long) -> Unit)? = null
    var onPlaybackStateChanged: ((trackId: Long, positionMs: Long, queueTrackIds: List<Long>) -> Unit)? = null

    fun triggerPlaybackStateSave() {
        val trackId = _currentTrack.value?.id ?: return
        val posMs = _currentPositionMs.value.toLong()
        val queueIds = playlistQueue.map { it.id }
        onPlaybackStateChanged?.invoke(trackId, posMs, queueIds)
    }

    fun restorePlaybackState(tracks: List<Track>, index: Int, positionMs: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        playlistQueue = tracks
        currentIndex = index
        val track = tracks[index]
        _currentTrack.value = track
        _durationMs.value = track.durationSeconds * 1000
        _currentPositionMs.value = positionMs
        _isPlaying.value = false
        updateServiceNotification()
    }

    fun flushListeningTime() {
        val trackId = _currentTrack.value?.id ?: return
        val secondsToFlush = currentTrackListeningSeconds
        if (secondsToFlush > 0) {
            currentTrackListeningSeconds = 0L
            lastFlushTimeMs = System.currentTimeMillis()
            onFlushListeningTimeListener?.invoke(trackId, secondsToFlush)
        }
    }

    private fun playTrackAtIndex(index: Int, startPositionMs: Int = 0) {
        if (index !in playlistQueue.indices) return
        flushListeningTime()
        currentIndex = index
        val track = playlistQueue[index]
        _currentTrack.value = track
        _durationMs.value = track.durationSeconds * 1000
        _currentPositionMs.value = if (startPositionMs > 0) startPositionMs else 0

        stopCurrentMedia()

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                val url = track.audioUrl
                when {
                    url.startsWith("content://") || url.startsWith("file://") || url.startsWith("http://") || url.startsWith("https://") -> {
                        setDataSource(context, Uri.parse(url))
                    }
                    url.startsWith("/") -> {
                        val file = java.io.File(url)
                        if (file.exists()) {
                            setDataSource(context, Uri.fromFile(file))
                        } else {
                            setDataSource(url)
                        }
                    }
                    url.isNotBlank() -> {
                        setDataSource(context, Uri.parse(url))
                    }
                    else -> {
                        Log.e("AudioPlayerManager", "Track audio URL is empty")
                        _isPlaying.value = false
                        return@apply
                    }
                }

                setOnPreparedListener { mp ->
                    setupEqualizer(mp.audioSessionId)
                    if (startPositionMs > 0) {
                        try {
                            mp.seekTo(startPositionMs)
                        } catch (e: Exception) {
                            Log.e("AudioPlayerManager", "Error seeking to start position", e)
                        }
                    }
                    mp.start()
                    _isPlaying.value = true
                    _durationMs.value = mp.duration.takeIf { it > 0 } ?: (track.durationSeconds * 1000)
                    updateServiceNotification()
                    triggerPlaybackStateSave()
                }
                setOnCompletionListener {
                    handleTrackCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("AudioPlayerManager", "MediaPlayer error: what=$what, extra=$extra")
                    _isPlaying.value = false
                    updateServiceNotification()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Failed to prepare MediaPlayer for track: ${track.title}", e)
            _isPlaying.value = false
            updateServiceNotification()
        }
        triggerPlaybackStateSave()
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        flushListeningTime()
        _isPlaying.value = false
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Error pausing MediaPlayer", e)
        }
        updateServiceNotification()
        triggerPlaybackStateSave()
    }

    fun resume() {
        if (_currentTrack.value == null && playlistQueue.isNotEmpty()) {
            playTrackAtIndex(0)
            return
        }
        if (mediaPlayer == null && _currentTrack.value != null) {
            playTrackAtIndex(currentIndex, startPositionMs = _currentPositionMs.value)
            return
        }
        try {
            mediaPlayer?.start()
            _isPlaying.value = true
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Error resuming MediaPlayer", e)
            _isPlaying.value = false
        }
        updateServiceNotification()
        triggerPlaybackStateSave()
    }

    fun seekTo(positionMs: Int) {
        _currentPositionMs.value = positionMs
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Error seeking MediaPlayer", e)
        }
        updateServiceNotification()
        triggerPlaybackStateSave()
    }

    fun nextTrack() {
        if (playlistQueue.isEmpty()) return
        if (_isShuffle.value) {
            val candidates = if (playlistQueue.size > 1) (playlistQueue.indices).filter { it != currentIndex } else playlistQueue.indices.toList()
            val randomIdx = candidates.randomOrNull() ?: 0
            playTrackAtIndex(randomIdx)
        } else {
            val nextIdx = (currentIndex + 1) % playlistQueue.size
            playTrackAtIndex(nextIdx)
        }
    }

    fun previousTrack() {
        if (playlistQueue.isEmpty()) return
        if (_currentPositionMs.value > 3000) {
            seekTo(0)
            return
        }
        val prevIdx = if (currentIndex - 1 < 0) playlistQueue.size - 1 else currentIndex - 1
        playTrackAtIndex(prevIdx)
    }

    fun updateCurrentTrackFavorite(isFavorite: Boolean) {
        val current = _currentTrack.value ?: return
        _currentTrack.value = current.copy(isFavorite = isFavorite)
        playlistQueue = playlistQueue.map {
            if (it.id == current.id) it.copy(isFavorite = isFavorite) else it
        }
        updateServiceNotification()
    }

    fun updateCurrentTrackInfo(title: String, artist: String, album: String = "") {
        val current = _currentTrack.value ?: return
        val newAlbum = if (album.isNotBlank()) album else current.album
        _currentTrack.value = current.copy(title = title, artist = artist, album = newAlbum)
        playlistQueue = playlistQueue.map {
            if (it.id == current.id) it.copy(title = title, artist = artist, album = newAlbum) else it
        }
        updateServiceNotification()
    }

    fun removeTrackFromQueue(trackId: Long) {
        val isCurrentPlaying = _currentTrack.value?.id == trackId
        playlistQueue = playlistQueue.filter { it.id != trackId }
        
        if (isCurrentPlaying) {
            if (playlistQueue.isNotEmpty()) {
                if (currentIndex >= playlistQueue.size) {
                    currentIndex = 0
                }
                playTrackAtIndex(currentIndex)
            } else {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                _isPlaying.value = false
                _currentTrack.value = null
            }
        } else {
            // Adjust currentIndex if necessary
            val currentId = _currentTrack.value?.id
            if (currentId != null) {
                currentIndex = playlistQueue.indexOfFirst { it.id == currentId }
            }
        }
    }

    fun setShuffle(enabled: Boolean) {
        _isShuffle.value = enabled
        updateServiceNotification()
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
        updateServiceNotification()
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        updateServiceNotification()
    }

    fun cyclePlaybackMode() {
        when {
            !_isShuffle.value && _repeatMode.value == RepeatMode.OFF -> {
                // Sequential -> Repeat All
                _isShuffle.value = false
                _repeatMode.value = RepeatMode.ALL
            }
            !_isShuffle.value && _repeatMode.value == RepeatMode.ALL -> {
                // Repeat All -> Repeat One
                _isShuffle.value = false
                _repeatMode.value = RepeatMode.ONE
            }
            !_isShuffle.value && _repeatMode.value == RepeatMode.ONE -> {
                // Repeat One -> Shuffle
                _isShuffle.value = true
                _repeatMode.value = RepeatMode.OFF
            }
            else -> {
                // Shuffle -> Sequential
                _isShuffle.value = false
                _repeatMode.value = RepeatMode.OFF
            }
        }
        updateServiceNotification()
    }

    private fun handleTrackCompletion() {
        flushListeningTime()
        when (_repeatMode.value) {
            RepeatMode.ONE -> playTrackAtIndex(currentIndex)
            RepeatMode.ALL -> nextTrack()
            RepeatMode.OFF -> {
                if (_isShuffle.value) {
                    nextTrack()
                } else {
                    if (currentIndex < playlistQueue.lastIndex) {
                        playTrackAtIndex(currentIndex + 1)
                    } else {
                        _isPlaying.value = false
                        _currentPositionMs.value = 0
                        try {
                            mediaPlayer?.seekTo(0)
                        } catch (e: Exception) {
                            Log.e("AudioPlayerManager", "Error seeking to 0 on complete", e)
                        }
                        updateServiceNotification()
                    }
                }
            }
        }
    }

    // Sleep Timer Logic
    fun setSleepTimerMinutes(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _sleepTimerSeconds.value = null
            return
        }
        val totalSec = minutes * 60
        _sleepTimerSeconds.value = totalSec

        sleepTimerJob = scope.launch(Dispatchers.Main) {
            var remaining = totalSec
            while (remaining > 0 && isActive) {
                delay(1000L)
                remaining--
                _sleepTimerSeconds.value = remaining
            }
            if (remaining <= 0) {
                pause()
                _sleepTimerSeconds.value = null
            }
        }
    }

    // Equalizer logic
    private fun setupEqualizer(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            equalizerEffect?.release()
            equalizerEffect = Equalizer(0, audioSessionId).apply {
                enabled = true
                applyEqualizerToHardware()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Failed to initialize hardware Equalizer effect", e)
        }
    }

    private fun applyEqualizerToHardware() {
        val eq = equalizerEffect ?: return
        try {
            if (!eq.enabled) {
                eq.enabled = true
            }
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minLevel = range.getOrNull(0) ?: -1500
            val maxLevel = range.getOrNull(1) ?: 1500
            val gains = _eqBandGains.value

            for (i in 0 until numBands) {
                if (i in gains.indices) {
                    val gainMb = (gains[i] * 100f).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
                    eq.setBandLevel(i.toShort(), gainMb)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Error applying equalizer gains", e)
        }
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        if (preset.name.equals("Custom", ignoreCase = true)) {
            _activeEqPreset.value = EqualizerPreset.Custom.copy(gains = _eqBandGains.value)
        } else {
            _activeEqPreset.value = preset
            _eqBandGains.value = preset.gains
        }
        applyEqualizerToHardware()
    }

    fun setCustomGains(gains: List<Float>) {
        if (gains.size == 5) {
            _eqBandGains.value = gains
            _activeEqPreset.value = EqualizerPreset.Custom.copy(gains = gains)
            applyEqualizerToHardware()
        }
    }

    fun updateCustomBandGain(bandIndex: Int, gain: Float) {
        val currentGains = _eqBandGains.value.toMutableList()
        if (bandIndex in currentGains.indices) {
            currentGains[bandIndex] = gain
            _eqBandGains.value = currentGains
            _activeEqPreset.value = EqualizerPreset.Custom.copy(gains = currentGains)
            applyEqualizerToHardware()
        }
    }

    // Theme switching
    fun setTheme(theme: GlassTheme) {
        _currentTheme.value = theme
    }

    private fun startProgressLoop() {
        progressJob = scope.launch(Dispatchers.Main) {
            var playSecondCounter = 0
            while (isActive) {
                delay(500L)
                if (_isPlaying.value && mediaPlayer != null) {
                    try {
                        if (mediaPlayer?.isPlaying == true) {
                            _currentPositionMs.value = mediaPlayer?.currentPosition ?: 0
                            playSecondCounter++
                            if (playSecondCounter >= 2) {
                                playSecondCounter = 0
                                currentTrackListeningSeconds++

                                // Throttle DB updates: flush every 10 seconds of continuous playback
                                if (currentTrackListeningSeconds >= 10L || (System.currentTimeMillis() - lastFlushTimeMs) >= 10_000L) {
                                    flushListeningTime()
                                    triggerPlaybackStateSave()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore transient state errors
                    }
                }
            }
        }
    }

    private fun stopCurrentMedia() {
        flushListeningTime()
        try {
            equalizerEffect?.release()
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Error releasing Equalizer", e)
        }
        equalizerEffect = null
        mediaPlayer?.apply {
            try {
                if (isPlaying) {
                    stop()
                }
                release()
            } catch (e: Exception) {}
        }
        mediaPlayer = null
    }

    fun release() {
        flushListeningTime()
        scope.cancel()
        stopCurrentMedia()
    }
}
