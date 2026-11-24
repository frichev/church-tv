package org.heartofthefather.churchtv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out whether the channel is live right now by loading
 * https://www.youtube.com/@CHANNEL/live — YouTube redirects that address to the
 * current live video when there is one, and to the channel page when there isn't.
 */
public final class LiveChecker {

    public static final class Result {
        public final boolean ok;       // false = network/parse trouble, keep previous state
        public final boolean live;
        public final String videoId;
        /** The video /live points to even if it isn't live (scheduled or just ended). */
        public final String candidateId;
        public final String note;
        Result(boolean ok, boolean live, String videoId, String candidateId, String note) {
            this.ok = ok; this.live = live; this.videoId = videoId; this.candidateId = candidateId; this.note = note;
        }
    }

    // The video the /live address resolved to (YouTube 2026 page format), with older fallbacks.
    private static final Pattern[] VIDEO_ID_PATTERNS = {
            Pattern.compile("\"currentVideoEndpoint\":\\{.{0,600}?\"videoId\":\"([A-Za-z0-9_-]{11})\"", Pattern.DOTALL),
            Pattern.compile("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\""),
            Pattern.compile("<meta property=\"og:url\" content=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\""),
    };
    // "1,234 watching now" counter on a live video carries "isLive":true.
    private static final Pattern LIVE_COUNTER = Pattern.compile(
            "\"videoViewCountRenderer\":\\{.{0,500}?\"isLive\":true", Pattern.DOTALL);

    private LiveChecker() { }

    public static Result check(String channel) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://www.youtube.com/@" + channel + "/live");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            // Skip the EU cookie-consent interstitial if YouTube ever shows it.
            c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
            int code = c.getResponseCode();
            if (code == 404) return new Result(true, false, null, null, "Channel @" + channel + " not found (404)");
            if (code != 200) return new Result(false, false, null, null, "YouTube returned HTTP " + code);
            String html = readAll(c.getInputStream());
            return parse(html);
        } catch (Exception e) {
            return new Result(false, false, null, null, "Network error: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Package-visible for testing. */
    static Result parse(String html) {
        String id = null;
        for (Pattern p : VIDEO_ID_PATTERNS) {
            Matcher m = p.matcher(html);
            if (m.find()) { id = m.group(1); break; }
        }
        if (id == null) return new Result(true, false, null, null, "Not live");
        boolean liveNow = LIVE_COUNTER.matcher(html).find() || html.contains("\"isLiveNow\":true");
        boolean upcoming = html.contains("\"isUpcoming\":true") || html.contains("\"upcomingEventData\"");
        if (liveNow) return new Result(true, true, id, id, "LIVE now (video " + id + ")");
        if (upcoming) return new Result(true, false, null, id, "A stream is scheduled but hasn't started (video " + id + ")");
        return new Result(true, false, null, id, "Not live (latest stream " + id + " has ended)");
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) break;
            }
        } finally {
            in.close();
        }
        return out.toString("UTF-8");
    }
}
