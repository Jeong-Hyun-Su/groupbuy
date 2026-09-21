plugins {
    id("groupbuy.kotlin-library")
}

// 모든 모듈의 클래스를 스캔해야 하므로 전부 의존한다. 프로덕션 코드는 없다.
dependencies {
    testImplementation(project(":modules:common"))
    testImplementation(project(":modules:deal"))
    testImplementation(project(":modules:participation"))
    testImplementation(project(":modules:payment"))
    testImplementation(project(":modules:settlement"))
    testImplementation(project(":modules:realtime"))
    testImplementation(project(":modules:search"))
    testImplementation(project(":apps:api"))
    testImplementation(project(":apps:worker"))

    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}
