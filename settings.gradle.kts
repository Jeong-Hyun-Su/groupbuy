plugins {
    // JDK 21 이 로컬에 없으면 자동으로 받는다 (buildSrc 의 toolchain 설정 참고)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "groupbuy"

include(
    "modules:common",
    "modules:deal",
    "modules:participation",
    "modules:payment",
    "modules:settlement",
    "modules:realtime",
    "modules:search",
    "apps:api",
    "apps:worker",
    "arch-test",
)
