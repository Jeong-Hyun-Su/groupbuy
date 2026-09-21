plugins {
    id("groupbuy.kotlin-library")
}

dependencies {
    api(project(":modules:common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}
