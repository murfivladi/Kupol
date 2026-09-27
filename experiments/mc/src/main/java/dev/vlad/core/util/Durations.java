package dev.vlad.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Длительности вида "1w2d3h4m5s" ↔ миллисекунды. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])");
    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;

    private Durations() {
    }

    /** Миллисекунды, либо -1, если строка не распознана. */
    public static long parse(String text) {
        String s = text.toLowerCase();
        Matcher m = PART.matcher(s);
        long total = 0;
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) {
                return -1;
            }
            consumed = m.end();
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s": total += n * SECOND; break;
                case "m": total += n * MINUTE; break;
                case "h": total += n * HOUR; break;
                case "d": total += n * DAY; break;
                default: total += n * WEEK;
            }
        }
        return consumed == s.length() && total > 0 ? total : -1;
    }

    /** "2д 3ч 15м"; для меньше минуты — секунды. */
    public static String format(long millis) {
        long d = millis / DAY;
        long h = millis % DAY / HOUR;
        long m = millis % HOUR / MINUTE;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("д ");
        if (h > 0) sb.append(h).append("ч ");
        if (m > 0) sb.append(m).append("м ");
        if (sb.length() == 0) sb.append(Math.max(1, millis / SECOND)).append("с ");
        return sb.toString().trim();
    }
}
