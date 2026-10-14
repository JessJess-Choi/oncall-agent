package dev.oncall.adapters;

import java.time.Instant;

/** 조회 구간. 시작 포함, 끝 포함. */
public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        if (start == null || end == null || end.isBefore(start)) {
            throw new IllegalArgumentException("잘못된 구간: " + start + " ~ " + end);
        }
    }

    public boolean contains(Instant t) {
        return !t.isBefore(start) && !t.isAfter(end);
    }

    /** 이 구간 안으로 잘라 낸다. 겹치지 않으면 예외. */
    public TimeRange clampTo(TimeRange outer) {
        Instant s = start.isBefore(outer.start) ? outer.start : start;
        Instant e = end.isAfter(outer.end) ? outer.end : end;
        if (e.isBefore(s)) {
            throw new IllegalArgumentException("조회 가능한 구간(" + outer.start + " ~ " + outer.end + ")과 겹치지 않음");
        }
        return new TimeRange(s, e);
    }
}
