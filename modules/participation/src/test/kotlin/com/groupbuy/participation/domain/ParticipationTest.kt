package com.groupbuy.participation.domain

import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.InvalidStateTransitionException
import com.groupbuy.participation.domain.event.ParticipationConfirmed
import com.groupbuy.participation.domain.event.ParticipationExpired
import com.groupbuy.participation.domain.event.ParticipationReserved
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.time.Instant

class ParticipationTest {

    private val now: Instant = Instant.parse("2026-09-20T02:00:00Z")
    private val ttl: Duration = Duration.ofMinutes(10)

    private fun reserved(id: Long? = 1L): Participation =
        Participation.reserve(dealId = 1, userId = 42, orderId = 100, now = now, ttl = ttl)
            .also { if (id != null) ReflectionTestUtils.setField(it, "id", id) }

    private fun confirmed(): Participation = reserved().apply { confirm(now.plusSeconds(60)); pollEvents() }

    @Nested
    inner class `선점` {

        @Test
        fun `RESERVED 상태로 생성되고 만료 시각은 now + TTL 이며 선점 이벤트가 등록된다`() {
            val p = reserved()

            assertThat(p.status).isEqualTo(ParticipationStatus.RESERVED)
            assertThat(p.occupiesSlot).isTrue()
            assertThat(p.reservedAt).isEqualTo(now)
            assertThat(p.reservationExpiresAt).isEqualTo(now.plus(ttl))
            assertThat(p.pollEvents()).singleElement().isInstanceOf(ParticipationReserved::class.java)
        }

        @Test
        fun `TTL 은 양수여야 한다`() {
            assertThatThrownBy { Participation.reserve(1, 1, 1, now, Duration.ZERO) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `만료 시각에 도달하면 만료된 선점으로 본다`() {
            val p = reserved()

            assertThat(p.isReservationExpired(now.plus(ttl).minusSeconds(1))).isFalse()
            assertThat(p.isReservationExpired(now.plus(ttl))).isTrue()
        }
    }

    @Nested
    inner class `결제 확정` {

        @Test
        fun `TTL 안에 승인되면 CONFIRMED 가 되고 확정 이벤트가 등록된다`() {
            val p = reserved().apply { pollEvents() }

            p.confirm(now.plusSeconds(60))

            assertThat(p.status).isEqualTo(ParticipationStatus.CONFIRMED)
            assertThat(p.confirmedAt).isEqualTo(now.plusSeconds(60))
            assertThat(p.occupiesSlot).isTrue()
            assertThat(p.pollEvents()).singleElement().isInstanceOf(ParticipationConfirmed::class.java)
        }

        @Test
        fun `선점이 만료된 뒤에는 승인할 수 없다`() {
            assertThatThrownBy { reserved().confirm(now.plus(ttl)) }
                .isInstanceOf(DomainException::class.java)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_EXPIRED)
        }

        @Test
        fun `RESERVED 가 아니면 승인할 수 없다`() {
            assertThatThrownBy { confirmed().confirm(now.plusSeconds(120)) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }
    }

    @Nested
    inner class `만료와 재선점` {

        @Test
        fun `만료되면 자리를 내놓고 만료 이벤트가 등록된다`() {
            val p = reserved().apply { pollEvents() }

            p.expire(now.plus(ttl))

            assertThat(p.status).isEqualTo(ParticipationStatus.EXPIRED)
            assertThat(p.occupiesSlot).isFalse()
            assertThat(p.canReserveAgain).isTrue()
            assertThat(p.pollEvents()).singleElement().isInstanceOf(ParticipationExpired::class.java)
        }

        @Test
        fun `CONFIRMED 는 만료시킬 수 없다`() {
            assertThatThrownBy { confirmed().expire(now.plus(ttl)) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `만료된 자리는 새 주문으로 다시 선점할 수 있다`() {
            val p = reserved().apply { expire(now.plus(ttl)); pollEvents() }
            val later = now.plus(ttl).plusSeconds(30)

            p.reserveAgain(orderId = 200, now = later, ttl = ttl)

            assertThat(p.status).isEqualTo(ParticipationStatus.RESERVED)
            assertThat(p.orderId).isEqualTo(200)
            assertThat(p.reservedAt).isEqualTo(later)
            assertThat(p.reservationExpiresAt).isEqualTo(later.plus(ttl))
            assertThat(p.confirmedAt).isNull()
            assertThat(p.pollEvents()).singleElement().isInstanceOf(ParticipationReserved::class.java)
        }

        @Test
        fun `살아있는 선점이나 확정 참여는 다시 선점할 수 없다 (R2)`() {
            assertThat(reserved().canReserveAgain).isFalse()
            assertThat(confirmed().canReserveAgain).isFalse()
            assertThatThrownBy { confirmed().reserveAgain(200, now, ttl) }
                .isInstanceOf(InvalidStateTransitionException::class.java)
        }
    }

    @Nested
    inner class `마감 이후 전이` {

        @Test
        fun `성사 시 CONFIRMED → ADJUSTING → FINALIZED`() {
            val p = confirmed()

            p.startAdjusting()
            assertThat(p.status).isEqualTo(ParticipationStatus.ADJUSTING)
            assertThat(p.occupiesSlot).isFalse()

            p.finalize()
            assertThat(p.status).isEqualTo(ParticipationStatus.FINALIZED)
        }

        @Test
        fun `무산 시 CONFIRMED → REFUNDING → REFUNDED`() {
            val p = confirmed()

            p.startRefunding()
            assertThat(p.status).isEqualTo(ParticipationStatus.REFUNDING)

            p.markRefunded()
            assertThat(p.status).isEqualTo(ParticipationStatus.REFUNDED)
        }

        @Test
        fun `RESERVED 는 환불 흐름으로 들어갈 수 없다 (ADR-07 마감 시 인원에서 제외)`() {
            assertThatThrownBy { reserved().startAdjusting() }.isInstanceOf(InvalidStateTransitionException::class.java)
            assertThatThrownBy { reserved().startRefunding() }.isInstanceOf(InvalidStateTransitionException::class.java)
        }

        @Test
        fun `자진 취소는 CONFIRMED 에서만 가능하다`() {
            val p = confirmed()
            p.cancel()
            assertThat(p.status).isEqualTo(ParticipationStatus.CANCELLED)
            assertThat(p.canReserveAgain).isTrue()

            assertThatThrownBy { reserved().cancel() }.isInstanceOf(InvalidStateTransitionException::class.java)
        }
    }
}
