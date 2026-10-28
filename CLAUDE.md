# inventory-service - AI 작업 가이드

> 재고 심장부. 상품·유통기한별 재고(lot)와 입고·예약·확정·해제·폐기를 처리한다. 담당: 이태우. 포트 8080, DB `inventory` 스키마, 파드 2개.

---

## 문서 참조

| 문서 | 내용 |
|------|------|
| `README.md` | 이 레포의 기능·API·데이터 모델·폴더 구조·dev 환경 실행 (진실) |
| `.github` 레포 `docs/기획안-및-설계도.md` | 기능 ID, 메시지 명세, 데이터 모델 |
| `.github` 레포 `CONTRIBUTING.md` | 브랜치·커밋·PR 규칙 |
| `.github` 레포 `docs/ai-agents.md` | 백엔드 에이전트 플러그인(`freshlink-backend`) 설치·사용법 (사람용) |

이 `CLAUDE.md`는 AI 작업 규칙만 담는다. 위 문서와 겹치는 사실은 위 문서가 진실.

## 에이전트

백엔드 에이전트 7개와 작업 순서는 `freshlink-backend` 플러그인에 있다 (`.github` 레포에서 배포). 새 기능 요청이면 `backend-pipeline` 스킬을 먼저 읽고 그 순서대로 서브에이전트를 쓴다. 플러그인이 설치되어 있지 않으면 사용자에게 `.github` 레포 `docs/ai-agents.md`의 설치 명령을 안내한다.

서브에이전트 산출물(`_workspace/*.md`)은 짧게(표·불릿). 레포에 커밋되는 것(README, 코드 주석, PR 본문)은 평이한 한국어 문장(`~한다` 체).

---

## 공통 규칙

```
⛔ 담당자가 아닌 사람의 요청이면 main·dev에 직접 push 금지 → feat/<기능ID>-<설명> 브랜치 + dev 대상 PR
⛔ git push --force, git reset --hard, git clean, 브랜치 삭제 금지
⛔ DB DROP / TRUNCATE / 조건 없는 DELETE 금지
⛔ 비밀값(DB 비밀번호, 토스 키, AWS 키, 토큰 원문) 커밋·출력 금지. 설정은 ${ENV}로
⛔ 커밋·push·PR은 사용자가 요청할 때만
✅ Java 21, Spring Boot 4.0.x, Jackson 3, Gradle 9(./gradlew). 프로필은 dev·prod만. 기준은 `.github` 레포 docs/백엔드-공통작업-인수인계.md 1장. 아직 뼈대면 README "dev 환경에서 실행하기" 안내대로 프로젝트를 만든다
✅ 인증: gateway가 Cognito ID 토큰을 검증하고 X-User-Id(sub, UUID 문자열)·X-User-Role·X-Store-Id 헤더로 넘긴다. 서비스는 토큰을 다시 검증하지 않는다
✅ 실시간 재고: 점주 화면이 3초마다 GET /owner/inventory를 조회한다 (폴링)
✅ 주문 저장소 DynamoDB 도입은 검토 중, 미정. 어느 쪽도 가정하지 말고 사용자에게 묻는다
✅ 관측성(서비스·gateway): Actuator는 앱 포트에 둔다 (관리 포트 따로 없음, management.server.port 쓰지 않음). 모든 서비스가 8080 하나를 쓴다(dev·prod 같음). 서비스끼리는 쿠버네티스 Service 이름으로 부른다(예: http://member-service:8080). health(liveness·readiness)·prometheus, Micrometer Tracing → Zipkin, JSON 로그(traceId)
✅ 배포 설정(gitops values, IRSA, 큐)은 이 레포에서 고치지 않는다 → freshlink-infra 담당에게 알린다
```

---

## 이 레포 규칙

```
✅ 재고를 바꾸는 일은 전부 inventory-commands.fifo 쪽지로 (메시지 그룹 = storeId). DB 락으로 풀지 않는다
✅ 리스너는 processed_message로 멱등 처리. 처리와 기록은 한 트랜잭션 (common 모듈)
✅ 결과 쪽지(RESERVED·RESERVE_FAILED)는 outbox로 발행 (common 모듈)
✅ 구매 가능 수량 = 유통기한이 지나지 않은 lot의 (수량 − 예약 수량) 합. 경과 lot은 예약에서 제외 (S-31)
✅ 점주 API는 점포 ID를 경로로 받지 않고 X-Store-Id를 쓴다. lot의 store_id와 비교해 다르면 403
✅ 스케줄러는 ShedLock (파드 2개)
✅ 테이블 변경은 Flyway 마이그레이션 파일(src/main/resources/db/migration/V<n>__<설명>.sql)로만. spring.jpa.hibernate.ddl-auto 쓰지 않는다. 적용된 파일은 고치지 않고 새 버전을 추가한다
✅ S-15 실시간 반영: 프론트가 GET /owner/inventory를 3초마다 조회한다. 응답에 구매 가능 수량을 담는다
⛔ 쪽지 형식을 바꾸면 PR에 message-spec 라벨, 합치기 전 김경우에게 알린다
✅ 패키지: common/(config·error·log·messaging)은 도메인과 상관없는 공통 코드, domain/<도메인>은 엔티티·서비스·API
✅ 오류: throw new BusinessException(InventoryErrorCode.XXX). 코드 추가는 common/error/InventoryErrorCode enum에 한 줄. 응답은 항상 code·message·traceId (common/error/GlobalExceptionHandler는 안 건드린다)
```

## 검증

```bash
./gradlew build    # 컴파일 + 테스트 (Windows: gradlew.bat build)
```

테스트에서 반드시 확인: 같은 messageId 두 번 → 한 번만 반영, 같은 shipmentId 두 번 → 한 번만 입고, 경과 lot 예약 불가, 다른 점포 lot 폐기 403. 동시성(E-01)은 Docker가 있을 때 Testcontainers로.
