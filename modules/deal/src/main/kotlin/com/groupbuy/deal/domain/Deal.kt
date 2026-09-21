package com.groupbuy.deal.domain

import com.groupbuy.common.domain.AggregateRoot
import com.groupbuy.common.domain.Money
import com.groupbuy.common.error.DomainException
import com.groupbuy.common.error.ErrorCode
import com.groupbuy.common.error.InvalidStateTransitionException
import com.groupbuy.deal.domain.event.DealClosed
import com.groupbuy.deal.domain.event.DealOpened
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

/**
 * 딜 애그리거트. 설계서 2.3 불변 규칙 중 R3(할인율은 마감 시점 최종 인원으로 확정), R8(마감은 정확히 한 번)을 이 클래스가 책임진다.
 * R1/R2(정원·중복)는 participation 모듈, R4~R7(금액·환불)은 payment/settlement 모듈이 책임진다.
 */
@Entity
@Table(name = "deals")
class Deal private constructor(
    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(name = "seller_id", nullable = false)
    val sellerId: Long,

    title: String,

    @Column(name = "list_price", nullable = false)
    val listPrice: Int,

    @Column(name = "min_participants", nullable = false)
    val minParticipants: Int,

    @Column(nullable = false)
    val capacity: Int,

    @Column(name = "start_at", nullable = false)
    val startAt: Instant,

    @Column(name = "close_at", nullable = false)
    val closeAt: Instant,
) : AggregateRoot() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false)
    var title: String = title
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: DealStatus = DealStatus.DRAFT
        protected set

    @OneToMany(mappedBy = "deal", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("minCount ASC")
    private val tierEntities: MutableList<DiscountTier> = mutableListOf()

    val tiers: List<DiscountTier>
        get() = tierEntities.toList()

    @Column(name = "final_participant_count")
    var finalParticipantCount: Int? = null
        protected set

    @Column(name = "final_discount_rate")
    var finalDiscountRate: Int? = null
        protected set

    @Column(name = "closed_at")
    var closedAt: Instant? = null
        protected set

    @Version
    @Column(nullable = false)
    var version: Int = 0
        protected set

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set

    // ---------- 조회 ----------

    val isOpen: Boolean get() = status == DealStatus.OPEN

    /**
     * 참여 접수 가능 여부. OPEN 이고 마감 시각 전이어야 한다.
     * 마감 시각이 지났지만 아직 스케줄러가 CLOSING 으로 옮기지 못한 틈에 들어오는 참여를 막는다 (ADR-07 완화책의 일부).
     */
    fun acceptsParticipation(now: Instant): Boolean = isOpen && now.isBefore(closeAt)

    fun ensureAcceptsParticipation(now: Instant) {
        if (!acceptsParticipation(now)) throw DomainException(ErrorCode.DEAL_NOT_OPEN)
    }

    fun discountPolicy(): DiscountPolicy = DiscountPolicy(tierEntities.map { it.toRule() })

    /** 현재 인원 기준 예상 할인율. 확정 아님 (ADR-02) */
    fun projectedDiscountRate(currentCount: Int): Int = discountPolicy().rateFor(currentCount)

    fun projectedPrice(currentCount: Int): Int = Money.discounted(listPrice, projectedDiscountRate(currentCount))

    fun nextTierAfter(currentCount: Int): DiscountPolicy.TierRule? = discountPolicy().nextTierAfter(currentCount)

    /** 마감 후 확정 금액. 마감 전 호출은 오류 */
    fun finalPrice(): Int {
        val rate = finalDiscountRate ?: throw InvalidStateTransitionException("마감 전에는 확정 금액이 없습니다.")
        return Money.discounted(listPrice, rate)
    }

    // ---------- 상태 전이 ----------

    fun schedule() {
        transition(from = setOf(DealStatus.DRAFT), to = DealStatus.SCHEDULED)
    }

    fun open(now: Instant) {
        if (now.isBefore(startAt)) throw InvalidStateTransitionException("시작 시각 전에는 오픈할 수 없습니다.")
        transition(from = setOf(DealStatus.SCHEDULED), to = DealStatus.OPEN)
        registerEvent(DealOpened(dealId = requireId(), occurredAt = now))
    }

    /**
     * 마감 처리 점유. OPEN → CLOSING.
     * 여러 인스턴스가 동시에 마감을 시도해도 낙관적 락(@Version)과 이 전이로 한 인스턴스만 성공한다 (R8).
     */
    fun beginClosing(now: Instant) {
        if (now.isBefore(closeAt)) throw InvalidStateTransitionException("마감 시각 전에는 마감할 수 없습니다.")
        transition(from = setOf(DealStatus.OPEN), to = DealStatus.CLOSING)
    }

    /**
     * 마감 판정. CLOSING → SUCCEEDED | FAILED.
     * @param confirmedCount 결제 확정(CONFIRMED)된 참여자 수. RESERVED 는 제외한다 (ADR-07)
     */
    fun finishClosing(confirmedCount: Int, now: Instant): CloseResult {
        if (confirmedCount < 0) throw DomainException(ErrorCode.INVALID_REQUEST, "확정 인원은 0 이상이어야 합니다.")
        if (confirmedCount > capacity) throw DomainException(ErrorCode.INVALID_REQUEST, "확정 인원이 정원을 초과했습니다. (R1 위반)")

        val succeeded = confirmedCount >= minParticipants
        val rate = if (succeeded) discountPolicy().rateFor(confirmedCount) else 0

        transition(from = setOf(DealStatus.CLOSING), to = if (succeeded) DealStatus.SUCCEEDED else DealStatus.FAILED)
        finalParticipantCount = confirmedCount
        finalDiscountRate = rate
        closedAt = now

        val result = CloseResult(
            dealId = requireId(),
            status = status,
            finalParticipantCount = confirmedCount,
            finalDiscountRate = rate,
            finalPrice = Money.discounted(listPrice, rate),
        )
        registerEvent(DealClosed(result = result, occurredAt = now))
        return result
    }

    fun markSettled() {
        transition(from = setOf(DealStatus.SUCCEEDED), to = DealStatus.SETTLED)
    }

    fun cancel() {
        transition(from = setOf(DealStatus.DRAFT, DealStatus.SCHEDULED), to = DealStatus.CANCELLED)
    }

    private fun transition(from: Set<DealStatus>, to: DealStatus) {
        if (status !in from) {
            throw InvalidStateTransitionException("딜 상태 전이 불가: $status → $to")
        }
        status = to
    }

    private fun requireId(): Long = id ?: throw IllegalStateException("영속화되지 않은 딜입니다.")

    private fun addTier(tier: DiscountTier) {
        tier.deal = this
        tierEntities.add(tier)
    }

    companion object {
        fun create(
            productId: Long,
            sellerId: Long,
            title: String,
            listPrice: Int,
            minParticipants: Int,
            capacity: Int,
            startAt: Instant,
            closeAt: Instant,
            tiers: List<DiscountPolicy.TierRule>,
        ): Deal {
            if (title.isBlank()) throw DomainException(ErrorCode.INVALID_REQUEST, "제목은 비어 있을 수 없습니다.")
            if (listPrice < 0) throw DomainException(ErrorCode.INVALID_REQUEST, "정가는 0 이상이어야 합니다.")
            if (minParticipants < 1) throw DomainException(ErrorCode.DEAL_INVALID_CAPACITY, "최소 인원은 1 이상이어야 합니다.")
            if (capacity < minParticipants) throw DomainException(ErrorCode.DEAL_INVALID_CAPACITY, "정원은 최소 인원 이상이어야 합니다.")
            if (!closeAt.isAfter(startAt)) throw DomainException(ErrorCode.DEAL_INVALID_PERIOD, "마감 시각은 시작 시각 이후여야 합니다.")

            DiscountPolicy(tiers) // 티어 규칙 검증

            val deal = Deal(productId, sellerId, title, listPrice, minParticipants, capacity, startAt, closeAt)
            tiers.sortedBy { it.minCount }.forEach { deal.addTier(DiscountTier(it.minCount, it.discountRate)) }
            return deal
        }
    }
}
