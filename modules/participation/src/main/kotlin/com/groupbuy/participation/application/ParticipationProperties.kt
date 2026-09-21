package com.groupbuy.participation.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `groupbuy.participation.*` */
@ConfigurationProperties("groupbuy.participation")
data class ParticipationProperties(
    /** 선점 후 결제 제한시간. 설계서 2.5 기본 10분 */
    val reservationTtl: Duration = Duration.ofMinutes(10),
)
