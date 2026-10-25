# dev 확인 절차 (T-12 ~ T-25 마무리)

PC 자동 테스트(Testcontainers·MSW)로 끝나지 않는 확인을 AWS dev를 **한 번 켜서** 순서대로 한다. 2~7번은 PC inventory 하나와 개인 큐만 쓰므로 dev 클러스터 없이 **RDS와 SQS만** 있으면 된다. 8번만 dev 클러스터가 필요하다.

명령은 Windows PowerShell 5.1 기준이다. 근거 문서:

- [개발 환경 접속 가이드](https://github.com/sesac-freshlink/.github/blob/main/docs/개발환경-접속.md) 2~4장: 포트 포워딩, 실행 구성, 개인 큐
- [팀원 가이드 5](https://github.com/sesac-freshlink/.github/blob/main/docs/팀원-가이드-5-주말-dev-켜기.md): dev 켜고 끄기
- [실패-시나리오-절차.md](실패-시나리오-절차.md), [동시성-테스트-절차.md](동시성-테스트-절차.md)

비밀값(DB 비밀번호, 키)은 이 문서와 명령 기록에 남기지 않는다.

| 순서 | 할 일 | 확인하는 기능 |
|---|---|---|
| 1 | dev 켜기, 포트 포워딩, PC inventory 띄우기 | |
| 2 | INBOUND 10 → RESERVE 3 → CONFIRM | T-12·T-14 |
| 3 | RESERVE 2 → RELEASE(EXPIRED) | T-14 |
| 4 | 기한 지난 INBOUND → 폐기 API → DISPOSE 처리 | T-17 |
| 5 | `EXPIRY_CRON` 1분으로 재시작 → 상태 갱신 로그 | T-18 |
| 6 | 동시 예약 100건 | T-22 |
| 7 | 상품코드 없는 INBOUND → DLQ → 재전송 | T-23 |
| 8 | dev 주소에서 이력·임박·폐기 화면 | T-15·T-19·T-21 |
| 9 | 진아님께 `EXPIRY_CRON` values 추가 요청 | T-18 |
| 10 | dev 끄기 | |

## 1. 준비

1. 팀 채팅에 "dev 켭니다 (DB만)" 또는 "(클러스터 O)"를 남기고 팀원 가이드 5의 3장대로 켠다. 2~7번만 할 때는 RDS와 접속용 서버만 켠다.
2. 접속 가이드 2장대로 포트 포워딩 창을 연다(닫지 않는다).
3. PC inventory를 접속 가이드 3장 실행 구성으로 띄운다. `QUEUE_PREFIX=dev-taewoo-`가 들어 있어야 한다. 로그에 `Started InventoryServiceApplication`이 보이면 된다.
4. 새 PowerShell 창에서 아래를 붙여 넣는다. 이후 단계는 이 창에서 한다.

```powershell
$env:AWS_PROFILE = "freshlink-dev"
$cmd = aws sqs get-queue-url --queue-name dev-taewoo-inventory-commands.fifo --query QueueUrl --output text
$res = aws sqs get-queue-url --queue-name dev-taewoo-inventory-results --query QueueUrl --output text

# 명령 쪽지 하나를 개인 FIFO 큐로 보낸다. JSON은 파일로 넘긴다(PowerShell 5.1 따옴표 문제)
function Send-Cmd([string]$json, [long]$storeId = 1) {
    $id = [guid]::NewGuid().ToString()
    $now = [DateTimeOffset]::Now.ToOffset([TimeSpan]::FromHours(9)).ToString("yyyy-MM-ddTHH:mm:sszzz")
    # 맨 앞 { 뒤에 messageId·createdAt을 끼워 넣는다
    $body = ([regex]'^\{').Replace($json, '{"messageId":"' + $id + '","createdAt":"' + $now + '",', 1)
    $file = Join-Path $env:TEMP "cmd-$id.json"
    [IO.File]::WriteAllText($file, $body)
    aws sqs send-message --queue-url $cmd --message-body ("file://" + ($file -replace "\\", "/")) --message-group-id "$storeId" --message-deduplication-id $id --query MessageId --output text
}

# 개인 결과 큐에 쌓인 RESERVED·RESERVE_FAILED를 본다(지우지 않는다)
function Show-Results { aws sqs receive-message --queue-url $res --max-number-of-messages 10 --visibility-timeout 0 --query "Messages[].Body" }
```

DB 확인은 MySQL 클라이언트로 `127.0.0.1:3306`의 `inventory` 스키마에 붙어서 한다. 시험 상품의 ID를 먼저 확인한다.

```sql
SELECT id, product_code, name, price FROM product WHERE product_code IN ('P015', 'P016');
```

아래 예시는 P015 = productId 15, 점포 1 기준이다. 결과가 다르면 숫자를 바꾼다. 점포 1에 P015의 더 이른 미경과 lot이 이미 있으면 예약이 FEFO로 그 lot에서 잡힌다. 그때는 `reservation.lot_id`로 lot을 찾아 확인한다.

## 2. INBOUND 10 → RESERVE 3 → CONFIRM (T-12·T-14)

```powershell
$ship = "SHP-DEVCHK-" + (Get-Date -Format "yyyyMMddHHmmss")
Send-Cmd ('{"type":"INBOUND","storeId":1,"shipmentId":"' + $ship + '","items":[{"productCode":"P015","quantity":10,"expiryDate":"2026-12-31"}]}')
Send-Cmd '{"type":"RESERVE","orderId":800001,"storeId":1,"productId":15,"quantity":3}'
Show-Results     # RESERVED, orderId 800001, unitPrice·productName·lots 포함
Send-Cmd '{"type":"CONFIRM","orderId":800001,"storeId":1}'
```

```sql
SELECT id, quantity, reserved_quantity FROM inventory_lot
WHERE store_id = 1 AND product_id = 15 AND expiry_date = '2026-12-31 23:59:59';        -- quantity 7, reserved 0 (이 lot에 다른 재고가 없을 때)
SELECT order_id, lot_id, quantity, status FROM reservation WHERE order_id = 800001;    -- CONFIRMED
SELECT type, quantity, ref_id FROM ledger WHERE ref_id IN ('800001') OR ref_id = '<위 $ship 값>';  -- INBOUND +10, SALE -3
```

## 3. RESERVE 2 → RELEASE(EXPIRED) (T-14)

```powershell
Send-Cmd '{"type":"RESERVE","orderId":800002,"storeId":1,"productId":15,"quantity":2}'
Send-Cmd '{"type":"RELEASE","orderId":800002,"storeId":1,"reason":"EXPIRED"}'
```

```sql
SELECT status FROM reservation WHERE order_id = 800002;                 -- RELEASED
SELECT quantity, reserved_quantity FROM inventory_lot
WHERE store_id = 1 AND product_id = 15 AND expiry_date = '2026-12-31 23:59:59';  -- 7 / 0 (예약 복원)
SELECT COUNT(*) FROM ledger WHERE ref_id = '800002';                    -- 0 (해제는 원장 없음)
```

## 4. 기한 지난 INBOUND → 폐기 API → DISPOSE (T-17)

```powershell
$ship2 = "SHP-DEVCHK-EXP-" + (Get-Date -Format "yyyyMMddHHmmss")
Send-Cmd ('{"type":"INBOUND","storeId":1,"shipmentId":"' + $ship2 + '","items":[{"productCode":"P016","quantity":5,"expiryDate":"2026-10-01"}]}')
```

```sql
SELECT id, quantity FROM inventory_lot
WHERE store_id = 1 AND product_id = 16 AND expiry_date = '2026-10-01 23:59:59';   -- 아래 lotId에 넣는다
```

PC inventory에 직접 요청한다. gateway 대신 헤더를 손으로 붙인다(`X-User-Id`는 아무 UUID).

```powershell
$lotId = <위 id>
Invoke-RestMethod -Method Post -Uri http://localhost:8080/owner/disposals `
    -Headers @{ "X-Store-Id" = "1"; "X-User-Id" = "00000000-0000-0000-0000-000000000001" } `
    -ContentType "application/json" -Body ('{"lotId":' + $lotId + ',"quantity":5}')
# 202 { disposalId, lotId, quantity }. 정상 lot이면 409, 다른 점포 헤더면 403
```

Outbox 발행기가 1초 안에 DISPOSE를 같은 개인 큐로 보내고 PC inventory가 다시 받아 처리한다.

```sql
SELECT id, status, processed_at FROM disposal ORDER BY id DESC LIMIT 1;                  -- DONE
SELECT quantity FROM inventory_lot WHERE id = <lotId>;                                     -- 0
SELECT type, quantity, ref_id FROM ledger WHERE lot_id = <lotId> ORDER BY id;              -- INBOUND +5, DISPOSE -5
```

## 5. 유통기한 스케줄러 1분 주기 (T-18)

PC inventory 실행 구성의 환경 변수에 `EXPIRY_CRON=0 * * * * *`를 더해 다시 띄운다. 1분 안에 로그에 아래 줄이 찍히면 된다.

```
유통기한 상태 갱신: 경과 n건, 임박 m건
```

```sql
SELECT name, lock_until, locked_by FROM shedlock WHERE name = 'lotStatusJob';     -- 행이 있다
SELECT id, expiry_date, status FROM inventory_lot WHERE store_id = 1 AND quantity > 0 ORDER BY expiry_date LIMIT 10;
```

확인 뒤 `EXPIRY_CRON`을 지우고 다시 띄운다(다른 단계 로그가 깔끔하다).

## 6. 동시 예약 100건 (T-22)

[동시성-테스트-절차.md](동시성-테스트-절차.md) 3~6장대로 한다. 요약:

```powershell
.\scripts\e01-reserve-100.ps1 -StoreId 1 -ProductId 15 -Quantity 1 -Count 100 -QueuePrefix dev-taewoo-
```

`scripts/e01-check.sql` 결과 표를 캡처하고 `scripts/e01-cleanup.sql`로 시험 예약을 정리한다(COMMIT은 직접).

## 7. 상품코드 없는 INBOUND → DLQ → 재전송 (T-23)

[실패-시나리오-절차.md](실패-시나리오-절차.md) 4장대로 한다. 쪽지가 DLQ로 갈 때까지 같은 점포(그룹 1)의 다음 쪽지가 기다리므로 6번을 끝낸 뒤 한다.

## 8. dev 화면 (T-15·T-19·T-21)

PR이 dev에 머지되고 ArgoCD가 새 이미지를 올린 뒤, dev 클러스터가 켜져 있을 때 한다.

1. dev 주소(CloudFront)에 점주 계정으로 로그인한다.
2. **변동 이력:** 2·4번에서 만든 입고 +10, 판매 −3, 폐기 −5가 보인다. 종류 버튼으로 거르면 그 종류만 남는다.
3. **임박·폐기:** 경과 탭에 기한 지난 lot이 보인다. 하나를 골라 [선택 폐기] → [폐기 요청] → "폐기 요청됨" 토스트 → **3초 안에** 그 lot 수량이 줄거나 목록에서 빠진다.
4. 위 화면을 캡처해 `ai_docs\WBS15\`, `ai_docs\WBS19\`에 dev 이름으로 더 넣는다.

dev 클러스터는 공용 큐(`dev-`)를 쓴다. PC inventory는 개인 큐(`dev-taewoo-`)라 서로 쪽지를 가져가지 않는다.

## 9. 진아님께 요청 (T-18)

시연 전에 아래 문구로 요청한다.

> gitops `envs/dev/inventory-service/values.yaml`의 `env`에 `EXPIRY_CRON: "0 * * * * *"`를 추가해 주세요. 유통기한 스케줄러를 시연 때 1분마다 돌리려는 것입니다. 시연이 끝나면 지워도 됩니다(기본은 자정). ShedLock(lotStatusJob)이 있어 파드 2개여도 한 번만 돕니다.

## 10. 끄기

1. PC inventory와 포트 포워딩 창을 끈다.
2. 팀원 가이드 5의 5장대로 끈다. 클러스터를 켰으면 `destroy`까지.
3. 팀 채팅에 "dev 껐습니다"를 남긴다.
