package com.groupbuy.worker.config

import com.groupbuy.common.time.SystemTimeProvider
import com.groupbuy.common.time.TimeProvider
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EntityScan(basePackages = ["com.groupbuy"])
@EnableJpaRepositories(basePackages = ["com.groupbuy"])
class WorkerConfig {
    @Bean
    fun timeProvider(): TimeProvider = SystemTimeProvider()
}
