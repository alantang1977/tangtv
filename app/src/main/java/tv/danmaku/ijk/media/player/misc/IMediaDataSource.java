package tv.danmaku.ijk.media.player.misc;

/* loaded from: classes3.dex */
public interface IMediaDataSource {
    void close();

    long getSize();

    int readAt(long j8, byte[] bArr, int i8, int i9);
}
