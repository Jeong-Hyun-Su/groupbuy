plugins {
    id("groupbuy.spring-app")
}

dependencies {
    implementation(project(":modules:common"))
    implementation(project(":modules:deal"))
    implementation(project(":modules:participation"))
    implementation(project(":modules:payment"))
    implementation(project(":modules:realtime"))
    implementation(project(":modules:search"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
