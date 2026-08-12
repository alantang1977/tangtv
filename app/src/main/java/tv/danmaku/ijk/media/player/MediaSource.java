package tv.danmaku.ijk.media.player;

import android.net.Uri;
import java.util.Map;

/* loaded from: classes3.dex */
public class MediaSource {
    private final Map<String, String> headers;
    private final Uri uri;

    public MediaSource(Map<String, String> map, Uri uri) {
        this.headers = map;
        this.uri = uri;
    }

    public Map<String, String> getHeaders() {
        return this.headers;
    }

    public Uri getUri() {
        return this.uri;
    }
}
