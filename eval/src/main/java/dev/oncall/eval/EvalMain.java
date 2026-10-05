package dev.oncall.eval;

import dev.oncall.eval.golden.GoldenLock;
import dev.oncall.eval.golden.GoldenMismatchException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * make eval 진입점. 무엇보다 먼저 GOLDEN.lock을 검증하고, 불일치하면 실행을 거부한다.
 * 시나리오 실행과 채점은 3주차에 구현한다.
 */
public final class EvalMain {

    static final int OK = 0;
    static final int REFUSED = 1;
    static final int USAGE = 2;

    public static void main(String[] args) {
        System.exit(run(Path.of(""), args, System.out, System.err));
    }

    static int run(Path root, String[] args, PrintStream out, PrintStream err) {
        String scenarios = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--scenarios") && i + 1 < args.length) {
                scenarios = args[++i];
            } else {
                err.println("사용법: eval [--scenarios a,b]");
                return USAGE;
            }
        }

        try {
            new GoldenLock(root).verify();
        } catch (GoldenMismatchException e) {
            err.println("평가 거부: " + e.getMessage());
            return REFUSED;
        } catch (IOException e) {
            err.println("평가 거부: GOLDEN.lock 검증 중 I/O 오류: " + e.getMessage());
            return REFUSED;
        }

        out.println("GOLDEN.lock 검증 통과. 시나리오: " + (scenarios == null ? "전체" : scenarios));
        out.println("평가 러너는 3주차에 구현한다.");
        return OK;
    }
}
