import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("io.spring.dependency-management")
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// JPA 엔티티는 프록시 생성을 위해 open 이어야 한다
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

// Spring Boot BOM 은 자체 kotlin.version 을 강제한다. 이 값이 buildSrc 의 Kotlin 플러그인 버전과 다르면
// 컴파일러(kotlin-build-tools-impl) 가 다른 버전으로 풀려 "ClasspathEntrySnapshotter$Settings" NoClassDefFoundError 가 난다.
// 플러그인 버전을 단일 진실로 삼아 BOM 의 값을 덮어쓴다.
extra["kotlin.version"] = getKotlinPluginVersion()

dependencyManagement {
    imports {
        // buildSrc/build.gradle.kts 의 Versions.springBoot 와 반드시 동일하게 유지
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.6")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito", module = "mockito-core")
    }
    testImplementation("io.mockk:mockk:1.14.2")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    // Gradle 이 자체 런처를 주입하면 junit-platform-engine 과 버전이 어긋나 테스트 탐색이 실패한다. BOM 버전으로 명시.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
