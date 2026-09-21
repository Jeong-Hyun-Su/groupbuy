package com.groupbuy.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * HTTP API + SSE 진입점. 비즈니스 로직은 modules/ 에 있고 여기는 조립만 한다.
 * 컴포넌트 스캔 루트를 com.groupbuy 로 잡아 모든 모듈의 빈을 올린다.
 */
@SpringBootApplication(scanBasePackages = ["com.groupbuy"])
@ConfigurationPropertiesScan("com.groupbuy")
class GroupbuyApiApplication

fun main(args: Array<String>) {
    runApplication<GroupbuyApiApplication>(*args)
}
