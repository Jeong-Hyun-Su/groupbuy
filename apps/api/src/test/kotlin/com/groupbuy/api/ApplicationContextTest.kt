package com.groupbuy.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * 컨텍스트가 뜨고 Flyway 마이그레이션이 실제 PostgreSQL(Testcontainers)에 적용되는지 확인.
 * Docker 가 필요하다. 없는 환경(로컬)에서는 건너뛰고, CI 에서는 항상 실행된다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @Test
    fun `컨텍스트가 로드된다`() {
    }
}
