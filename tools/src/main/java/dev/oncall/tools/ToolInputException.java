package dev.oncall.tools;

/**
 * 툴 입력이 잘못됐을 때. 메시지는 그대로 모델에게 돌아가므로, 고칠 수 있게 구체적으로 쓰고
 * 스냅샷 경로 같은 내부 정보(시나리오 이름 누출)는 넣지 않는다.
 */
final class ToolInputException extends RuntimeException {

    ToolInputException(String message) {
        super(message);
    }
}
