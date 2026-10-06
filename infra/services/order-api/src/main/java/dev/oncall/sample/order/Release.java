package dev.oncall.sample.order;

/**
 * 배포 버전별 동작. APP_VERSION으로 고르며, 배포는 이 값을 바꿔 컨테이너를 재기동하는 것으로 표현한다.
 * 버전별 변경 설명(에이전트가 볼 수 있는 쪽)은 infra/services/versions.yaml에 있다.
 * 이 파일과 코드 주석은 에이전트에게 노출되지 않는다.
 */
public record Release(
        String version,
        boolean sharedCouponNormalizer, // 1.2.0: null 쿠폰에서 NPE (bad_deploy)
        boolean paymentInsideTransaction, // 1.3.0: 결제 대기 중 커넥션 점유 (connection_pool_exhaustion)
        boolean receiptCache // 1.4.0: 만료 없는 캐시 (memory_leak)
) {

    public static Release of(String version) {
        return switch (version) {
            case "1.0.0", "1.1.0" -> new Release(version, false, false, false);
            case "1.2.0" -> new Release(version, true, false, false);
            case "1.3.0" -> new Release(version, false, true, false);
            case "1.4.0" -> new Release(version, false, false, true);
            default -> throw new IllegalArgumentException("unknown version: " + version);
        };
    }
}
