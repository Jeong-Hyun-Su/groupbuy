package com.groupbuy.participation.infrastructure

import com.groupbuy.participation.domain.ParticipantCountProvider
import com.groupbuy.participation.domain.ParticipationRepository
import org.springframework.stereotype.Component

/**
 * Phase 1 구현. 상세 조회마다 count 쿼리가 나간다 — LT-01 에서 병목으로 드러날 것을 의도한 구현.
 * Phase 2 에서 RedisParticipantCountProvider 로 교체한다.
 */
@Component
class DbParticipantCountProvider(
    private val participationRepository: ParticipationRepository,
) : ParticipantCountProvider {

    override fun currentCount(dealId: Long): Int = participationRepository.countOccupying(dealId)
}
