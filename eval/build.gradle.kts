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

// 정답·스냅샷 해시를 다시 고정한다. 사람만 실행한다 (make lock-golden).
tasks.register<JavaExec>("lockGolden") {
    group = "golden"
    description = "scenarios/GOLDEN.lock을 현재 labels/snapshots 기준으로 다시 쓴다."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.oncall.eval.golden.LockGoldenMain"
    workingDir = rootDir
}
