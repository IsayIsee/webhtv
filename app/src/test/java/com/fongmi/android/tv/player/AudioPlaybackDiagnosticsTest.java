package com.fongmi.android.tv.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioPlaybackDiagnosticsTest {

    @Test
    public void formatsDtsHdCoreDowngradeAsCurrentPassthrough() {
        AudioPlaybackDiagnostics.Track source = track("DTS-HD MA", 6, 48_000, 4_000_000);
        AudioPlaybackDiagnostics.Snapshot snapshot = new AudioPlaybackDiagnostics.Snapshot(
                source, source.withCodec("DTS Core"),
                AudioPlaybackDiagnostics.DecodeMode.NONE, "",
                AudioPlaybackDiagnostics.OutputMode.PASSTHROUGH,
                6, 48_000, false, "dts-hd-core");

        assertEquals("DTS-HD MA 5.1/降级DTS Core 5.1 · 直通 · 48kHz · 4.0Mbps",
                AudioPlaybackDiagnostics.format(snapshot));
    }

    @Test
    public void formatsAutomaticStereoTrackDowngradeAndSoftwarePcm() {
        AudioPlaybackDiagnostics.Snapshot snapshot = new AudioPlaybackDiagnostics.Snapshot(
                track("DTS-HD MA", 6, 48_000, 4_000_000),
                track("AAC", 2, 48_000, 192_000),
                AudioPlaybackDiagnostics.DecodeMode.SOFTWARE, "aac",
                AudioPlaybackDiagnostics.OutputMode.PCM,
                2, 48_000, false, "same-language-stereo");

        assertEquals("DTS-HD MA 5.1/降级AAC 2.0 · 软件解码 · PCM 2.0 · 48kHz · 192kbps",
                AudioPlaybackDiagnostics.format(snapshot));
    }

    @Test
    public void formatsObservedHardwarePcm() {
        AudioPlaybackDiagnostics.Track track = track("E-AC-3", 6, 48_000, 768_000);
        AudioPlaybackDiagnostics.Snapshot snapshot = new AudioPlaybackDiagnostics.Snapshot(
                track, track, AudioPlaybackDiagnostics.DecodeMode.HARDWARE,
                "c2.vendor.eac3.decoder", AudioPlaybackDiagnostics.OutputMode.PCM,
                6, 48_000, false, "");

        assertEquals("E-AC-3 5.1 · 硬件解码 · PCM 5.1 · 48kHz · 768kbps",
                AudioPlaybackDiagnostics.format(snapshot));
    }

    @Test
    public void formatsOffloadWithoutClaimingPassthrough() {
        AudioPlaybackDiagnostics.Track track = track("E-AC-3", 6, 48_000, 768_000);
        AudioPlaybackDiagnostics.Snapshot snapshot = new AudioPlaybackDiagnostics.Snapshot(
                track, track, AudioPlaybackDiagnostics.DecodeMode.NONE, "",
                AudioPlaybackDiagnostics.OutputMode.OFFLOAD,
                6, 48_000, false, "");

        assertEquals("E-AC-3 5.1 · 硬件卸载 · 48kHz · 768kbps",
                AudioPlaybackDiagnostics.format(snapshot));
    }

    @Test
    public void mapsMpvOutputAndDtsCoreTrack() {
        AudioPlaybackDiagnostics.Track source = track("DTS-HD MA", 6, 48_000, 0);

        assertEquals(AudioPlaybackDiagnostics.OutputMode.PASSTHROUGH,
                AudioPlaybackDiagnostics.mpvOutputMode("spdif-dts"));
        assertEquals("DTS Core",
                AudioPlaybackDiagnostics.passthroughTrack(source, "spdif-dts").codec());
        assertEquals(AudioPlaybackDiagnostics.OutputMode.PCM,
                AudioPlaybackDiagnostics.mpvOutputMode("float"));
    }

    @Test
    public void mapsConsumerChannelLabels() {
        assertEquals("2.0", AudioPlaybackDiagnostics.channelLabel(2));
        assertEquals("5.1", AudioPlaybackDiagnostics.channelLabel(6));
        assertEquals("7.1", AudioPlaybackDiagnostics.channelLabel(8));
    }

    private static AudioPlaybackDiagnostics.Track track(
            String codec, int channels, int sampleRate, int bitrate) {
        return new AudioPlaybackDiagnostics.Track(codec, channels, sampleRate, "", bitrate);
    }
}
