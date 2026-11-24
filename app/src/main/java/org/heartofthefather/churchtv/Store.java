package org.heartofthefather.churchtv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * All app state lives here: the announcement playlist, settings, and the
 * current live/announcement mode. Shared by the web server, the live checker
 * and the on-screen player.
 */
public final class Store {

    public static final String DEFAULT_CHANNEL = "heartofthefatherministry";

    public interface Listener { void onModeChanged(String effectiveMode); }

    private static Store instance;

    public static synchronized Store get(Context ctx) {
        if (instance == null) instance = new Store(ctx.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final File mediaDir;
    private Listener listener;

    // Live status (in memory; re-detected after every restart)
    private boolean live = false;
    private String liveVideoId = null;
    private long lastCheck = 0;
    private String lastCheckResult = "Not checked yet";
    private int playlistVersion = 0;

    private Store(Context ctx) {
        prefs = ctx.getSharedPreferences("churchtv", Context.MODE_PRIVATE);
        mediaDir = new File(ctx.getFilesDir(), "media");
        //noinspection ResultOfMethodCallIgnored
        mediaDir.mkdirs();
        syncPlaylistWithDisk();
    }

    public synchronized void setListener(Listener l) { listener = l; }

    // ---------- settings ----------

    public synchronized String channel() { return prefs.getString("channel", DEFAULT_CHANNEL); }

    public synchronized int imageSeconds() { return prefs.getInt("imageSeconds", 10); }

    /** "inapp" = play the stream inside this app; "youtubeapp" = hand off to the YouTube app. */
    public synchronized String playback() { return prefs.getString("playback", "inapp"); }

    /** "dissolve" (default), "black" (fade through black) or "none". */
    public synchronized String transition() { return prefs.getString("transition", "dissolve"); }

    public synchronized int transitionMs() { return prefs.getInt("transitionMs", 1000); }

    public synchronized void updateSettings(String channel, Integer imageSeconds, String playback) {
        updateSettings(channel, imageSeconds, playback, null, null);
    }

    public synchronized void updateSettings(String channel, Integer imageSeconds, String playback,
                                            String transition, Integer transitionMs) {
        if ("dissolve".equals(transition) || "black".equals(transition) || "none".equals(transition))
            prefs.edit().putString("transition", transition).apply();
        if (transitionMs != null) prefs.edit().putInt("transitionMs", Math.max(0, Math.min(5000, transitionMs))).apply();
        SharedPreferences.Editor e = prefs.edit();
        if (channel != null) {
            String c = channel.trim();
            int at = c.lastIndexOf('@');
            if (at >= 0) c = c.substring(at + 1);
            int slash = c.indexOf('/');
            if (slash >= 0) c = c.substring(0, slash);
            if (!c.isEmpty()) e.putString("channel", c);
        }
        if (imageSeconds != null) e.putInt("imageSeconds", Math.max(2, Math.min(600, imageSeconds)));
        if ("inapp".equals(playback) || "youtubeapp".equals(playback)) e.putString("playback", playback);
        e.apply();
        playlistVersion++;
    }

    // ---------- mode ----------

    /** null = automatic; otherwise "live" or "announcements". */
    public synchronized String override() { return prefs.getString("override", null); }

    public synchronized String effectiveMode() {
        String o = override();
        if (o != null) return o;
        return live ? "live" : "announcements";
    }

    public void setOverride(String mode) {
        String before, after;
        Listener l;
        synchronized (this) {
            before = effectiveMode();
            if ("live".equals(mode) || "announcements".equals(mode)) prefs.edit().putString("override", mode).apply();
            else prefs.edit().remove("override").apply();
            after = effectiveMode();
            l = listener;
        }
        if (l != null) l.onModeChanged(after);
    }

    /** Called by the live checker. A change in live status clears any manual override. */
    public void setLiveStatus(boolean isLive, String videoId, String note) {
        String after;
        boolean changed;
        Listener l;
        synchronized (this) {
            changed = isLive != live || (isLive && videoId != null && !videoId.equals(liveVideoId));
            String beforeMode = effectiveMode();
            if (isLive != live) prefs.edit().remove("override").apply();
            live = isLive;
            if (isLive) liveVideoId = videoId; else liveVideoId = null;
            lastCheck = System.currentTimeMillis();
            lastCheckResult = note;
            after = effectiveMode();
            changed = changed || !beforeMode.equals(after);
            l = listener;
        }
        if (changed && l != null) l.onModeChanged(after);
    }

    public synchronized void setCheckNote(String note) {
        lastCheck = System.currentTimeMillis();
        lastCheckResult = note;
    }

    public synchronized boolean isLive() { return live; }
    public synchronized String liveVideoId() { return liveVideoId; }

    // ---------- playlist ----------

    public File mediaDir() { return mediaDir; }

    public static String typeOf(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif")
                || n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".heic") || n.endsWith(".heif")) return "image";
        if (n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".webm") || n.endsWith(".mkv")
                || n.endsWith(".mov") || n.endsWith(".3gp")) return "video";
        return null;
    }

    public static String sanitize(String name) {
        if (name == null) return null;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._ \\-()]", "_").trim();
        if (name.isEmpty() || name.startsWith(".")) return null;
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, 120 - ext.length()) + ext;
        }
        return typeOf(name) == null ? null : name;
    }

    public synchronized List<String> playlist() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("playlist", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) { }
        return out;
    }

    private void savePlaylist(List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        prefs.edit().putString("playlist", a.toString()).apply();
        playlistVersion++;
    }

    /** Drop entries whose file is gone; append files on disk that aren't listed. */
    public synchronized void syncPlaylistWithDisk() {
        List<String> list = playlist();
        List<String> kept = new ArrayList<>();
        for (String s : list) if (new File(mediaDir, s).isFile() && !kept.contains(s)) kept.add(s);
        File[] files = mediaDir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName();
            if (f.isFile() && !n.endsWith(".part") && typeOf(n) != null && !kept.contains(n)) kept.add(n);
        }
        savePlaylist(kept);
    }

    public synchronized void added(String name) {
        List<String> list = playlist();
        if (!list.contains(name)) list.add(name);
        savePlaylist(list);
    }

    public synchronized boolean delete(String name) {
        List<String> list = playlist();
        boolean removed = list.remove(name);
        File f = new File(mediaDir, name);
        if (f.isFile()) removed |= f.delete();
        savePlaylist(list);
        return removed;
    }

    public synchronized void move(String name, int delta) {
        List<String> list = playlist();
        int i = list.indexOf(name);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return;
        list.remove(i);
        list.add(j, name);
        savePlaylist(list);
    }

    // ---------- JSON for the web pages ----------

    public synchronized JSONObject stateJson(String controlUrl, boolean autoStartReady) {
        JSONObject o = new JSONObject();
        try {
            o.put("mode", effectiveMode());
            o.put("override", override() == null ? JSONObject.NULL : override());
            o.put("live", live);
            o.put("videoId", liveVideoId == null ? JSONObject.NULL : liveVideoId);
            o.put("channel", channel());
            o.put("imageSeconds", imageSeconds());
            o.put("playback", playback());
            o.put("transition", transition());
            o.put("transitionMs", transitionMs());
            o.put("lastCheck", lastCheck);
            o.put("lastCheckResult", lastCheckResult);
            o.put("controlUrl", controlUrl);
            o.put("autoStartReady", autoStartReady);
            o.put("playlistVersion", playlistVersion);
            JSONArray items = new JSONArray();
            for (String s : playlist()) {
                File f = new File(mediaDir, s);
                JSONObject it = new JSONObject();
                it.put("name", s);
                it.put("type", typeOf(s));
                it.put("size", f.length());
                it.put("v", f.lastModified());
                items.put(it);
            }
            o.put("items", items);
            o.put("freeBytes", mediaDir.getUsableSpace());
        } catch (JSONException ignored) { }
        return o;
    }
}
