package tv.danmaku.ijk.media.player;

/* loaded from: classes3.dex */
public final class IjkTimedText {
    private int[] bitmap;
    private String text;

    public IjkTimedText(String str) {
        this.text = str;
    }

    public IjkTimedText(int[] iArr) {
        this.bitmap = iArr;
    }

    public static IjkTimedText create(String str) {
        return new IjkTimedText(str);
    }

    public static IjkTimedText create(int[] iArr) {
        return new IjkTimedText(iArr);
    }

    public int[] getBitmap() {
        return this.bitmap;
    }

    public String getText() {
        return this.text;
    }
}
