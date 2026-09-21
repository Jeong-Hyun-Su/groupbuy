plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// 버전은 여기 한 곳에서만 관리한다. 첫 빌드에서 반드시 호환성을 확인할 것 (README 참고).
object Versions {
    const val kotlin = "2.2.0"
    const val springBoot = "3.5.6"
    const val dependencyManagement = "1.1.7"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${Versions.kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-noarg:${Versions.kotlin}")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${Versions.springBoot}")
    implementation("io.spring.gradle:dependency-management-plugin:${Versions.dependencyManagement}")
}
