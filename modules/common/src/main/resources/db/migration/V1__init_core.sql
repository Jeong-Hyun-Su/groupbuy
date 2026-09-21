-- Phase 1 스키마. 설계서 6장.
-- 이후 마이그레이션 계획:
--   V2 (Phase 3): outbox_events, processed_events
--   V3 (Phase 5): settlements, reconciliation_results

CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sellers (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    seller_id   BIGINT       NOT NULL REFERENCES sellers(id),
    name        VARCHAR(200) NOT NULL,
    list_price  INTEGER      NOT NULL CHECK (list_price >= 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE deals (
    id                      BIGSERIAL PRIMARY KEY,
    product_id              BIGINT       NOT NULL REFERENCES products(id),
    seller_id               BIGINT       NOT NULL REFERENCES sellers(id),
    title                   VARCHAR(200) NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    list_price              INTEGER      NOT NULL CHECK (list_price >= 0),   -- 상품 가격 스냅샷
    min_participants        INTEGER      NOT NULL CHECK (min_participants >= 1),
    capacity                INTEGER      NOT NULL CHECK (capacity >= min_participants),
    start_at                TIMESTAMPTZ  NOT NULL,
    close_at                TIMESTAMPTZ  NOT NULL,
    final_participant_count INTEGER,
    final_discount_rate     INTEGER,
    closed_at               TIMESTAMPTZ,
    version                 INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT deals_period_chk CHECK (close_at > start_at)
);
-- 마감/오픈 대상 폴링 (ADR-05)
CREATE INDEX idx_deals_status_close_at ON deals (status, close_at);
CREATE INDEX idx_deals_status_start_at ON deals (status, start_at);

CREATE TABLE deal_tiers (
    id            BIGSERIAL PRIMARY KEY,
    deal_id       BIGINT  NOT NULL REFERENCES deals(id) ON DELETE CASCADE,
    min_count     INTEGER NOT NULL CHECK (min_count >= 1),
    discount_rate INTEGER NOT NULL CHECK (discount_rate BETWEEN 0 AND 100),
    UNIQUE (deal_id, min_count)
);

CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users(id),
    deal_id      BIGINT      NOT NULL REFERENCES deals(id),
    order_no     VARCHAR(64) NOT NULL UNIQUE,   -- PG 에 전달하는 주문번호
    list_amount  INTEGER     NOT NULL,
    final_amount INTEGER,                        -- 마감 후 확정
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE participations (
    id                      BIGSERIAL PRIMARY KEY,
    deal_id                 BIGINT      NOT NULL REFERENCES deals(id),
    user_id                 BIGINT      NOT NULL REFERENCES users(id),
    order_id                BIGINT      NOT NULL REFERENCES orders(id),
    status                  VARCHAR(20) NOT NULL,
    reserved_at             TIMESTAMPTZ NOT NULL,
    reservation_expires_at  TIMESTAMPTZ NOT NULL,
    confirmed_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- R2 중복 참여 최종 방어선. Redis 가 우회되거나 초기화돼도 DB 가 막는다.
    CONSTRAINT uq_participation_deal_user UNIQUE (deal_id, user_id)
);
-- 선점 만료 스캔 (10.2)
CREATE INDEX idx_participations_status_expires ON participations (status, reservation_expires_at);
-- 마감 시 확정 인원 집계
CREATE INDEX idx_participations_deal_status ON participations (deal_id, status);

CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES orders(id),
    pg_provider     VARCHAR(20)  NOT NULL,
    pg_payment_key  VARCHAR(200) UNIQUE,         -- PG 발급, 취소 시 사용
    order_no        VARCHAR(64)  NOT NULL UNIQUE,
    amount          INTEGER      NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    approved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refunds (
    id               BIGSERIAL PRIMARY KEY,
    payment_id       BIGINT       NOT NULL REFERENCES payments(id),
    amount           INTEGER      NOT NULL CHECK (amount > 0),
    reason           VARCHAR(40)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    idempotency_key  VARCHAR(64)  NOT NULL UNIQUE,   -- refund-{id}, PG Idempotency-Key
    pg_cancel_key    VARCHAR(200),
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    next_retry_at    TIMESTAMPTZ,
    last_error       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 환불 워커 폴링 (Phase 3)
CREATE INDEX idx_refunds_status_retry ON refunds (status, next_retry_at);
