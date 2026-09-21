-- 부하테스트·로컬 개발용 시드 데이터 (설계서 13.1 LT-01)
--
--   docker compose up -d
--   ./gradlew :apps:api:bootRun          # Flyway 가 스키마를 만든 뒤
--   psql -h localhost -U groupbuy -d groupbuy -f infra/seed/seed.sql
--
-- 여러 번 실행해도 안전하다 (ON CONFLICT DO NOTHING + 고정 id).
-- k6 는 X-User-Id 로 1~5000 을 쓴다 — 그래서 사용자 id 를 1..5000 으로 고정한다.

BEGIN;

-- ---------- 사용자 5,000명 ----------
INSERT INTO users (id, email, name)
SELECT i, 'user' || i || '@loadtest.local', '테스터' || i
  FROM generate_series(1, 5000) AS i
    ON CONFLICT (id) DO NOTHING;

-- ---------- 판매자 10명 ----------
INSERT INTO sellers (id, name)
SELECT i, '판매자' || i
  FROM generate_series(1, 10) AS i
    ON CONFLICT (id) DO NOTHING;

-- ---------- 상품 100개 ----------
INSERT INTO products (id, seller_id, name, list_price)
SELECT i,
       (i % 10) + 1,
       (ARRAY['무선 이어폰','기계식 키보드','캠핑 의자','전기 주전자','블루투스 스피커'])[(i % 5) + 1] || ' ' || i,
       (ARRAY[29000, 59000, 100000, 149000, 249000])[(i % 5) + 1]
  FROM generate_series(1, 100) AS i
    ON CONFLICT (id) DO NOTHING;

COMMIT;

-- ---------- 시퀀스 동기화 ----------
-- 수동 id 로 넣었으므로 시퀀스를 그 뒤로 민다. 안 하면 이후 INSERT 가 PK 충돌을 낸다
SELECT setval(pg_get_serial_sequence('users', 'id'), COALESCE((SELECT MAX(id) FROM users), 1));
SELECT setval(pg_get_serial_sequence('sellers', 'id'), COALESCE((SELECT MAX(id) FROM sellers), 1));
SELECT setval(pg_get_serial_sequence('products', 'id'), COALESCE((SELECT MAX(id) FROM products), 1));

-- ---------- LT-01 대상 딜 ----------
-- 정원 200, 최소 10, 지금부터 2시간 동안 열린 OPEN 딜.
-- k6 는 DEAL_ID 로 이 딜을 가리킨다.
INSERT INTO deals (
    id, product_id, seller_id, title, status, list_price,
    min_participants, capacity, start_at, close_at
)
VALUES (
    1, 1, 1, 'LT-01 부하테스트 딜 (정원 200)', 'OPEN', 100000,
    10, 200, now() - interval '1 minute', now() + interval '2 hours'
)
ON CONFLICT (id) DO UPDATE
   SET status   = 'OPEN',
       close_at = now() + interval '2 hours',
       final_participant_count = NULL,
       final_discount_rate     = NULL,
       closed_at               = NULL;

INSERT INTO deal_tiers (deal_id, min_count, discount_rate)
VALUES (1, 10, 10), (1, 30, 20), (1, 50, 30)
    ON CONFLICT (deal_id, min_count) DO NOTHING;

-- ---------- 목록 화면용 딜 30개 ----------
INSERT INTO deals (
    id, product_id, seller_id, title, status, list_price,
    min_participants, capacity, start_at, close_at
)
SELECT 100 + i,
       (i % 100) + 1,
       (i % 10) + 1,
       '공동구매 ' || i || '차',
       'OPEN',
       (ARRAY[29000, 59000, 100000, 149000, 249000])[(i % 5) + 1],
       5,
       50 + (i * 7) % 150,
       now() - interval '1 hour',
       now() + ((i % 48) + 1) * interval '1 hour'     -- 마감 임박순 정렬이 보이도록 흩뿌린다
  FROM generate_series(1, 30) AS i
    ON CONFLICT (id) DO NOTHING;

INSERT INTO deal_tiers (deal_id, min_count, discount_rate)
SELECT 100 + i, tier.min_count, tier.discount_rate
  FROM generate_series(1, 30) AS i
 CROSS JOIN (VALUES (5, 5), (15, 15), (30, 25)) AS tier(min_count, discount_rate)
    ON CONFLICT (deal_id, min_count) DO NOTHING;

SELECT setval(pg_get_serial_sequence('deals', 'id'), COALESCE((SELECT MAX(id) FROM deals), 1));
SELECT setval(pg_get_serial_sequence('deal_tiers', 'id'), COALESCE((SELECT MAX(id) FROM deal_tiers), 1));

-- ---------- 확인 ----------
SELECT 'users' AS table_name, count(*) FROM users
UNION ALL SELECT 'sellers', count(*) FROM sellers
UNION ALL SELECT 'products', count(*) FROM products
UNION ALL SELECT 'deals', count(*) FROM deals
UNION ALL SELECT 'deal_tiers', count(*) FROM deal_tiers;
