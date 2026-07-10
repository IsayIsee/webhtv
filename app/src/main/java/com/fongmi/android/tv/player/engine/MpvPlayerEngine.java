package com.fongmi.android.tv.player.engine;

import androidx.media3.common.MimeTypes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.lut.MpvLutShader;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.SpiderDebug;

import java.util.List;
import java.util.concurrent.TimeUnit;

import is.xyz.mpv.MPVLib;

@UnstableApi
public class MpvPlayerEngine implements PlayerEngine {

    private MpvPlayer player;
    private PlaySpec spec;
    private boolean playWhenReady;
    private boolean retriedFormat;
    private int decode;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this.decode = decode;
        this.player = buildPlayer(listener);
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        player.release();
        SpiderDebug.log("player-engine", "rebuild mpv decode=%d", decode);
        return player = buildPlayer(listener);
    }

    @Override
    public boolean isRepeatOne() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    @Override
    public void setRepeatOne(boolean repeat) {
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        start(spec, true);
    }

    @Override
    public void start(PlaySpec spec, boolean playWhenReady) {
        start(spec, 0, playWhenReady);
    }

    @Override
    public void start(PlaySpec spec, long position, boolean playWhenReady) {
        this.spec = spec;
        this.playWhenReady = playWhenReady;
        this.retriedFormat = false;
        SpiderDebug.log("player-engine", "start mpv decode=%d position=%d play=%s url=%s headers=%s", decode, position, playWhenReady, spec.getUrl(), spec.getHeaders());
        MediaItem item = ExoUtil.getMediaItem(spec, decode);
        if (position > 0) player.setMediaItem(item, position);
        else player.setMediaItem(item);
        player.prepare();
        if (playWhenReady) player.play();
        else player.pause();
    }

    @Override
    public void restart(PlaySpec spec, long position, boolean playWhenReady) {
        player.stop();
        start(spec, position, playWhenReady);
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player.getDuration() < TimeUnit.MINUTES.toMillis(1) || player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        return player.getDuration() > TimeUnit.MINUTES.toMillis(1) && !player.isCurrentMediaItemLive();
    }

    @Override
    public void setTrack(List<Track> tracks) {
        for (Track track : tracks) {
            if (track.isDisabled() && track.getType() == C.TRACK_TYPE_TEXT) {
                player.setTrackSelection(C.TRACK_TYPE_TEXT, "no");
                continue;
            }
            String id = findMpvTrackId(track);
            if (id != null) player.setTrackSelection(track.getType(), id);
        }
    }

    @Override
    public void resetTrack() {
        player.resetTrackSelection();
    }

    @Override
    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracksSnapshot();
    }

    @Override
    public Format getVideoFormat() {
        return TrackUtil.selectedFormat(getCurrentTracks(), C.TRACK_TYPE_VIDEO);
    }

    @Override
    public boolean supportsNativeLut() {
        return true;
    }

    @Override
    public void setNativeLutShader(MpvLutShader shader) {
        player.setLutShader(shader);
    }

    @Override
    public PlayerCacheState getCacheState() {
        return player.getCacheState();
    }

    @Override
    public String getRenderDiagnostics() {
        return player.getRenderDiagnostics();
    }

    @Override
    public String getRuntimeDiagnostics() {
        return player.getRuntimeDiagnostics();
    }

    @Override
    public long getDroppedFrames() {
        return player.getDroppedFrames();
    }

    @Override
    public boolean supportsSubtitleStyle() {
        return true;
    }

    @Override
    public String getAudioSpdifCodecs() {
        return player.getAudioSpdifCodecs();
    }

    @Override
    public void setSubtitleStyle(float textSize, float position) {
        player.setSubtitleStyle(textSize, position);
    }

    @Override
    public boolean supportsSecondarySubtitle() {
        return true;
    }

    @Override
    public boolean isSecondarySubtitleSelected(Format format) {
        return format != null && player.isSecondarySubtitleSelected(parseMpvTrackId(format.id));
    }

    @Override
    public void setSecondarySubtitleTrack(Track track) {
        if (track == null) return;
        if (track.isDisabled() && track.getType() == C.TRACK_TYPE_TEXT) {
            player.setSecondarySubtitleTrackSelection("no");
            return;
        }
        String id = findMpvTrackId(track);
        if (id != null) player.setSecondarySubtitleTrackSelection(id);
    }

    @Override
    public boolean haveTitle() {
        return !getCurrentMediaEditions().isEmpty();
    }

    @Override
    public List<MediaEdition> getCurrentMediaEditions() {
        return player.getCurrentMediaEditions();
    }

    @Override
    public boolean selectEdition(MediaEdition edition) {
        return player.selectEdition(edition);
    }

    private String findMpvTrackId(Track track) {
        if (track == null || track.getFormat() == null) return null;
        for (Tracks.Group group : getCurrentTracks().getGroups()) {
            if (group.getType() != track.getType()) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                if (!track.getFormat().equals(PlayerHelper.describeFormat(format))) continue;
                return parseMpvTrackId(format.id);
            }
        }
        return null;
    }

    private String parseMpvTrackId(String id) {
        if (id == null) return null;
        int index = id.indexOf(':');
        return index >= 0 && index + 1 < id.length() ? id.substring(index + 1) : id;
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        String message = e.getMessage();
        if (startsWith(message, MpvPlayer.ERROR_HLS_PLAYBACK_FAILED)) return ResUtil.getString(R.string.error_play_mpv_hls_unsupported);
        if (startsWith(message, MpvPlayer.ERROR_LOAD_FAILED)) return ResUtil.getString(R.string.error_play_mpv_load_failed);
        if (startsWith(message, MpvPlayer.ERROR_NETWORK_FAILED)) return ResUtil.getString(R.string.error_play_mpv_network_failed);
        if (startsWith(message, MpvPlayer.ERROR_DRM_UNSUPPORTED)) return ResUtil.getString(R.string.error_play_mpv_drm_unsupported);
        if (startsWith(message, MpvPlayer.ERROR_UNEXPECTED_IMAGE)) return ResUtil.getString(R.string.error_play_mpv_unexpected_image);
        if (startsWith(message, MpvPlayer.ERROR_NO_AV_DATA)) return ResUtil.getString(R.string.error_play_mpv_no_av);
        if (startsWith(message, MpvPlayer.ERROR_INVALID_MEDIA_DATA)) return ResUtil.getString(R.string.error_play_mpv_invalid_data);
        if (startsWith(message, MpvPlayer.ERROR_DECODE_FAILED)) return ResUtil.getString(R.string.error_play_mpv_decode_failed);
        if (startsWith(message, MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED)) return ResUtil.getString(R.string.error_play_mpv_video_output);
        return e.getMessage();
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        SpiderDebug.log("player-engine", "handleError mpv code=%d message=%s format=%s retried=%s url=%s", e.errorCode, e.getMessage(), spec == null ? null : spec.getFormat(), retriedFormat, spec == null ? null : spec.getUrl());
        if (shouldRetryFormat(e)) return retryFormat();
        return ErrorAction.FATAL;
    }

    private boolean shouldRetryFormat(PlaybackException e) {
        if (retriedFormat || spec == null || spec.getFormat() != null) return false;
        String message = e.getMessage();
        if (isTerminalMpvError(message)) return false;
        return e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    private ErrorAction retryFormat() {
        retriedFormat = true;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        long position = Math.max(0, player.getCurrentPosition());
        SpiderDebug.log("player-engine", "retryFormat mpv newFormat=%s position=%d", spec.getFormat(), position);
        player.stop();
        MediaItem item = ExoUtil.getMediaItem(spec, decode);
        if (position > 0) player.setMediaItem(item, position);
        else player.setMediaItem(item);
        player.prepare();
        if (playWhenReady) player.play();
        else player.pause();
        return ErrorAction.RECOVERED;
    }

    private boolean isTerminalMpvError(String message) {
        return startsWith(message, MpvPlayer.ERROR_HLS_PLAYBACK_FAILED)
                || startsWith(message, MpvPlayer.ERROR_NETWORK_FAILED)
                || startsWith(message, MpvPlayer.ERROR_DRM_UNSUPPORTED)
                || startsWith(message, MpvPlayer.ERROR_UNEXPECTED_IMAGE)
                || startsWith(message, MpvPlayer.ERROR_NO_AV_DATA)
                || startsWith(message, MpvPlayer.ERROR_INVALID_MEDIA_DATA)
                || startsWith(message, MpvPlayer.ERROR_DECODE_FAILED)
                || startsWith(message, MpvPlayer.ERROR_VIDEO_OUTPUT_FAILED);
    }

    private boolean startsWith(String message, String prefix) {
        return message != null && message.startsWith(prefix);
    }

    private MpvPlayer buildPlayer(Player.Listener listener) {
        MpvPlayer player = new MpvPlayer(App.get(), buildConfig());
        player.addListener(listener);
        return player;
    }

    private MpvPlayerConfig buildConfig() {
        MpvConfigStore.ensureReady();
        boolean requestVulkan = PlayerSetting.getMpvRender() == PlayerSetting.MPV_RENDER_VULKAN;
        boolean nativeVulkan = MPVLib.isBundledVulkanEnabled(App.get());
        boolean deviceVulkan = MPVLib.isDeviceVulkan13Capable(App.get());
        boolean useVulkan = requestVulkan && nativeVulkan && deviceVulkan;
        if (requestVulkan && !useVulkan) SpiderDebug.log("player-engine", "mpv render requested=vulkan but unavailable native=%s device=%s; fallback=opengl", nativeVulkan, deviceVulkan);
        SpiderDebug.log("player-engine", "mpv render requested=%s nativeVulkan=%s deviceVulkan=%s actual=%s", requestVulkan ? "vulkan" : "opengl", nativeVulkan, deviceVulkan, useVulkan ? "vulkan" : "opengl");
        MpvPlayerConfig.Builder builder = MpvPlayerConfig.builder(App.get())
                .configDir(MpvConfigStore.configDir())
                .hwdec(decode == HARD ? "mediacodec,mediacodec-copy" : "no")
                .audioSpdif(resolveAudioSpdifCodecs())
                .demuxerMaxBytes(getDemuxerMaxBytes())
                .demuxerMaxBackBytes(getDemuxerMaxBackBytes())
                .cacheSeconds(getDemuxerReadAheadSeconds())
                .demuxerReadaheadSeconds(getDemuxerReadAheadSeconds());
        if (useVulkan) {
            builder.vo("gpu-next")
                    .gpuContext("androidvk")
                    .gpuApi("vulkan")
                    .openglEs(false);
        }
        return builder.build();
    }

    private String resolveAudioSpdifCodecs() {
        if (!PlayerSetting.isAudioPassThrough()) return "";
        return MpvAudioCapabilities.getAudioSpdifCodecs(App.get());
    }

    private long getDemuxerMaxBytes() {
        int bytes = PlayerSetting.getBufferBytes();
        return bytes > 0 ? bytes : MpvPlayerConfig.DEFAULT_DEMUXER_BYTES;
    }

    private long getDemuxerMaxBackBytes() {
        if (PlayerSetting.getBackBufferMs() <= 0) return 0;
        long forward = getDemuxerMaxBytes();
        return switch (PlayerSetting.getBackBufferOption()) {
            case 1 -> Math.max(16L * 1024 * 1024, forward / 4);
            case 2 -> Math.max(32L * 1024 * 1024, forward / 2);
            case 3 -> forward;
            default -> 0;
        };
    }

    private int getDemuxerReadAheadSeconds() {
        return Math.min(60, Math.max(15, PlayerSetting.getBuffer() * 3));
    }
}
