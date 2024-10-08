/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test cases for {@link BluetoothCodecConfig}. */
@RunWith(AndroidJUnit4.class)
public class BluetoothCodecConfigTest {

    @Rule public Expect expect = Expect.create();

    private static final int[] sCodecTypeArray =
            new int[] {
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS,
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID,
            };
    private static final int[] sCodecPriorityArray =
            new int[] {
                BluetoothCodecConfig.CODEC_PRIORITY_DISABLED,
                BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST,
            };
    private static final int[] sSampleRateArray =
            new int[] {
                BluetoothCodecConfig.SAMPLE_RATE_NONE,
                BluetoothCodecConfig.SAMPLE_RATE_44100,
                BluetoothCodecConfig.SAMPLE_RATE_48000,
                BluetoothCodecConfig.SAMPLE_RATE_88200,
                BluetoothCodecConfig.SAMPLE_RATE_96000,
                BluetoothCodecConfig.SAMPLE_RATE_176400,
                BluetoothCodecConfig.SAMPLE_RATE_192000,
            };
    private static final int[] sBitsPerSampleArray =
            new int[] {
                BluetoothCodecConfig.BITS_PER_SAMPLE_NONE,
                BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                BluetoothCodecConfig.BITS_PER_SAMPLE_32,
            };
    private static final int[] sChannelModeArray =
            new int[] {
                BluetoothCodecConfig.CHANNEL_MODE_NONE,
                BluetoothCodecConfig.CHANNEL_MODE_MONO,
                BluetoothCodecConfig.CHANNEL_MODE_STEREO,
            };
    private static final long[] sCodecSpecific1Array =
            new long[] {
                1000, 1001, 1002, 1003,
            };
    private static final long[] sCodecSpecific2Array =
            new long[] {
                2000, 2001, 2002, 2003,
            };
    private static final long[] sCodecSpecific3Array =
            new long[] {
                3000, 3001, 3002, 3003,
            };
    private static final long[] sCodecSpecific4Array =
            new long[] {
                4000, 4001, 4002, 4003,
            };

    private static final int sTotalConfigs =
            sCodecTypeArray.length
                    * sCodecPriorityArray.length
                    * sSampleRateArray.length
                    * sBitsPerSampleArray.length
                    * sChannelModeArray.length
                    * sCodecSpecific1Array.length
                    * sCodecSpecific2Array.length
                    * sCodecSpecific3Array.length
                    * sCodecSpecific4Array.length;

    private int selectCodecType(int configId) {
        int left = sCodecTypeArray.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sCodecTypeArray.length;
        return sCodecTypeArray[index];
    }

    private int selectCodecPriority(int configId) {
        int left = sCodecTypeArray.length * sCodecPriorityArray.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sCodecPriorityArray.length;
        return sCodecPriorityArray[index];
    }

    private int selectSampleRate(int configId) {
        int left = sCodecTypeArray.length * sCodecPriorityArray.length * sSampleRateArray.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sSampleRateArray.length;
        return sSampleRateArray[index];
    }

    private int selectBitsPerSample(int configId) {
        int left =
                sCodecTypeArray.length
                        * sCodecPriorityArray.length
                        * sSampleRateArray.length
                        * sBitsPerSampleArray.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sBitsPerSampleArray.length;
        return sBitsPerSampleArray[index];
    }

    private int selectChannelMode(int configId) {
        int left =
                sCodecTypeArray.length
                        * sCodecPriorityArray.length
                        * sSampleRateArray.length
                        * sBitsPerSampleArray.length
                        * sChannelModeArray.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sChannelModeArray.length;
        return sChannelModeArray[index];
    }

    private long selectCodecSpecific1(int configId) {
        int left =
                sCodecTypeArray.length
                        * sCodecPriorityArray.length
                        * sSampleRateArray.length
                        * sBitsPerSampleArray.length
                        * sChannelModeArray.length
                        * sCodecSpecific1Array.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sCodecSpecific1Array.length;
        return sCodecSpecific1Array[index];
    }

    private long selectCodecSpecific2(int configId) {
        int left =
                sCodecTypeArray.length
                        * sCodecPriorityArray.length
                        * sSampleRateArray.length
                        * sBitsPerSampleArray.length
                        * sChannelModeArray.length
                        * sCodecSpecific1Array.length
                        * sCodecSpecific2Array.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sCodecSpecific2Array.length;
        return sCodecSpecific2Array[index];
    }

    private long selectCodecSpecific3(int configId) {
        int left =
                sCodecTypeArray.length
                        * sCodecPriorityArray.length
                        * sSampleRateArray.length
                        * sBitsPerSampleArray.length
                        * sChannelModeArray.length
                        * sCodecSpecific1Array.length
                        * sCodecSpecific2Array.length
                        * sCodecSpecific3Array.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sCodecSpecific3Array.length;
        return sCodecSpecific3Array[index];
    }

    private long selectCodecSpecific4(int configId) {
        int left =
                sCodecTypeArray.length
                        * sCodecPriorityArray.length
                        * sSampleRateArray.length
                        * sBitsPerSampleArray.length
                        * sChannelModeArray.length
                        * sCodecSpecific1Array.length
                        * sCodecSpecific2Array.length
                        * sCodecSpecific3Array.length
                        * sCodecSpecific4Array.length;
        int right = sTotalConfigs / left;
        int index = configId / right;
        index = index % sCodecSpecific4Array.length;
        return sCodecSpecific4Array[index];
    }

    @Test
    public void testBluetoothCodecConfig_valid_get_methods() {
        for (int config_id = 0; config_id < sTotalConfigs; config_id++) {
            int codec_type = selectCodecType(config_id);
            int codec_priority = selectCodecPriority(config_id);
            int sample_rate = selectSampleRate(config_id);
            int bits_per_sample = selectBitsPerSample(config_id);
            int channel_mode = selectChannelMode(config_id);
            long codec_specific1 = selectCodecSpecific1(config_id);
            long codec_specific2 = selectCodecSpecific2(config_id);
            long codec_specific3 = selectCodecSpecific3(config_id);
            long codec_specific4 = selectCodecSpecific4(config_id);

            BluetoothCodecConfig bcc =
                    buildBluetoothCodecConfig(
                            codec_type,
                            codec_priority,
                            sample_rate,
                            bits_per_sample,
                            channel_mode,
                            codec_specific1,
                            codec_specific2,
                            codec_specific3,
                            codec_specific4);

            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC) {
                expect.that(bcc.isMandatoryCodec()).isTrue();
            } else {
                expect.that(bcc.isMandatoryCodec()).isFalse();
            }

            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC) {
                expect.that(BluetoothCodecConfig.getCodecName(codec_type)).isEqualTo("SBC");
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC) {
                expect.that(BluetoothCodecConfig.getCodecName(codec_type)).isEqualTo("AAC");
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX) {
                expect.that(BluetoothCodecConfig.getCodecName(codec_type)).isEqualTo("aptX");
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD) {
                expect.that(BluetoothCodecConfig.getCodecName(codec_type)).isEqualTo("aptX HD");
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
                expect.that(BluetoothCodecConfig.getCodecName(codec_type)).isEqualTo("LDAC");
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS) {
                expect.that(BluetoothCodecConfig.getCodecName(codec_type)).isEqualTo("Opus");
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
                expect.that(BluetoothCodecConfig.getCodecName(codec_type))
                        .isEqualTo("INVALID CODEC");
            }

            expect.that(bcc.getCodecType()).isEqualTo(codec_type);
            expect.that(bcc.getCodecPriority()).isEqualTo(codec_priority);
            expect.that(bcc.getSampleRate()).isEqualTo(sample_rate);
            expect.that(bcc.getBitsPerSample()).isEqualTo(bits_per_sample);
            expect.that(bcc.getChannelMode()).isEqualTo(channel_mode);
            expect.that(bcc.getCodecSpecific1()).isEqualTo(codec_specific1);
            expect.that(bcc.getCodecSpecific2()).isEqualTo(codec_specific2);
            expect.that(bcc.getCodecSpecific3()).isEqualTo(codec_specific3);
            expect.that(bcc.getCodecSpecific4()).isEqualTo(codec_specific4);
        }
    }

    @Test
    public void testBluetoothCodecConfig_same() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc2_same =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);
        assertThat(bcc2_same).isEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_codec_type() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc3_codec_type =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);
        assertThat(bcc3_codec_type).isNotEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_codec_priority() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc4_codec_priority =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);
        assertThat(bcc4_codec_priority).isNotEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_sample_rate() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc5_sample_rate =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_48000,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);
        assertThat(bcc5_sample_rate).isNotEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_bits_per_sample() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc6_bits_per_sample =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);
        assertThat(bcc6_bits_per_sample).isNotEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_channel_mode() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc7_channel_mode =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_MONO,
                        1000,
                        2000,
                        3000,
                        4000);
        assertThat(bcc7_channel_mode).isNotEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_code_specific_1() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc8_codec_specific1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1001,
                        2000,
                        3000,
                        4000);
        assertThat(bcc8_codec_specific1).isNotEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_code_specific_2() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc9_codec_specific2 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2002,
                        3000,
                        4000);
        assertThat(bcc9_codec_specific2).isNotEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_code_specific_3() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc10_codec_specific3 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3003,
                        4000);
        assertThat(bcc10_codec_specific3).isNotEqualTo(bcc1);
    }

    @Test
    public void testBluetoothCodecConfig_different_code_specific_4() {
        BluetoothCodecConfig bcc1 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4000);

        BluetoothCodecConfig bcc11_codec_specific4 =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        1000,
                        2000,
                        3000,
                        4004);
        assertThat(bcc11_codec_specific4).isNotEqualTo(bcc1);
    }

    private BluetoothCodecConfig buildBluetoothCodecConfig(
            int sourceCodecType,
            int codecPriority,
            int sampleRate,
            int bitsPerSample,
            int channelMode,
            long codecSpecific1,
            long codecSpecific2,
            long codecSpecific3,
            long codecSpecific4) {
        return new BluetoothCodecConfig.Builder()
                .setCodecType(sourceCodecType)
                .setCodecPriority(codecPriority)
                .setSampleRate(sampleRate)
                .setBitsPerSample(bitsPerSample)
                .setChannelMode(channelMode)
                .setCodecSpecific1(codecSpecific1)
                .setCodecSpecific2(codecSpecific2)
                .setCodecSpecific3(codecSpecific3)
                .setCodecSpecific4(codecSpecific4)
                .build();
    }
}
