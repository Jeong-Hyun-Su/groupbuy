package com.groupbuy.common.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 시간 접근 추상화. 도메인/애플리케이션 코드는 Instant.now() 를 직접 호출하지 않는다.
 * 마감·선점 만료처럼 시간에 의존하는 규칙을 테스트에서 고정하기 위함.
 */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId = ZoneId.of("Asia/Seoul")
}

class SystemTimeProvider(private val clock: Clock = Clock.systemUTC()) : TimeProvider {
    override fun now(): Instant = Instant.now(clock)
}

/** 테스트용. 원하는 시각으로 고정하거나 앞으로 감을 수 있다. */
class FixedTimeProvider(private var current: Instant) : TimeProvider {
    override fun now(): Instant = current
    fun set(instant: Instant) { current = instant }
    fun advanceSeconds(seconds: Long) { current = current.plusSeconds(seconds) }
}
