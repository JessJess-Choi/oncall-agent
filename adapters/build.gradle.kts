plugins {
    `java-library`
}

group = "dev.oncall"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // 스냅샷 파일(JSON, JSONL) 읽기. 공개 API에는 Jackson 타입을 노출하지 않는다.
    implementation(platform("tools.jackson:jackson-bom:3.2.3"))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
