plugins {
    // 모듈 toolchain(JDK 21)이 로컬에 없으면 자동으로 내려받는다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "oncall-agent"

// 모듈은 해당 주차에 추가한다: runners:claude_code(2주차 B), service(6주차), runners:own_loop(2단계)
include("adapters", "tools", "eval")
