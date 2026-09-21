package com.groupbuy.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.boot.autoconfigure.domain.EntityScan

@Configuration
@EntityScan(basePackages = ["com.groupbuy"])
@EnableJpaRepositories(basePackages = ["com.groupbuy"])
class JpaConfig
