plugins {
    id("groupbuy.spring-app")
}

dependencies {
    implementation(project(":modules:common"))
    implementation(project(":modules:deal"))
    implementation(project(":modules:participation"))
    implementation(project(":modules:payment"))
    implementation(project(":modules:settlement"))
    implementation(project(":modules:realtime"))
    implementation(project(":modules:search"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")   // actuator 노출용. Phase 3에서 kafka 추가
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
