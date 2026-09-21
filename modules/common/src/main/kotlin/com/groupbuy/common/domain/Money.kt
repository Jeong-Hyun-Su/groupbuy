package com.groupbuy.common.domain

/**
 * 설계서 6.3 금액 계산 규칙. 모든 금액은 정수(원), 내림.
 * 금액 계산은 반드시 여기서만 한다.
 */
object Money {

    /** 정가 × (100 − 할인율) / 100, 원 단위 내림 */
    fun discounted(listPrice: Int, discountRate: Int): Int {
        require(listPrice >= 0) { "listPrice must be >= 0" }
        require(discountRate in 0..100) { "discountRate must be in 0..100" }
        return listPrice * (100 - discountRate) / 100
    }

    /** 차액 환불액 = 결제액 − 확정액 */
    fun tierRefund(listAmount: Int, finalAmount: Int): Int {
        require(finalAmount <= listAmount) { "finalAmount must be <= listAmount" }
        return listAmount - finalAmount
    }

    /** 플랫폼 수수료. rateBasisPoints: 만분율 (예: 350 = 3.5%) */
    fun fee(amount: Long, rateBasisPoints: Int): Long {
        require(rateBasisPoints in 0..10_000) { "rateBasisPoints must be in 0..10000" }
        return amount * rateBasisPoints / 10_000
    }
}
