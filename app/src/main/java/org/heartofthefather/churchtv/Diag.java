package org.heartofthefather.churchtv;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory log shown in the control page's Diagnostics box (to help fix problems remotely). */
public final class Diag {
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Diag() { }

    public static synchronized void log(String s) {
        lines.addLast(fmt.format(new Date()) + "  " + s);
        while (lines.size() > 80) lines.removeFirst();
        android.util.Log.i("ChurchTV", s);
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }
}
