package dev.oncall.eval;

import dev.oncall.eval.golden.GoldenLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalMainTest {

    @TempDir
    Path root;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        Path label = root.resolve("scenarios/labels/pool-01.yaml");
        Files.createDirectories(label.getParent());
        Files.writeString(label, "root_cause_category: connection_pool_exhaustion\n");
    }

    @Test
    void lock이_일치하면_진행한다() throws IOException {
        new GoldenLock(root).write();

        assertEquals(EvalMain.OK, run("--scenarios", "pool-01"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("pool-01"));
    }

    @Test
    void lock이_불일치하면_실행을_거부한다() throws IOException {
        new GoldenLock(root).write();
        Files.writeString(root.resolve("scenarios/labels/pool-01.yaml"), "root_cause_category: bad_deploy\n");

        assertEquals(EvalMain.REFUSED, run());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("변경됨: scenarios/labels/pool-01.yaml"));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void lock이_없으면_실행을_거부한다() {
        assertEquals(EvalMain.REFUSED, run());
    }

    @Test
    void 모르는_인자는_검증_전에_거부한다() {
        assertEquals(EvalMain.USAGE, run("--unknown"));
    }

    private int run(String... args) {
        return EvalMain.run(root, args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }
}
