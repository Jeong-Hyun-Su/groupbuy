# settlement (Phase 5)

정산 집계, 원장 대조. **R7 책임 모듈.**

- 일 배치: 딜 단위 gross / refund / fee / net 스냅샷 (ADR-10, 멱등 upsert)
- 원장 대조: PG 거래내역 vs 내부 payments/refunds → `reconciliation_results`
- 이벤트 컨슈머로만 동작. deal/participation/payment 를 직접 호출하지 않는다.
