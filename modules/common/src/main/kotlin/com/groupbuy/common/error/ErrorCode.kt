package com.groupbuy.common.error

/**
 * API 에러 코드. 설계서 9.1 의 에러 응답 표준 `{ code, message, traceId }` 의 code 값.
 * common 은 웹 프레임워크를 모른다 — status 는 HTTP 상태 코드 정수. HttpStatus 변환은 apps/api 의 핸들러가 한다.
 */
enum class ErrorCode(val status: Int, val defaultMessage: String) {
    // common
    INVALID_REQUEST(400, "요청이 올바르지 않습니다."),
    NOT_FOUND(404, "리소스를 찾을 수 없습니다."),
    INVALID_STATE_TRANSITION(409, "현재 상태에서 허용되지 않는 작업입니다."),

    // deal
    DEAL_NOT_FOUND(404, "딜을 찾을 수 없습니다."),
    DEAL_NOT_OPEN(409, "참여 가능한 상태가 아닙니다."),
    DEAL_INVALID_TIERS(400, "할인 티어 구성이 올바르지 않습니다."),
    DEAL_INVALID_PERIOD(400, "딜 기간이 올바르지 않습니다."),
    DEAL_INVALID_CAPACITY(400, "정원/최소 인원 설정이 올바르지 않습니다."),

    // participation
    DEAL_FULL(409, "정원이 마감되었습니다."),
    DUPLICATE_PARTICIPATION(409, "이미 참여한 딜입니다."),
    PARTICIPATION_NOT_FOUND(404, "참여 내역을 찾을 수 없습니다."),
    RESERVATION_EXPIRED(409, "결제 제한시간이 지났습니다."),

    // payment
    PAYMENT_AMOUNT_MISMATCH(400, "결제 금액이 일치하지 않습니다."),
    PAYMENT_APPROVAL_FAILED(502, "결제 승인에 실패했습니다."),
    PAYMENT_NOT_FOUND(404, "결제 내역을 찾을 수 없습니다."),
    PAYMENT_ALREADY_APPROVED(409, "이미 결제가 완료된 주문입니다."),
    PAYMENT_PENDING(502, "결제 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
    WEBHOOK_SIGNATURE_INVALID(401, "웹훅 서명이 올바르지 않습니다."),
}
