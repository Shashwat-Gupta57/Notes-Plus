package org.flexstudios.notes.plus;

import android.app.Application;

public class NotesApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
    }
}
