package app.castv.player

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.castv.data.Settings
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shared, changeable audio delay in microseconds (positive = audio later). */
class AudioOffset { @Volatile var us: Long = 0 }

private class OffsetAudioSink(sink: AudioSink, private val offset: AudioOffset) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val pos = super.getCurrentPositionUs(sourceEnded)
        return if (pos == AudioSink.CURRENT_POSITION_NOT_SET) pos else (pos + offset.us).coerceAtLeast(0)
    }
}

object PlayerFactory {
    fun create(context: Context, s: Settings, offset: AudioOffset, forMultiview: Boolean = false): ExoPlayer {
        val passthrough = s.audioPassthrough
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val b = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                @Suppress("DEPRECATION")
                if (!passthrough) b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                return OffsetAudioSink(b.build(), offset)
            }
        }
        renderers.setExtensionRendererMode(
            if (s.audioDecoder == "software") DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )
        renderers.setEnableDecoderFallback(true)
        if (s.videoDecoder == "software") renderers.setMediaCodecSelector(softwareVideoFirst)
        if (s.amlogicFix) renderers.forceDisableMediaCodecAsynchronousQueueing()

        val (minMs, maxMs, startMs) = when (s.bufferSize) {
            "small" -> Triple(2_000, 15_000, 500)
            "large" -> Triple(15_000, 60_000, 3_000)
            "very_large" -> Triple(30_000, 120_000, 6_000)
            else -> Triple(8_000, 30_000, 1_500)
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, startMs, startMs * 2)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val tracks = DefaultTrackSelector(context)
        tracks.setParameters(
            tracks.buildUponParameters()
                .setTunnelingEnabled(s.tunneled && !forMultiview)
                .setPreferredAudioLanguage(s.preferredAudioLanguage.ifEmpty { null })
                .setPreferredTextLanguage(s.preferredSubtitleLanguage.ifEmpty { null })
                .setSelectUndeterminedTextLanguage(s.subtitlesAllChannels)
                .setPreferredTextRoleFlags(if (s.subtitlesAllChannels) C.ROLE_FLAG_CAPTION else 0)
                .setIgnoredTextSelectionFlags(if (s.subtitlesAllChannels) 0 else C.SELECTION_FLAG_DEFAULT)
                .apply { if (forMultiview) setMaxVideoSize(1280, 720) }
                .build()
        )

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(load)
            .setTrackSelector(tracks)
            .setVideoChangeFrameRateStrategy(
                if (s.autoFrameRate && !s.amlogicFix) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private val softwareVideoFirst = MediaCodecSelector { mime, secure, tunneling ->
        val list = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        if (MimeTypes.isVideo(mime)) list.sortedBy { if (it.softwareOnly) 0 else 1 } else list
    }
}

/** Auto frame rate: switches the TV's refresh rate (and optionally resolution) to match the video. */
class DisplayModeSwitcher(private val activity: Activity) {
    private var applied = false

    fun apply(frameRate: Float, videoHeight: Int, s: Settings) {
        if (!s.autoFrameRate || frameRate <= 0f) return
        if (s.afrOnly5060 && !isPalNtsc(frameRate)) return
        val display = currentDisplay() ?: return
        val current = display.mode
        val modes = display.supportedModes.toList()
        val sameSize = modes.filter {
            if (s.switchResolution && videoHeight > 0) true
            else it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        fun matches(m: Display.Mode): Boolean {
            val ratio = m.refreshRate / frameRate
            return abs(ratio - ratio.roundToInt()) < 0.01f && ratio.roundToInt() >= 1
        }
        val candidates = sameSize.filter(::matches)
        if (candidates.isEmpty()) return
        val best = candidates.sortedWith(
            compareBy<Display.Mode> {
                if (s.switchResolution && videoHeight > 0) abs(it.physicalHeight - targetHeight(videoHeight, current.physicalHeight)) else 0
            }.thenBy { (it.refreshRate / frameRate).roundToInt() }
        ).first()
        if (best.modeId == current.modeId) return
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = best.modeId
        activity.window.attributes = attrs
        applied = true
    }

    fun reset() {
        if (!applied) return
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = 0
        activity.window.attributes = attrs
        applied = false
    }

    private fun targetHeight(video: Int, screen: Int) = when {
        video >= 2000 -> 2160
        video >= 1000 -> 1080
        video >= 700 -> 720
        else -> screen.coerceAtMost(1080)
    }

    private fun isPalNtsc(fps: Float) = listOf(25f, 50f, 29.97f, 30f, 59.94f, 60f).any { abs(it - fps) < 0.05f }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
}
