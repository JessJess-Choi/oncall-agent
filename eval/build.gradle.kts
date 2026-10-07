plugins {
    application
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
    // 시나리오 정의·versions.yaml(YAML)과 Loki/Prometheus 응답(JSON) 처리
    implementation(platform("tools.jackson:jackson-bom:3.2.3"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.oncall.eval.EvalMain"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// 장애를 주입하고 스냅샷을 뜬다 (make capture SCENARIO=<id>). 실행 중인 스택이 필요하다.
tasks.register<JavaExec>("capture") {
    group = "scenarios"
    description = "시나리오 정의대로 장애를 주입하고 scenarios/snapshots/<id>/를 만든다."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.oncall.eval.capture.CaptureMain"
    workingDir = rootDir
}

// 정답·스냅샷 해시를 다시 고정한다. 사람만 실행한다 (make lock-golden).
tasks.register<JavaExec>("lockGolden") {
    group = "golden"
    description = "scenarios/GOLDEN.lock을 현재 labels/snapshots 기준으로 다시 쓴다."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.oncall.eval.golden.LockGoldenMain"
    workingDir = rootDir
}
