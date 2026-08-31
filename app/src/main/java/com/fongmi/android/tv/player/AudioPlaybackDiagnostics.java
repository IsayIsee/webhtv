package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.Format;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AudioPlaybackDiagnostics {

    private AudioPlaybackDiagnostics() {
    }

    public enum DecodeMode {
        HARDWARE,
        SOFTWARE,
        NONE,
        UNKNOWN
    }

    public enum OutputMode {
        PASSTHROUGH,
        PCM,
        OFFLOAD,
        UNKNOWN
    }

    public record Track(String codec, int channels, int sampleRate,
                        String language, int bitrate) {

        public Track {
            codec = clean(codec);
            language = clean(language);
            channels = Math.max(0, channels);
            sampleRate = Math.max(0, sampleRate);
            bitrate = Math.max(0, bitrate);
        }

        public Track withCodec(String codec) {
            return new Track(codec, channels, sampleRate, language, bitrate);
        }

        public boolean available() {
            return !codec.isEmpty() || channels > 0 || sampleRate > 0;
        }

        public static Track empty() {
            return new Track("", 0, 0, "", 0);
        }
    }

    public record Snapshot(Track originalTrack, Track activeTrack,
                           DecodeMode decodeMode, String decoderName,
                           OutputMode outputMode, int outputChannels,
                           int outputSampleRate, boolean tunneling,
                           String downgradeReason) {

        public Snapshot {
            originalTrack = originalTrack == null ? Track.empty() : originalTrack;
            activeTrack = activeTrack == null ? Track.empty() : activeTrack;
            decodeMode = decodeMode == null ? DecodeMode.UNKNOWN : decodeMode;
            decoderName = clean(decoderName);
            outputMode = outputMode == null ? OutputMode.UNKNOWN : outputMode;
            outputChannels = Math.max(0, outputChannels);
            outputSampleRate = Math.max(0, outputSampleRate);
            downgradeReason = clean(downgradeReason);
        }

        public boolean available() {
            return originalTrack.available() || activeTrack.available()
                    || outputMode != OutputMode.UNKNOWN;
        }

        public boolean downgraded() {
            return !downgradeReason.isEmpty() && originalTrack.available()
                    && activeTrack.available();
        }

        public static Snapshot empty() {
            return new Snapshot(Track.empty(), Track.empty(), DecodeMode.UNKNOWN,
                    "", OutputMode.UNKNOWN, 0, 0, false, "");
        }
    }

    public static Track track(Format format, String profile) {
        if (format == null) return Track.empty();
        return track(format.codecs, format.label, profile, format.sampleMimeType,
                format.channelCount, format.sampleRate, format.language,
                Math.max(format.averageBitrate, format.bitrate));
    }

    public static Track track(String codec, String title, String profile,
                              String mimeType, int channels, int sampleRate,
                              String language, int bitrate) {
        return new Track(codecLabel(codec, title, profile, mimeType), channels,
                sampleRate, language, bitrate);
    }

    public static Track passthroughTrack(Track source, String outputFormat) {
        Track base = source == null ? Track.empty() : source;
        String format = lower(outputFormat);
        if (format.contains("spdif-dts") && !format.contains("dtshd")) {
            return base.withCodec("DTS Core");
        }
        if (format.contains("spdif-dtshd")) {
            String codec = lower(base.codec());
            return codec.contains("dts-hd") || codec.contains("dts:x")
                    ? base : base.withCodec("DTS-HD");
        }
        if (format.contains("spdif-truehd")) return base.withCodec("Dolby TrueHD");
        if (format.contains("spdif-eac3")) return base.withCodec("E-AC-3");
        if (format.contains("spdif-ac3")) return base.withCodec("AC-3");
        return base;
    }

    public static Track encodedTrack(Track source, int encoding) {
        Track base = source == null ? Track.empty() : source;
        return switch (encoding) {
            case C.ENCODING_AC3 -> base.withCodec("AC-3");
            case C.ENCODING_E_AC3 -> base.withCodec("E-AC-3");
            case C.ENCODING_E_AC3_JOC -> base.withCodec("E-AC-3 JOC");
            case C.ENCODING_DTS -> base.withCodec("DTS Core");
            case C.ENCODING_DTS_HD -> base.withCodec("DTS-HD");
            case C.ENCODING_DTS_HD_MA -> base.withCodec("DTS-HD MA");
            case C.ENCODING_DOLBY_TRUEHD -> base.withCodec("Dolby TrueHD");
            default -> base;
        };
    }

    public static OutputMode mpvOutputMode(String outputFormat) {
        String value = lower(outputFormat);
        if (value.isEmpty()) return OutputMode.UNKNOWN;
        return value.startsWith("spdif-") ? OutputMode.PASSTHROUGH : OutputMode.PCM;
    }

    public static String format(Snapshot snapshot) {
        if (snapshot == null || !snapshot.available()) return "";
        Track active = snapshot.activeTrack().available()
                ? snapshot.activeTrack() : snapshot.originalTrack();
        String track = formatTrack(active);
        if (snapshot.downgraded()) {
            track = formatTrack(snapshot.originalTrack()) + "/降级" + formatTrack(active);
        }
        List<String> parts = new ArrayList<>();
        add(parts, track);
        switch (snapshot.outputMode()) {
            case PASSTHROUGH -> add(parts, "直通");
            case OFFLOAD -> add(parts, "硬件卸载");
            case PCM -> {
                add(parts, decodeText(snapshot.decodeMode()));
                add(parts, "PCM" + channelSuffix(snapshot.outputChannels()));
            }
            case UNKNOWN -> {
                add(parts, decodeText(snapshot.decodeMode()));
                add(parts, "输出待确认");
            }
        }
        if (snapshot.tunneling()) add(parts, "隧道");
        int sampleRate = snapshot.outputSampleRate() > 0
                ? snapshot.outputSampleRate() : active.sampleRate();
        if (sampleRate > 0) add(parts, sampleRateText(sampleRate));
        if (active.bitrate() > 0) add(parts, bitrateText(active.bitrate()));
        return String.join(" · ", parts);
    }

    public static String channelLabel(int channels) {
        return switch (channels) {
            case 1 -> "1.0";
            case 2 -> "2.0";
            case 3 -> "3.0";
            case 4 -> "4.0";
            case 5 -> "5.0";
            case 6 -> "5.1";
            case 7 -> "6.1";
            case 8 -> "7.1";
            default -> channels > 0 ? channels + "ch" : "";
        };
    }

    public static String codecLabel(String codec, String title, String profile,
                                    String mimeType) {
        String value = lower(join(codec, title, profile, mimeType));
        if (value.contains("dts:x") || value.contains("dtsx")) return "DTS:X";
        if (containsAny(value, "dts-hd ma", "dts hd ma", "master audio")) return "DTS-HD MA";
        if (containsAny(value, "dts-hd hra", "dts hd hra", "high resolution")) return "DTS-HD HRA";
        if (containsAny(value, "dts-hd", "dts_hd", "dtshd", "dts hd")) return "DTS-HD";
        if (value.contains("truehd") || value.contains("true hd")) {
            return value.contains("atmos") ? "Dolby TrueHD Atmos" : "Dolby TrueHD";
        }
        if (containsAny(value, "eac3-joc", "e-ac-3 joc", "eac3_joc")) return "E-AC-3 JOC";
        if (containsAny(value, "eac3", "e-ac-3", "e-ac3")) return "E-AC-3";
        if (containsAny(value, "ac3", "ac-3")) return "AC-3";
        if (value.contains("dts")) return "DTS";
        if (value.contains("aac")) return "AAC";
        if (value.contains("opus")) return "Opus";
        if (value.contains("vorbis")) return "Vorbis";
        if (value.contains("flac")) return "FLAC";
        if (value.contains("alac")) return "ALAC";
        if (containsAny(value, "mp3", "mpeg audio", "audio/mpeg")) return "MP3";
        if (containsAny(value, "pcm", "audio/raw")) return "PCM";
        String fallback = clean(codec);
        if (fallback.isEmpty()) fallback = clean(mimeType);
        return fallback.isEmpty() ? "音频" : fallback.toUpperCase(Locale.US);
    }

    private static String formatTrack(Track track) {
        if (track == null || !track.available()) return "音频";
        return clean(track.codec()) + channelSuffix(track.channels());
    }

    private static String decodeText(DecodeMode mode) {
        return switch (mode) {
            case HARDWARE -> "硬件解码";
            case SOFTWARE -> "软件解码";
            case NONE -> "";
            case UNKNOWN -> "解码待确认";
        };
    }

    private static String channelSuffix(int channels) {
        String value = channelLabel(channels);
        return value.isEmpty() ? "" : " " + value;
    }

    private static String sampleRateText(int sampleRate) {
        if (sampleRate % 1000 == 0) return sampleRate / 1000 + "kHz";
        return String.format(Locale.US, "%.1fkHz", sampleRate / 1000f);
    }

    private static String bitrateText(int bitrate) {
        if (bitrate >= 1_000_000) {
            return String.format(Locale.US, "%.1fMbps", bitrate / 1_000_000f);
        }
        return Math.max(1, bitrate / 1000) + "kbps";
    }

    private static void add(List<String> parts, String value) {
        if (value != null && !value.isBlank()) parts.add(value);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String join(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            String clean = clean(value);
            if (clean.isEmpty()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(clean);
        }
        return builder.toString();
    }

    private static String lower(String value) {
        return clean(value).toLowerCase(Locale.US);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
