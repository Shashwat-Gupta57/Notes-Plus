package org.flexstudios.notes.plus;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class NotesApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        registerActivityLifecycleCallbacks(new EdgeToEdge());
    }

    /**
     * Android 15+ draws every app edge to edge (targetSdk 36): without this, toolbars sit under
     * the status bar and the last rows under the navigation bar. Each screen's content is padded
     * by the system bars (and the keyboard, where the screen doesn't handle it itself). The media
     * viewer is full screen and pads itself; the chat screen handles the keyboard itself.
     */
    private static final class EdgeToEdge implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            if (activity instanceof MediaPagerActivity) return;
            View content = activity.findViewById(android.R.id.content);
            if (content == null || content.getTag(R.id.edge_to_edge) != null) return;
            content.setTag(R.id.edge_to_edge, Boolean.TRUE);
            boolean ownKeyboard = activity instanceof ChatActivity;
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                int bottom = ownKeyboard ? bars.bottom : Math.max(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                v.setPadding(bars.left, bars.top, bars.right, bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(content);
        }

        @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
        @Override public void onActivityResumed(@NonNull Activity a) {}
        @Override public void onActivityPaused(@NonNull Activity a) {}
        @Override public void onActivityStopped(@NonNull Activity a) {}
        @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        @Override public void onActivityDestroyed(@NonNull Activity a) {}
    }
}
