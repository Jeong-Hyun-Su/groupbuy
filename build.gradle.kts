// 실제 설정은 buildSrc/src/main/kotlin/ 의 convention plugin에 있다.
//   groupbuy.kotlin-library  : 라이브러리 모듈 공통 (Kotlin, Spring BOM, 테스트)
//   groupbuy.spring-app      : 실행 가능한 앱 (bootJar)
allprojects {
    group = "com.groupbuy"
    version = "0.1.0-SNAPSHOT"
}
