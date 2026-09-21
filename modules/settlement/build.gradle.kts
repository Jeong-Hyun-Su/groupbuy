plugins {
    id("groupbuy.kotlin-library")
}

dependencies {
    api(project(":modules:common"))
    // 다른 비즈니스 모듈에 직접 의존하지 않는다. 이벤트 컨슈머로만 동작 (ArchUnit 으로 강제)
}
