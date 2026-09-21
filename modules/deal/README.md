# deal

딜의 등록, 상태 전이, 마감 판정. **시스템의 도메인 핵심.** 다른 모듈에 의존하지 않는다.

| 패키지 | 내용 |
|---|---|
| `domain` | `Deal` 애그리거트, `DealStatus`, `DiscountTier`, `DiscountPolicy`, `CloseResult`, `DealRepository` 포트 |
| `domain.event` | `DealOpened`, `DealClosed` 등 도메인 이벤트 |
| `application` | `DealCommandService` — 유스케이스 단위 트랜잭션 |
| `api` | 판매자용 딜 등록/취소 컨트롤러 |
| `infrastructure` | JPA 어댑터 |

**참여 인원은 이 모듈이 모른다.** 딜 상세(현재 인원·예상 할인율)의 조합은 `apps/api` 의 query 패키지에서 한다.
