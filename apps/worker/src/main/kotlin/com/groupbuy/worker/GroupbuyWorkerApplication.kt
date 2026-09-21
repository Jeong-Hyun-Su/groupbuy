package com.groupbuy.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄러 + (Phase 3부터) Kafka 컨슈머 진입점. api 와 같은 모듈에 의존하되 HTTP 요청을 받지 않는다.
 * ADR-09: api 와 배포 단위를 분리하는 이유는 부하 특성이 다르기 때문.
 */
@SpringBootApplication(scanBasePackages = ["com.groupbuy"])
@ConfigurationPropertiesScan("com.groupbuy")
@EnableScheduling
class GroupbuyWorkerApplication

fun main(args: Array<String>) {
    runApplication<GroupbuyWorkerApplication>(*args)
}
