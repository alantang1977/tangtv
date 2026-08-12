package tv.danmaku.ijk.media.player;

import android.os.Bundle;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

/* loaded from: classes3.dex */
public class IjkMediaMeta {
    public static final long AV_CH_BACK_CENTER = 256;
    public static final long AV_CH_BACK_LEFT = 16;
    public static final long AV_CH_BACK_RIGHT = 32;
    public static final long AV_CH_FRONT_CENTER = 4;
    public static final long AV_CH_FRONT_LEFT = 1;
    public static final long AV_CH_FRONT_LEFT_OF_CENTER = 64;
    public static final long AV_CH_FRONT_RIGHT = 2;
    public static final long AV_CH_FRONT_RIGHT_OF_CENTER = 128;
    public static final long AV_CH_LAYOUT_2POINT1 = 11;
    public static final long AV_CH_LAYOUT_2_1 = 259;
    public static final long AV_CH_LAYOUT_2_2 = 1539;
    public static final long AV_CH_LAYOUT_3POINT1 = 15;
    public static final long AV_CH_LAYOUT_4POINT0 = 263;
    public static final long AV_CH_LAYOUT_4POINT1 = 271;
    public static final long AV_CH_LAYOUT_5POINT0 = 1543;
    public static final long AV_CH_LAYOUT_5POINT0_BACK = 55;
    public static final long AV_CH_LAYOUT_5POINT1 = 1551;
    public static final long AV_CH_LAYOUT_5POINT1_BACK = 63;
    public static final long AV_CH_LAYOUT_6POINT0 = 1799;
    public static final long AV_CH_LAYOUT_6POINT0_FRONT = 1731;
    public static final long AV_CH_LAYOUT_6POINT1 = 1807;
    public static final long AV_CH_LAYOUT_6POINT1_BACK = 319;
    public static final long AV_CH_LAYOUT_6POINT1_FRONT = 1739;
    public static final long AV_CH_LAYOUT_7POINT0 = 1591;
    public static final long AV_CH_LAYOUT_7POINT0_FRONT = 1735;
    public static final long AV_CH_LAYOUT_7POINT1 = 1599;
    public static final long AV_CH_LAYOUT_7POINT1_WIDE = 1743;
    public static final long AV_CH_LAYOUT_7POINT1_WIDE_BACK = 255;
    public static final long AV_CH_LAYOUT_HEXAGONAL = 311;
    public static final long AV_CH_LAYOUT_MONO = 4;
    public static final long AV_CH_LAYOUT_OCTAGONAL = 1847;
    public static final long AV_CH_LAYOUT_QUAD = 51;
    public static final long AV_CH_LAYOUT_STEREO = 3;
    public static final long AV_CH_LAYOUT_STEREO_DOWNMIX = 1610612736;
    public static final long AV_CH_LAYOUT_SURROUND = 7;
    public static final long AV_CH_LOW_FREQUENCY = 8;
    public static final long AV_CH_LOW_FREQUENCY_2 = 34359738368L;
    public static final long AV_CH_SIDE_LEFT = 512;
    public static final long AV_CH_SIDE_RIGHT = 1024;
    public static final long AV_CH_STEREO_LEFT = 536870912;
    public static final long AV_CH_STEREO_RIGHT = 1073741824;
    public static final long AV_CH_SURROUND_DIRECT_LEFT = 8589934592L;
    public static final long AV_CH_SURROUND_DIRECT_RIGHT = 17179869184L;
    public static final long AV_CH_TOP_BACK_CENTER = 65536;
    public static final long AV_CH_TOP_BACK_LEFT = 32768;
    public static final long AV_CH_TOP_BACK_RIGHT = 131072;
    public static final long AV_CH_TOP_CENTER = 2048;
    public static final long AV_CH_TOP_FRONT_CENTER = 8192;
    public static final long AV_CH_TOP_FRONT_LEFT = 4096;
    public static final long AV_CH_TOP_FRONT_RIGHT = 16384;
    public static final long AV_CH_WIDE_LEFT = 2147483648L;
    public static final long AV_CH_WIDE_RIGHT = 4294967296L;
    public static final int FF_PROFILE_H264_BASELINE = 66;
    public static final int FF_PROFILE_H264_CAVLC_444 = 44;
    public static final int FF_PROFILE_H264_CONSTRAINED = 512;
    public static final int FF_PROFILE_H264_CONSTRAINED_BASELINE = 578;
    public static final int FF_PROFILE_H264_EXTENDED = 88;
    public static final int FF_PROFILE_H264_HIGH = 100;
    public static final int FF_PROFILE_H264_HIGH_10 = 110;
    public static final int FF_PROFILE_H264_HIGH_10_INTRA = 2158;
    public static final int FF_PROFILE_H264_HIGH_422 = 122;
    public static final int FF_PROFILE_H264_HIGH_422_INTRA = 2170;
    public static final int FF_PROFILE_H264_HIGH_444 = 144;
    public static final int FF_PROFILE_H264_HIGH_444_INTRA = 2292;
    public static final int FF_PROFILE_H264_HIGH_444_PREDICTIVE = 244;
    public static final int FF_PROFILE_H264_INTRA = 2048;
    public static final int FF_PROFILE_H264_MAIN = 77;
    public static final String IJKM_KEY_AUDIO_STREAM = "audio";
    public static final String IJKM_KEY_BITRATE = "bitrate";
    public static final String IJKM_KEY_CHANNEL_LAYOUT = "channel_layout";
    public static final String IJKM_KEY_CODEC_LEVEL = "codec_level";
    public static final String IJKM_KEY_CODEC_LONG_NAME = "codec_long_name";
    public static final String IJKM_KEY_CODEC_NAME = "codec_name";
    public static final String IJKM_KEY_CODEC_PIXEL_FORMAT = "codec_pixel_format";
    public static final String IJKM_KEY_CODEC_PROFILE = "codec_profile";
    public static final String IJKM_KEY_CODEC_PROFILE_ID = "codec_profile_id";
    public static final String IJKM_KEY_DURATION_US = "duration_us";
    public static final String IJKM_KEY_FORMAT = "format";
    public static final String IJKM_KEY_FPS_DEN = "fps_den";
    public static final String IJKM_KEY_FPS_NUM = "fps_num";
    public static final String IJKM_KEY_HEIGHT = "height";
    public static final String IJKM_KEY_LANGUAGE = "language";
    public static final String IJKM_KEY_SAMPLE_RATE = "sample_rate";
    public static final String IJKM_KEY_SAR_DEN = "sar_den";
    public static final String IJKM_KEY_SAR_NUM = "sar_num";
    public static final String IJKM_KEY_START_US = "start_us";
    public static final String IJKM_KEY_STREAMS = "streams";
    public static final String IJKM_KEY_TBR_DEN = "tbr_den";
    public static final String IJKM_KEY_TBR_NUM = "tbr_num";
    public static final String IJKM_KEY_TIMEDTEXT_STREAM = "timedtext";
    public static final String IJKM_KEY_TYPE = "type";
    public static final String IJKM_KEY_VIDEO_STREAM = "video";
    public static final String IJKM_KEY_WIDTH = "width";
    public static final String IJKM_VAL_TYPE__AUDIO = "audio";
    public static final String IJKM_VAL_TYPE__TIMEDTEXT = "timedtext";
    public static final String IJKM_VAL_TYPE__UNKNOWN = "unknown";
    public static final String IJKM_VAL_TYPE__VIDEO = "video";
    public long mBitrate;
    public long mDurationUS;
    public String mFormat;
    public Bundle mMediaMeta;
    public long mStartUS;
    public final ArrayList<IjkStreamMeta> mStreams = new ArrayList<>();

    public static class IjkStreamMeta {
        public long mBitrate;
        public long mChannelLayout;
        public String mCodecLongName;
        public String mCodecName;
        public String mCodecProfile;
        public int mFpsDen;
        public int mFpsNum;
        public int mHeight;
        public final int mIndex;
        public String mLanguage;
        public Bundle mMeta;
        public int mSampleRate;
        public int mSarDen;
        public int mSarNum;
        public int mTbrDen;
        public int mTbrNum;
        public String mType;
        public int mWidth;

        public IjkStreamMeta(int i8) {
            this.mIndex = i8;
        }

        public int getChannelCount() {
            long j8 = this.mChannelLayout;
            if (j8 <= 0) {
                return 0;
            }
            if (j8 == 4) {
                return 1;
            }
            if (j8 == 3 || j8 == IjkMediaMeta.AV_CH_LAYOUT_STEREO_DOWNMIX) {
                return 2;
            }
            if (j8 == IjkMediaMeta.AV_CH_LAYOUT_5POINT1 || j8 == 63 || j8 == IjkMediaMeta.AV_CH_LAYOUT_6POINT0 || j8 == IjkMediaMeta.AV_CH_LAYOUT_6POINT0_FRONT || j8 == 311) {
                return 6;
            }
            if (j8 == IjkMediaMeta.AV_CH_LAYOUT_6POINT1 || j8 == 319 || j8 == IjkMediaMeta.AV_CH_LAYOUT_7POINT0 || j8 == IjkMediaMeta.AV_CH_LAYOUT_6POINT1_FRONT || j8 == IjkMediaMeta.AV_CH_LAYOUT_7POINT0_FRONT) {
                return 7;
            }
            if (j8 == IjkMediaMeta.AV_CH_LAYOUT_7POINT1 || j8 == 255 || j8 == IjkMediaMeta.AV_CH_LAYOUT_7POINT1_WIDE || j8 == IjkMediaMeta.AV_CH_LAYOUT_OCTAGONAL) {
                return 8;
            }
            return (int) j8;
        }

        public String getCodecName() {
            return !TextUtils.isEmpty(this.mCodecLongName) ? this.mCodecLongName : !TextUtils.isEmpty(this.mCodecName) ? this.mCodecName : "";
        }

        public int getInt(String str) {
            return getInt(str, 0);
        }

        public int getInt(String str, int i8) {
            String string = getString(str);
            if (TextUtils.isEmpty(string)) {
                return i8;
            }
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException unused) {
                return i8;
            }
        }

        public long getLong(String str) {
            return getLong(str, 0L);
        }

        public long getLong(String str, long j8) {
            String string = getString(str);
            if (TextUtils.isEmpty(string)) {
                return j8;
            }
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException unused) {
                return j8;
            }
        }

        public String getString(String str) {
            return this.mMeta.getString(str);
        }
    }

    private static String convertLang(String str) {
        return "chi".equals(str) ? "zh" : "cze".equals(str) ? "cs" : "dut".equals(str) ? "nl" : "fre".equals(str) ? "fr" : "ger".equals(str) ? "de" : "gre".equals(str) ? "el" : "ice".equals(str) ? "is" : "rum".equals(str) ? "ro" : str;
    }

    public static IjkMediaMeta parse(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        IjkMediaMeta ijkMediaMeta = new IjkMediaMeta();
        ijkMediaMeta.mMediaMeta = bundle;
        ijkMediaMeta.mFormat = ijkMediaMeta.getString(IJKM_KEY_FORMAT);
        ijkMediaMeta.mDurationUS = ijkMediaMeta.getLong(IJKM_KEY_DURATION_US);
        ijkMediaMeta.mStartUS = ijkMediaMeta.getLong(IJKM_KEY_START_US);
        ijkMediaMeta.mBitrate = ijkMediaMeta.getLong(IJKM_KEY_BITRATE);
        ArrayList<Bundle> parcelableArrayList = ijkMediaMeta.getParcelableArrayList(IJKM_KEY_STREAMS);
        if (parcelableArrayList == null) {
            return ijkMediaMeta;
        }
        Iterator<Bundle> it = parcelableArrayList.iterator();
        int i8 = -1;
        while (it.hasNext()) {
            Bundle next = it.next();
            i8++;
            if (next != null) {
                IjkStreamMeta ijkStreamMeta = new IjkStreamMeta(i8);
                ijkStreamMeta.mMeta = next;
                ijkStreamMeta.mType = ijkStreamMeta.getString(IJKM_KEY_TYPE);
                ijkStreamMeta.mLanguage = convertLang(ijkStreamMeta.getString(IJKM_KEY_LANGUAGE));
                if (!TextUtils.isEmpty(ijkStreamMeta.mType)) {
                    ijkStreamMeta.mCodecName = ijkStreamMeta.getString(IJKM_KEY_CODEC_NAME);
                    ijkStreamMeta.mCodecProfile = ijkStreamMeta.getString(IJKM_KEY_CODEC_PROFILE);
                    ijkStreamMeta.mCodecLongName = ijkStreamMeta.getString(IJKM_KEY_CODEC_LONG_NAME);
                    ijkStreamMeta.mBitrate = ijkStreamMeta.getInt(IJKM_KEY_BITRATE);
                    if ("video".equalsIgnoreCase(ijkStreamMeta.mType)) {
                        ijkStreamMeta.mWidth = ijkStreamMeta.getInt(IJKM_KEY_WIDTH);
                        ijkStreamMeta.mHeight = ijkStreamMeta.getInt(IJKM_KEY_HEIGHT);
                        ijkStreamMeta.mFpsNum = ijkStreamMeta.getInt(IJKM_KEY_FPS_NUM);
                        ijkStreamMeta.mFpsDen = ijkStreamMeta.getInt(IJKM_KEY_FPS_DEN);
                        ijkStreamMeta.mTbrNum = ijkStreamMeta.getInt(IJKM_KEY_TBR_NUM);
                        ijkStreamMeta.mTbrDen = ijkStreamMeta.getInt(IJKM_KEY_TBR_DEN);
                        ijkStreamMeta.mSarNum = ijkStreamMeta.getInt(IJKM_KEY_SAR_NUM);
                        ijkStreamMeta.mSarDen = ijkStreamMeta.getInt(IJKM_KEY_SAR_DEN);
                    } else if ("audio".equalsIgnoreCase(ijkStreamMeta.mType)) {
                        ijkStreamMeta.mSampleRate = ijkStreamMeta.getInt(IJKM_KEY_SAMPLE_RATE);
                        ijkStreamMeta.mChannelLayout = ijkStreamMeta.getLong(IJKM_KEY_CHANNEL_LAYOUT);
                    }
                    ijkMediaMeta.mStreams.add(ijkStreamMeta);
                }
            }
        }
        return ijkMediaMeta;
    }

    public String getDurationInline() {
        long j8 = (this.mDurationUS + 5000) / 1000000;
        long j9 = j8 / 60;
        return String.format(Locale.US, "%02d:%02d:%02d", Long.valueOf(j9 / 60), Long.valueOf(j9 % 60), Long.valueOf(j8 % 60));
    }

    public int getInt(String str) {
        return getInt(str, 0);
    }

    public int getInt(String str, int i8) {
        String string = getString(str);
        if (TextUtils.isEmpty(string)) {
            return i8;
        }
        try {
            return Integer.parseInt(string);
        } catch (NumberFormatException unused) {
            return i8;
        }
    }

    public long getLong(String str) {
        return getLong(str, 0L);
    }

    public long getLong(String str, long j8) {
        String string = getString(str);
        if (TextUtils.isEmpty(string)) {
            return j8;
        }
        try {
            return Long.parseLong(string);
        } catch (NumberFormatException unused) {
            return j8;
        }
    }

    public ArrayList<Bundle> getParcelableArrayList(String str) {
        return this.mMediaMeta.getParcelableArrayList(str);
    }

    public String getString(String str) {
        return this.mMediaMeta.getString(str);
    }
}
