plugins {
    id("groupbuy.kotlin-library")
    id("org.springframework.boot")
}

// 앱 모듈만 실행 가능한 jar 를 만든다
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}
