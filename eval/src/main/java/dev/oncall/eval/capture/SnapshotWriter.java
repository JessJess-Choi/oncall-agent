package dev.oncall.eval.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 스냅샷을 임시 디렉터리(build/capture/<id>)에 다 쓴 뒤 한 번에 옮긴다.
 * 기존 스냅샷은 덮어쓰지 않는다. 다시 뜨려면 사람이 지운다.
 */
final class SnapshotWriter {

    static final String SNAPSHOTS = "scenarios/snapshots";

    private final Path root;

    SnapshotWriter(Path root) {
        this.root = root;
    }

    Path target(String id) {
        return root.resolve(SNAPSHOTS).resolve(id);
    }

    void refuseExisting(String id) {
        if (Files.exists(target(id))) {
            throw new IllegalStateException("스냅샷이 이미 있음: " + SNAPSHOTS + "/" + id
                    + " (다시 뜨려면 사람이 직접 지운 뒤 실행)");
        }
    }

    Path write(String id, Map<String, String> files) throws IOException {
        refuseExisting(id);
        Path temp = root.resolve("build/capture").resolve(id);
        deleteTree(temp);
        Files.createDirectories(temp);
        for (Map.Entry<String, String> f : files.entrySet()) {
            Files.writeString(temp.resolve(f.getKey()), f.getValue(), StandardCharsets.UTF_8);
        }
        Path target = target(id);
        Files.createDirectories(target.getParent());
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target);
        }
        return target;
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
