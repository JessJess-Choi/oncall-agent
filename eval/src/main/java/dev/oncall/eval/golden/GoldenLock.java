package dev.oncall.eval.golden;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 정답 라벨과 스냅샷의 SHA-256 해시를 scenarios/GOLDEN.lock에 고정하고 검증한다.
 *
 * <p>lock 형식은 sha256sum 호환 텍스트다: 한 줄에 {@code <hex>  <상대경로>}, 경로순 정렬, LF 줄바꿈.
 * 경로는 저장소 루트 기준이며 구분자는 OS와 무관하게 '/'로 쓴다.
 * 파일은 바이트 그대로 해시하므로 줄바꿈 1바이트 차이도 불일치로 본다.
 */
public final class GoldenLock {

    public static final List<String> TRACKED_DIRS = List.of("scenarios/labels", "scenarios/snapshots");
    public static final String LOCK_FILE = "scenarios/GOLDEN.lock";

    private static final Pattern LINE = Pattern.compile("([0-9a-f]{64})  (.+)");

    private final Path root;

    public GoldenLock(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 추적 디렉터리의 현재 파일 해시. 디렉터리가 없으면 파일이 없는 것으로 본다. */
    public SortedMap<String, String> compute() throws IOException {
        SortedMap<String, String> hashes = new TreeMap<>();
        for (String dir : TRACKED_DIRS) {
            Path base = root.resolve(dir);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(base)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    hashes.put(relativeKey(file), sha256(file));
                }
            }
        }
        return hashes;
    }

    /** 현재 상태로 lock 파일을 다시 쓴다. */
    public void write() throws IOException {
        Path lockFile = root.resolve(LOCK_FILE);
        Files.createDirectories(lockFile.getParent());
        Files.writeString(lockFile, format(compute()), StandardCharsets.UTF_8);
    }

    /** lock과 현재 상태의 차이. 비어 있으면 일치한다. */
    public List<String> diff() throws IOException {
        SortedMap<String, String> expected;
        try {
            expected = parse(Files.readString(root.resolve(LOCK_FILE), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return List.of("lock 파일 없음: " + LOCK_FILE);
        } catch (IllegalArgumentException e) {
            return List.of("lock 파일 형식 오류: " + e.getMessage());
        }
        SortedMap<String, String> actual = compute();

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String now = actual.get(e.getKey());
            if (now == null) {
                problems.add("삭제됨: " + e.getKey());
            } else if (!now.equals(e.getValue())) {
                problems.add("변경됨: " + e.getKey());
            }
        }
        for (String path : actual.keySet()) {
            if (!expected.containsKey(path)) {
                problems.add("추가됨: " + path);
            }
        }
        return problems;
    }

    /** 불일치가 있으면 예외를 던진다. 평가 러너는 시작할 때 이것을 호출한다. */
    public void verify() throws IOException, GoldenMismatchException {
        List<String> problems = diff();
        if (!problems.isEmpty()) {
            throw new GoldenMismatchException(problems);
        }
    }

    static String format(SortedMap<String, String> hashes) {
        StringBuilder sb = new StringBuilder();
        hashes.forEach((path, hash) -> sb.append(hash).append("  ").append(path).append('\n'));
        return sb.toString();
    }

    static SortedMap<String, String> parse(String content) {
        SortedMap<String, String> hashes = new TreeMap<>();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            Matcher m = LINE.matcher(lines[i]);
            if (!m.matches()) {
                throw new IllegalArgumentException((i + 1) + "번째 줄");
            }
            hashes.put(m.group(2), m.group(1));
        }
        return hashes;
    }

    private String relativeKey(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = new DigestOutputStream(OutputStream.nullOutputStream(), md)) {
            in.transferTo(out);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
