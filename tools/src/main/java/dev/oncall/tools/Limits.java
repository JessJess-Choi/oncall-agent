package dev.oncall.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 툴 출력 상한. 값은 resources/limits.yaml 한 곳에서 온다 (원칙 2). */
record Limits(int logMaxLines, int logLineMaxChars, int metricMaxPoints, int metricMaxSeries,
              int deployLookbackDays, int deployMax, int resultMaxChars) {

    static Limits load() {
        try (InputStream in = Limits.class.getResourceAsStream("/limits.yaml")) {
            if (in == null) {
                throw new IllegalStateException("limits.yaml 없음");
            }
            JsonNode n = YAMLMapper.builder().build().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return new Limits(required(n, "log_max_lines"), required(n, "log_line_max_chars"),
                    required(n, "metric_max_points"), required(n, "metric_max_series"),
                    required(n, "deploy_lookback_days"), required(n, "deploy_max"), required(n, "result_max_chars"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int required(JsonNode n, String key) {
        if (!n.path(key).isInt() || n.path(key).asInt() <= 0) {
            throw new IllegalStateException("limits.yaml의 " + key + "는 양의 정수여야 한다");
        }
        return n.path(key).asInt();
    }
}
