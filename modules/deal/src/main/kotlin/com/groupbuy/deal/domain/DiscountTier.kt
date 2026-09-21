package com.groupbuy.deal.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "deal_tiers")
class DiscountTier(
    @Column(name = "min_count", nullable = false)
    val minCount: Int,

    @Column(name = "discount_rate", nullable = false)
    val discountRate: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deal_id", nullable = false)
    var deal: Deal? = null
        internal set

    fun toRule() = DiscountPolicy.TierRule(minCount, discountRate)
}
