package com.groupbuy.deal.domain

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode

/**
 * 인원 → 할인율 규칙. 순수 값 객체, JPA 무관.
 *
 * - 티어는 minCount 오름차순, discountRate 오름차순이어야 한다 (많이 모일수록 싸진다)
 * - 어느 티어에도 못 미치면 0%
 */
class DiscountPolicy(rules: List<TierRule>) {

    data class TierRule(val minCount: Int, val discountRate: Int)

    val rules: List<TierRule> = rules.sortedBy { it.minCount }

    init {
        if (this.rules.isEmpty()) fail("티어는 최소 1개 이상이어야 합니다.")
        this.rules.forEach {
            if (it.minCount < 1) fail("티어 최소 인원은 1 이상이어야 합니다.")
            if (it.discountRate !in 0..100) fail("할인율은 0~100 사이여야 합니다.")
        }
        this.rules.zipWithNext().forEach { (prev, next) ->
            if (next.minCount <= prev.minCount) fail("티어 최소 인원은 중복 없이 오름차순이어야 합니다.")
            if (next.discountRate <= prev.discountRate) fail("할인율은 인원이 많을수록 커야 합니다.")
        }
    }

    /** 현재 인원에 적용되는 할인율. 예상(참여 중) 또는 확정(마감 시) 계산에 공통으로 쓴다. */
    fun rateFor(count: Int): Int =
        rules.lastOrNull { it.minCount <= count }?.discountRate ?: 0

    /** 다음 티어. 없으면 최고 티어 도달. */
    fun nextTierAfter(count: Int): TierRule? =
        rules.firstOrNull { it.minCount > count }

    private fun fail(message: String): Nothing =
        throw DomainException(ErrorCode.DEAL_INVALID_TIERS, message)
}
