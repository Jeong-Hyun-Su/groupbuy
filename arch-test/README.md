# arch-test

설계서 5.3 모듈 의존 규칙을 ArchUnit 으로 강제한다. 위반하면 `./gradlew :arch-test:test` 가 실패한다.

규칙 요약:
1. `deal` 은 다른 비즈니스 모듈에 의존하지 않는다
2. `participation` 은 `deal` 에만 의존한다
3. `payment` 는 `deal`, `participation` 에만 의존한다
4. `settlement`, `realtime`, `search` 는 서로도, 위 셋도 직접 호출하지 않는다 (이벤트로만)
5. 모듈 안에서 `api → application → domain`, `infrastructure → domain` 방향만 허용
6. `domain` 패키지는 Spring 에 의존하지 않는다 (JPA 애노테이션은 허용)
7. 모듈은 `apps/` 코드를 참조하지 않는다
8. 모듈 간 순환 의존 없음
