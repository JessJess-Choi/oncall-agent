package dev.oncall.eval.capture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 배포 ID를 시나리오와 단계 위치로부터 결정적으로 만든다.
 * 다시 캡처해도 같은 ID가 나와야 라벨의 deploy_id가 깨지지 않는다.
 */
final class DeployIds {

    private DeployIds() {
    }

    /** position 예: "history/0", "background/0", "fault/1" */
    static String of(String scenarioId, String position) {
        byte[] h = sha256(scenarioId + "/" + position);
        long n = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
        return "deploy-" + (1000 + n % 9000);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
