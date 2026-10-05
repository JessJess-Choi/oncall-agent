package dev.oncall.eval.golden;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenLockTest {

    @TempDir
    Path root;

    GoldenLock lock;

    @BeforeEach
    void setUp() throws IOException {
        put("scenarios/labels/pool-01.yaml", "root_cause_category: connection_pool_exhaustion\n");
        put("scenarios/snapshots/pool-01/logs.jsonl", "{\"msg\":\"Connection is not available\"}\n");
        put("scenarios/snapshots/pool-01/alert.json", "{}\n");
        lock = new GoldenLock(root);
    }

    @Test
    void 생성_직후에는_검증을_통과한다() throws Exception {
        lock.write();
        lock.verify();
    }

    @Test
    void 추적_디렉터리가_없으면_빈_lock으로_통과한다(@TempDir Path empty) throws Exception {
        GoldenLock emptyLock = new GoldenLock(empty);
        Files.createDirectories(empty.resolve("scenarios"));
        emptyLock.write();

        assertEquals("", Files.readString(empty.resolve(GoldenLock.LOCK_FILE)));
        emptyLock.verify();
    }

    @Test
    void 라벨이_바뀌면_실패한다() throws Exception {
        lock.write();
        put("scenarios/labels/pool-01.yaml", "root_cause_category: bad_deploy\n");

        assertProblems("변경됨: scenarios/labels/pool-01.yaml");
    }

    @Test
    void 스냅샷_파일이_추가되면_실패한다() throws Exception {
        lock.write();
        put("scenarios/snapshots/pool-01/deploys.json", "[]\n");

        assertProblems("추가됨: scenarios/snapshots/pool-01/deploys.json");
    }

    @Test
    void 파일이_삭제되면_실패한다() throws Exception {
        lock.write();
        Files.delete(root.resolve("scenarios/snapshots/pool-01/alert.json"));

        assertProblems("삭제됨: scenarios/snapshots/pool-01/alert.json");
    }

    @Test
    void lock_파일이_없으면_실패한다() throws Exception {
        assertProblems("lock 파일 없음: " + GoldenLock.LOCK_FILE);
    }

    @Test
    void 줄바꿈_1바이트_차이도_감지한다() throws Exception {
        lock.write();
        put("scenarios/snapshots/pool-01/alert.json", "{}\r\n");

        assertProblems("변경됨: scenarios/snapshots/pool-01/alert.json");
    }

    @Test
    void 경로는_OS와_무관하게_슬래시로_기록한다() throws Exception {
        lock.write();
        String content = Files.readString(root.resolve(GoldenLock.LOCK_FILE));

        assertTrue(content.contains("  scenarios/snapshots/pool-01/logs.jsonl\n"), content);
        assertTrue(!content.contains("\\"), content);
        assertTrue(!content.contains("\r"), content);
    }

    @Test
    void 형식이_깨진_lock은_실패한다() throws Exception {
        put(GoldenLock.LOCK_FILE, "not-a-hash  scenarios/labels/pool-01.yaml\n");

        assertProblems("lock 파일 형식 오류: 1번째 줄");
    }

    private void assertProblems(String... expected) throws IOException {
        GoldenMismatchException e = assertThrows(GoldenMismatchException.class, lock::verify);
        assertEquals(List.of(expected), e.problems());
    }

    private void put(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
