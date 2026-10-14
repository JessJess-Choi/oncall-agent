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
    implementation(project(":adapters"))

    implementation(platform("tools.jackson:jackson-bom:3.2.3"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    // MCP 규약(JSON-RPC, 툴 목록·호출, stdio 전송) 구현. 직접 만들지 않는다.
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
    // SDK 로그를 stderr로 보낸다. stdout은 MCP 통신 전용이라 로그가 섞이면 안 된다.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.oncall.tools.OncallMcpServer"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
}
