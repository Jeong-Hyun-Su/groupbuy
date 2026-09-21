package com.groupbuy.common.error

/**
 * 도메인 규칙 위반. GlobalExceptionHandler 가 ErrorCode.status 로 변환한다.
 */
open class DomainException(
    val errorCode: ErrorCode,
    message: String = errorCode.defaultMessage,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class NotFoundException(
    errorCode: ErrorCode = ErrorCode.NOT_FOUND,
    message: String = errorCode.defaultMessage,
) : DomainException(errorCode, message)

class InvalidStateTransitionException(message: String) :
    DomainException(ErrorCode.INVALID_STATE_TRANSITION, message)
