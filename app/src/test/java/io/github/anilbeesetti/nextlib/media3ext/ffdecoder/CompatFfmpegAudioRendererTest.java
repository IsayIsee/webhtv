package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

public class CompatFfmpegAudioRendererTest {

    @Test
    public void supportedMultichannelPcm_isPreserved() {
        assertEquals(6, CompatFfmpegAudioRenderer.resolveOutputChannelCount(6, true, true));
    }

    @Test
    public void unsupportedMultichannelPcm_downmixesToStereo() {
        assertEquals(2, CompatFfmpegAudioRenderer.resolveOutputChannelCount(6, false, true));
    }

    @Test
    public void noSupportedPcmOutput_rejectsFormat() {
        assertEquals(Format.NO_VALUE, CompatFfmpegAudioRenderer.resolveOutputChannelCount(6, false, false));
    }

    @Test
    public void av3aManifestMime_isRecognizedByMedia3() {
        assertEquals(MimeTypes.AUDIO_AV3A, MimeTypes.getMediaMimeType("av3a.02"));
    }
}
