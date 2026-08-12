package tv.danmaku.ijk.media.player;

import android.annotation.TargetApi;
import android.media.MediaCodecInfo;
import android.text.TextUtils;
import android.util.Log;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import okhttp3.internal.http2.Http2;

/* loaded from: classes3.dex */
public class IjkMediaCodecInfo {
    public static final int RANK_ACCEPTABLE = 700;
    public static final int RANK_LAST_CHANCE = 600;
    public static final int RANK_MAX = 1000;
    public static final int RANK_NON_STANDARD = 100;
    public static final int RANK_NO_SENSE = 0;
    public static final int RANK_SECURE = 300;
    public static final int RANK_SOFTWARE = 200;
    public static final int RANK_TESTED = 800;
    private static final String TAG = "IjkMediaCodecInfo";
    private static Map<String, Integer> sKnownCodecList;
    public MediaCodecInfo mCodecInfo;
    public String mMimeType;
    public int mRank = 0;

    private static synchronized Map<String, Integer> getKnownCodecList() {
        Map<String, Integer> map = sKnownCodecList;
        if (map != null) {
            return map;
        }
        TreeMap treeMap = new TreeMap(String.CASE_INSENSITIVE_ORDER);
        sKnownCodecList = treeMap;
        treeMap.put("OMX.Nvidia.h264.decode", 800);
        sKnownCodecList.put("OMX.Nvidia.h264.decode.secure", Integer.valueOf(RANK_SECURE));
        sKnownCodecList.put("OMX.Intel.hw_vd.h264", Integer.valueOf(IMediaPlayer.MEDIA_INFO_NOT_SEEKABLE));
        sKnownCodecList.put("OMX.Intel.VideoDecoder.AVC", 800);
        sKnownCodecList.put("OMX.qcom.video.decoder.avc", 800);
        sKnownCodecList.put("OMX.qcom.video.decoder.avc.secure", Integer.valueOf(RANK_SECURE));
        sKnownCodecList.put("OMX.qcom.video.decoder.avc.low_latency", Integer.valueOf(RANK_MAX));
        sKnownCodecList.put("OMX.qcom.video.decoder.hevc", 800);
        sKnownCodecList.put("OMX.qcom.video.decoder.hevc.secure", Integer.valueOf(RANK_SECURE));
        sKnownCodecList.put("OMX.qcom.video.decoder.hevc.low_latency", Integer.valueOf(RANK_MAX));
        sKnownCodecList.put("OMX.qcom.video.decoder.vp9", 800);
        sKnownCodecList.put("OMX.qcom.video.decoder.vp9.secure", Integer.valueOf(RANK_SECURE));
        sKnownCodecList.put("OMX.qcom.video.decoder.vp9.low_latency", Integer.valueOf(RANK_MAX));
        sKnownCodecList.put("OMX.ittiam.video.decoder.avc", 0);
        sKnownCodecList.put("OMX.SEC.avc.dec", 800);
        sKnownCodecList.put("OMX.SEC.AVC.Decoder", 799);
        sKnownCodecList.put("OMX.SEC.avcdec", 798);
        sKnownCodecList.put("OMX.SEC.avc.sw.dec", 200);
        sKnownCodecList.put("OMX.Exynos.avc.dec", 800);
        sKnownCodecList.put("OMX.Exynos.AVC.Decoder", 799);
        sKnownCodecList.put("OMX.k3.video.decoder.avc", 800);
        sKnownCodecList.put("OMX.IMG.MSVDX.Decoder.AVC", 800);
        sKnownCodecList.put("OMX.TI.DUCATI1.VIDEO.DECODER", 800);
        sKnownCodecList.put("OMX.rk.video_decoder.avc", 800);
        sKnownCodecList.put("OMX.amlogic.avc.decoder.awesome", 800);
        sKnownCodecList.put("OMX.amlogic.avs.decoder.awesome", 800);
        sKnownCodecList.put("OMX.amlogic.avs2.decoder.awesome", 800);
        sKnownCodecList.put("OMX.MARVELL.VIDEO.HW.CODA7542DECODER", 800);
        sKnownCodecList.put("OMX.MARVELL.VIDEO.H264DECODER", 200);
        sKnownCodecList.put("OMX.sprd.h264.decoder", 800);
        sKnownCodecList.put("OMX.sprd.soft.h264.decoder", 200);
        return sKnownCodecList;
    }

    public static String getLevelName(int i8) {
        if (i8 == 1) {
            return "1";
        }
        if (i8 == 2) {
            return "1b";
        }
        switch (i8) {
            case 4:
                return "11";
            case 8:
                return "12";
            case 16:
                return "13";
            case 32:
                return "2";
            case 64:
                return "21";
            case 128:
                return "22";
            case 256:
                return "3";
            case IjkMediaMeta.FF_PROFILE_H264_CONSTRAINED /* 512 */:
                return "31";
            case 1024:
                return "32";
            case IjkMediaMeta.FF_PROFILE_H264_INTRA /* 2048 */:
                return "4";
            case 4096:
                return "41";
            case 8192:
                return "42";
            case Http2.INITIAL_MAX_FRAME_SIZE /* 16384 */:
                return "5";
            case 32768:
                return "51";
            case 65536:
                return "52";
            default:
                return "0";
        }
    }

    public static String getProfileLevelName(int i8, int i9) {
        Locale locale = Locale.US;
        return " " + getProfileName(i8) + " Profile Level " + getLevelName(i9) + " (" + i8 + "," + i9 + ")";
    }

    public static String getProfileName(int i8) {
        return i8 != 1 ? i8 != 2 ? i8 != 4 ? i8 != 8 ? i8 != 16 ? i8 != 32 ? i8 != 64 ? "Unknown" : "High444" : "High422" : "High10" : "High" : "Extends" : "Main" : "Baseline";
    }

    @TargetApi(16)
    public static IjkMediaCodecInfo setupCandidate(MediaCodecInfo mediaCodecInfo, String str) {
        int iIntValue;
        if (mediaCodecInfo == null) {
            return null;
        }
        String name = mediaCodecInfo.getName();
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        String lowerCase = name.toLowerCase(Locale.US);
        if (!lowerCase.startsWith("omx.")) {
            iIntValue = 100;
        } else if (lowerCase.startsWith("omx.pv") || lowerCase.startsWith("omx.google.") || lowerCase.startsWith("omx.ffmpeg.") || lowerCase.startsWith("omx.k3.ffmpeg.") || lowerCase.startsWith("omx.avcodec.")) {
            iIntValue = 200;
        } else if (lowerCase.startsWith("omx.ittiam.")) {
            iIntValue = 0;
        } else if (lowerCase.startsWith("omx.mtk.")) {
            iIntValue = 800;
        } else {
            Integer num = getKnownCodecList().get(lowerCase);
            if (num != null) {
                iIntValue = num.intValue();
            } else {
                iIntValue = RANK_LAST_CHANCE;
                try {
                    if (mediaCodecInfo.getCapabilitiesForType(str) != null) {
                        iIntValue = 700;
                    }
                } catch (Throwable unused) {
                }
            }
        }
        IjkMediaCodecInfo ijkMediaCodecInfo = new IjkMediaCodecInfo();
        ijkMediaCodecInfo.mCodecInfo = mediaCodecInfo;
        ijkMediaCodecInfo.mRank = iIntValue;
        ijkMediaCodecInfo.mMimeType = str;
        return ijkMediaCodecInfo;
    }

    @TargetApi(16)
    public void dumpProfileLevels(String str) {
        int iMax;
        MediaCodecInfo.CodecProfileLevel[] codecProfileLevelArr;
        try {
            MediaCodecInfo.CodecCapabilities capabilitiesForType = this.mCodecInfo.getCapabilitiesForType(str);
            int i8 = 0;
            if (capabilitiesForType == null || (codecProfileLevelArr = capabilitiesForType.profileLevels) == null) {
                iMax = 0;
            } else {
                int length = codecProfileLevelArr.length;
                int iMax2 = 0;
                iMax = 0;
                while (i8 < length) {
                    MediaCodecInfo.CodecProfileLevel codecProfileLevel = codecProfileLevelArr[i8];
                    if (codecProfileLevel != null) {
                        iMax2 = Math.max(iMax2, codecProfileLevel.profile);
                        iMax = Math.max(iMax, codecProfileLevel.level);
                    }
                    i8++;
                }
                i8 = iMax2;
            }
            Locale locale = Locale.US;
            Log.i(TAG, getProfileLevelName(i8, iMax));
        } catch (Throwable unused) {
            Log.i(TAG, "profile-level: exception");
        }
    }
}
