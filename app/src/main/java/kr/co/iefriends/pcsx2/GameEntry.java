package kr.co.iefriends.pcsx2;

import android.net.Uri;

public class GameEntry {
    public final String title;
    public final Uri uri;
    public String serial;
    public String gameTitle;

    public GameEntry(String t, Uri u) {
        this.title = t;
        this.uri = u;
    }

    public String fileTitleNoExt() {
        int i = title.lastIndexOf('.');
        return (i > 0) ? title.substring(0, i) : title;
    }
}
