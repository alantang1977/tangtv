package tv.danmaku.ijk.media.player;

import android.util.Log;
import java.util.Arrays;

/* loaded from: classes3.dex */
public abstract class IjkLibLoader {
    private static final String TAG = "IjkLibLoader";
    private boolean isAvailable;
    private boolean loadAttempted;
    private final String[] nativeLibraries;

    public IjkLibLoader(String... strArr) {
        this.nativeLibraries = strArr;
    }

    public synchronized boolean isAvailable() {
        if (this.loadAttempted) {
            return this.isAvailable;
        }
        this.loadAttempted = true;
        try {
            for (String str : this.nativeLibraries) {
                loadLibrary(str);
            }
            this.isAvailable = true;
        } catch (Throwable unused) {
            Log.w(TAG, "Failed to load " + Arrays.toString(this.nativeLibraries));
        }
        return this.isAvailable;
    }

    public abstract void loadLibrary(String str);
}
