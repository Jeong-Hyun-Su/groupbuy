plugins {
    id("groupbuy.kotlin-library")
}

dependencies {
    api(project(":modules:common"))
    implementation(project(":modules:deal"))
    implementation(project(":modules:participation"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
