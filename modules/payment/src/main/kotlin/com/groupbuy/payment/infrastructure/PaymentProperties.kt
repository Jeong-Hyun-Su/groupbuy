package com.groupbuy.payment.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `groupbuy.payment.*` — PG 어댑터 설정.
 * application 이 아니라 infrastructure 에 둔다: 외부 시스템 접속 정보는 어댑터의 관심사다
 * (레이어 규칙상 infrastructure 는 application 을 참조할 수 없다).
 */
@ConfigurationProperties("groupbuy.payment")
data class PaymentProperties(
    val provider: String = "TOSS",
    val toss: Toss = Toss(),
) {
    data class Toss(
        val baseUrl: String = "https://api.tosspayments.com",
        /** 테스트 시크릿 키. 운영 키는 환경변수·Secret 으로 주입한다 */
        val secretKey: String = "",
        val connectTimeout: Duration = Duration.ofSeconds(3),
        val readTimeout: Duration = Duration.ofSeconds(15),
    )
}
