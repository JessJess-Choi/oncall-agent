// 조사 대상 시스템(샘플 서비스). 하네스 빌드와 섞지 않도록 별도 Gradle 빌드로 둔다.
// 이미지 빌드(Dockerfile) 안에서 컴파일하므로 호스트에 빌드 도구가 없어도 된다.
pluginManagement {
    plugins {
        id("org.springframework.boot") version "4.1.1"
    }
}

rootProject.name = "sample-services"

include("order-api", "payment-api")
