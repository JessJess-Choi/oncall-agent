package dev.oncall.eval.golden;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** make lock-golden 진입점. 사람만 실행한다. 덮어쓰기 전에 무엇이 바뀌는지 출력한다. */
public final class LockGoldenMain {

    public static void main(String[] args) throws IOException {
        GoldenLock lock = new GoldenLock(Path.of(""));
        List<String> changes = lock.diff();
        if (changes.isEmpty()) {
            System.out.println("변경 없음: " + GoldenLock.LOCK_FILE);
            return;
        }
        changes.forEach(c -> System.out.println("  " + c));
        lock.write();
        System.out.println("갱신함: " + GoldenLock.LOCK_FILE + " (파일 " + lock.compute().size() + "개)");
    }
}
