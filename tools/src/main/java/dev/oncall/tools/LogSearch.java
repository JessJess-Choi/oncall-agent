package dev.oncall.tools;

import dev.oncall.adapters.Telemetry.LogEntry;
import dev.oncall.adapters.TelemetrySource;
import dev.oncall.adapters.TimeRange;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * search_logs v0: 조건에 맞는 로그 중 가장 최근 N줄을 원문 그대로 돌려준다(줄당 길이 제한).
 * 앱의 JSON 로그는 읽기 좋게 "레벨 로거: 메시지 | 예외 줄 | Caused by 줄"로 펼친다.
 * 패턴 집계는 4주차 개선 항목이다. v0를 단순하게 둬야 개선 효과(비용 감소)를 비교할 수 있다.
 */
final class LogSearch {

    static final Set<String> LEVELS = Set.of("ERROR", "WARN", "INFO");
    static final Set<String> KEYS = Set.of("service", "level", "contains", "start", "end");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TelemetrySource source;
    private final Limits limits;

    LogSearch(TelemetrySource source, Limits limits) {
        this.source = source;
        this.limits = limits;
    }

    ObjectNode run(Args args) {
        args.onlyKeys(KEYS);
        String service = Args.oneOf("service", args.requiredString("service"), source.services());
        String level = args.optionalString("level");
        if (level != null) {
            level = Args.oneOf("level", level.toUpperCase(Locale.ROOT), LEVELS);
        }
        String contains = args.optionalString("contains");
        TimeRange range = args.range(source.available().start(), source.available().end(), source.available());

        List<LogEntry> matched = new ArrayList<>();
        for (LogEntry e : source.logs(service, range)) {
            if (level != null && !level.equals(levelOf(e.line()))) {
                continue;
            }
            if (contains != null && !e.line().toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT))) {
                continue;
            }
            matched.add(e);
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("tool", "search_logs");
        out.put("service", service);
        ObjectNode r = out.putObject("range");
        r.put("start", range.start().toString());
        r.put("end", range.end().toString());
        ObjectNode f = out.putObject("filters");
        f.put("level", level);
        f.put("contains", contains);
        out.put("total_matched", matched.size());
        if (!matched.isEmpty()) {
            out.put("first_match_at", matched.getFirst().ts().toString());
            out.put("last_match_at", matched.getLast().ts().toString());
        }
        List<LogEntry> recent = matched.subList(Math.max(0, matched.size() - limits.logMaxLines()), matched.size());
        ArrayNode lines = out.putArray("lines");
        for (LogEntry e : recent) {
            ObjectNode l = lines.addObject();
            l.put("ts", e.ts().toString());
            l.put("text", render(e.line(), limits.logLineMaxChars()));
        }
        out.put("returned", lines.size());
        if (matched.size() > lines.size()) {
            out.put("note", "총 " + matched.size() + "건 중 가장 최근 " + lines.size() + "건만 보여 준다. "
                    + "범위(start, end)나 level, contains로 좁힐 수 있다.");
        }
        return out;
    }

    /** 앱 JSON 로그의 level, Postgres 로그의 심각도를 ERROR/WARN/INFO로 맞춘다. */
    static String levelOf(String line) {
        if (line.startsWith("{")) {
            try {
                String lv = JSON.readTree(line).path("level").asString("");
                return lv.equals("WARNING") ? "WARN" : lv;
            } catch (JacksonException e) {
                return "INFO";
            }
        }
        if (line.contains(" ERROR: ") || line.contains(" FATAL: ") || line.contains(" PANIC: ")) {
            return "ERROR";
        }
        if (line.contains(" WARNING: ")) {
            return "WARN";
        }
        return "INFO";
    }

    /** 앱 JSON 로그는 읽기 좋게 펼치고(스택 프레임 'at ...' 줄은 뺌), 그 밖은 원문. 최대 길이로 자른다. */
    static String render(String line, int maxChars) {
        String text = line;
        if (line.startsWith("{")) {
            try {
                JsonNode n = JSON.readTree(line);
                StringBuilder sb = new StringBuilder();
                sb.append(n.path("level").asString("")).append(' ')
                        .append(shortName(n.path("logger_name").asString(""))).append(": ")
                        .append(n.path("message").asString(""));
                for (String s : n.path("stack_trace").asString("").split("\n")) {
                    String t = s.strip();
                    if (!t.isEmpty() && !t.startsWith("at ") && !t.startsWith("...")) {
                        sb.append(" | ").append(t);
                    }
                }
                text = sb.toString();
            } catch (JacksonException e) {
                text = line;
            }
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…(잘림)";
    }

    private static String shortName(String logger) {
        int i = logger.lastIndexOf('.');
        return i < 0 ? logger : logger.substring(i + 1);
    }
}
