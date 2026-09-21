package com.groupbuy.api.config

import com.groupbuy.common.time.SystemTimeProvider
import com.groupbuy.common.time.TimeProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TimeConfig {
    @Bean
    fun timeProvider(): TimeProvider = SystemTimeProvider()
}
