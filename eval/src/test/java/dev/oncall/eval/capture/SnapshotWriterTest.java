package dev.oncall.eval.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotWriterTest {

    @TempDir
    Path root;

    private static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("alert.json", "{}\n");
        files.put("logs.jsonl", "{\"ts\":\"t\",\"service\":\"order-api\",\"line\":\"한글 로그\"}\n");
        return files;
    }

    @Test
    void 임시_디렉터리에_쓴_뒤_스냅샷_위치로_옮긴다() throws Exception {
        Path target = new SnapshotWriter(root).write("bad-deploy-01", files());

        assertEquals(root.resolve("scenarios/snapshots/bad-deploy-01"), target);
        assertTrue(Files.isRegularFile(target.resolve("logs.jsonl")));
        assertFalse(Files.exists(root.resolve("build/capture/bad-deploy-01")));
    }

    @Test
    void 기존_스냅샷은_덮어쓰지_않는다() throws Exception {
        SnapshotWriter writer = new SnapshotWriter(root);
        writer.write("bad-deploy-01", files());

        assertThrows(IllegalStateException.class, () -> writer.write("bad-deploy-01", files()));
        assertThrows(IllegalStateException.class, () -> writer.refuseExisting("bad-deploy-01"));
    }

    @Test
    void 같은_내용이면_같은_바이트를_쓴다(@TempDir Path other) throws Exception {
        Path a = new SnapshotWriter(root).write("x", files());
        Path b = new SnapshotWriter(other).write("x", files());

        assertArrayEquals(Files.readAllBytes(a.resolve("logs.jsonl")), Files.readAllBytes(b.resolve("logs.jsonl")));
    }

    @Test
    void pretty_JSON은_운영체제와_무관하게_LF로_끝난다() {
        String text = Json.pretty(Json.parse("{\"a\":[1,2]}"));
        assertFalse(text.contains("\r"));
        assertTrue(text.endsWith("\n"));
    }
}
