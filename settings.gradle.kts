plugins {
    // 모듈 toolchain(JDK 21)이 로컬에 없으면 자동으로 내려받는다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "oncall-agent"

// 모듈은 해당 주차에 추가한다: agent-gateway, agent-core(2주차), service(6주차)
include("eval")
