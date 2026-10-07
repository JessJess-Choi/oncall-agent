package dev.oncall.eval.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스냅샷에 장애 주입 흔적(정답 힌트)이 섞였는지 검사한다.
 * 현실의 당직자가 볼 수 없는 단어가 나오면 시험지가 오염된 것이다.
 */
final class LeakGuard {

    static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(fault\\w*|chaos\\w*|inject\\w*|toxi\\w*|k6|loadgen|scenario\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    record Leak(String file, int lineNumber, String word, String excerpt) {
        @Override
        public String toString() {
            return file + ":" + lineNumber + " '" + word + "' → " + excerpt;
        }
    }

    private LeakGuard() {
    }

    /** files: 파일 이름 → 내용. meta.json은 평가 전용이라 검사하지 않는다. */
    static List<Leak> scan(Map<String, String> files) {
        List<Leak> leaks = new ArrayList<>();
        files.forEach((name, content) -> {
            if (name.equals(CaptureRunner.META)) {
                return;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = FORBIDDEN.matcher(lines[i]);
                while (m.find()) {
                    int from = Math.max(0, m.start() - 60);
                    int to = Math.min(lines[i].length(), m.end() + 60);
                    leaks.add(new Leak(name, i + 1, m.group(), lines[i].substring(from, to)));
                }
            }
        });
        return leaks;
    }
}
