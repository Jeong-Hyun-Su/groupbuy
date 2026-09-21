plugins {
    id("groupbuy.kotlin-library")
}

dependencies {
    api(project(":modules:common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Phase 5: spring-boot-starter-data-elasticsearch
}
