package dev.oncall.eval.capture;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 정의 파일의 기간 표기("30s", "8m", "1h")를 해석한다. */
final class Durations {

    private static final Pattern FORMAT = Pattern.compile("([1-9][0-9]*)(s|m|h)");

    private Durations() {
    }

    static Duration parse(String text) {
        Matcher m = FORMAT.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("기간 형식 오류: '" + text + "' (예: 30s, 8m, 1h)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
