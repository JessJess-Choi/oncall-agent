package dev.oncall.eval.golden;

import java.util.List;

/** GOLDEN.lock과 현재 labels/snapshots가 다르다. 평가를 진행하면 안 된다. */
public final class GoldenMismatchException extends Exception {

    private final List<String> problems;

    public GoldenMismatchException(List<String> problems) {
        super("GOLDEN.lock 불일치:\n  " + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
