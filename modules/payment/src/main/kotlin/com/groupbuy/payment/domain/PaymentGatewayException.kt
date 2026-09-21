package com.groupbuy.payment.domain

/**
 * PG 호출 실패. `retryable` 이 재시도 정책을 가른다 (설계서 10.3).
 *
 * - 일시 오류(타임아웃, 5xx, 네트워크) → retryable = true. 승인 경로에서는 결과가 불확실하므로
 *   선점을 풀지 않고 사용자에게 재시도를 안내한다. 실제로 승인됐다면 웹훅이 뒤따라 정리한다.
 * - 영구 오류(카드 거절, 잘못된 요청) → retryable = false. 승인은 확정적으로 실패했다.
 */
class PaymentGatewayException(
    val pgCode: String,
    override val message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
