<#
.SYNOPSIS
    E-01 동시 예약 시험. 같은 점포·상품에 RESERVE 쪽지를 한꺼번에 넣는다 (T-22).

.DESCRIPTION
    orderId = OrderIdBase + i (기본 900000~900099), messageId = 새 GUID,
    그룹 ID = storeId 문자열, 중복 제거 ID = messageId 로 만들어 send-message-batch(10건씩)로 보낸다.
    JSON은 파일로 넘긴다(PowerShell 5.1이 명령줄로 넘긴 JSON의 따옴표를 지운다. 접속 가이드 4-1).
    절차와 결과 확인은 docs/동시성-테스트-절차.md를 따른다.

.EXAMPLE
    $env:AWS_PROFILE = "freshlink-dev"
    .\scripts\e01-reserve-100.ps1 -StoreId 1 -ProductId 15

.EXAMPLE
    .\scripts\e01-reserve-100.ps1 -StoreId 1 -ProductId 15 -DryRun    # 보내지 않고 배치 파일만 만든다
#>
param(
    [long]$StoreId = 1,
    [Parameter(Mandatory = $true)][long]$ProductId,
    [int]$Quantity = 1,
    [int]$Count = 100,
    [string]$QueuePrefix = "dev-taewoo-",
    [long]$OrderIdBase = 900000,
    [switch]$DryRun,
    [switch]$AllowSharedQueue
)

$ErrorActionPreference = "Stop"

if ($Count -lt 1) {
    throw "Count는 1 이상이어야 한다: $Count"
}
# 공용 큐(dev-)는 dev 클러스터 파드가 읽는다. 시험 쪽지가 섞이지 않게 기본으로 막는다
if ($QueuePrefix -eq "dev-" -and -not $AllowSharedQueue) {
    throw "공용 큐(dev-)에는 보내지 않는다. 꼭 필요하면 -AllowSharedQueue를 붙인다"
}

$queueName = "${QueuePrefix}inventory-commands.fifo"
$workDir = Join-Path $env:TEMP ("e01-reserve-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $workDir | Out-Null

if (-not $DryRun) {
    $queueUrl = aws sqs get-queue-url --queue-name $queueName --query QueueUrl --output text
    if ($LASTEXITCODE -ne 0 -or -not $queueUrl) {
        throw "큐 주소를 못 찾았다: $queueName (AWS_PROFILE, 로그인 확인)"
    }
}

$kst = [TimeSpan]::FromHours(9)
$sent = 0
$failed = 0
$startedAt = [DateTimeOffset]::Now.ToOffset($kst)

for ($batchStart = 1; $batchStart -le $Count; $batchStart += 10) {
    $batchEnd = [Math]::Min($batchStart + 9, $Count)
    $entries = @()

    for ($i = $batchStart; $i -le $batchEnd; $i++) {
        $messageId = [guid]::NewGuid().ToString()
        $createdAt = [DateTimeOffset]::Now.ToOffset($kst).ToString("yyyy-MM-ddTHH:mm:sszzz")
        $orderId = $OrderIdBase + $i
        $body = '{"messageId":"' + $messageId + '","type":"RESERVE","createdAt":"' + $createdAt +
                '","orderId":' + $orderId + ',"storeId":' + $StoreId +
                ',"productId":' + $ProductId + ',"quantity":' + $Quantity + '}'

        $entries += [ordered]@{
            Id                     = "m$i"
            MessageBody            = $body
            MessageGroupId         = "$StoreId"
            MessageDeduplicationId = $messageId
        }
    }

    $file = Join-Path $workDir ("batch-{0:D3}.json" -f $batchStart)
    ConvertTo-Json -InputObject $entries -Depth 3 | Set-Content -Path $file -Encoding Ascii

    if ($DryRun) {
        $sent += $entries.Count
        continue
    }

    $fileUri = "file://" + ($file -replace "\\", "/")
    $result = aws sqs send-message-batch --queue-url $queueUrl --entries $fileUri --output json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) {
        throw "send-message-batch 실패: $file"
    }

    $sent += @($result.Successful).Count
    if ($result.Failed) {
        $failed += @($result.Failed).Count
        $result.Failed | ForEach-Object { Write-Warning ("보내기 실패 Id={0} Code={1} {2}" -f $_.Id, $_.Code, $_.Message) }
    }
}

$finishedAt = [DateTimeOffset]::Now.ToOffset($kst)

Write-Host ""
Write-Host ("큐            : {0}{1}" -f $queueName, $(if ($DryRun) { " (DryRun, 보내지 않음)" } else { "" }))
Write-Host ("점포/상품/수량: storeId={0}, productId={1}, quantity={2}" -f $StoreId, $ProductId, $Quantity)
Write-Host ("orderId 범위  : {0} ~ {1}" -f ($OrderIdBase + 1), ($OrderIdBase + $Count))
Write-Host ("보낸 건수     : {0} / {1} (실패 {2})" -f $sent, $Count, $failed)
Write-Host ("보낸 시각     : {0} ~ {1}" -f $startedAt.ToString("HH:mm:ss.fff"), $finishedAt.ToString("HH:mm:ss.fff"))
Write-Host ("배치 파일     : {0}" -f $workDir)
Write-Host ""
Write-Host "결과 확인: scripts\e01-check.sql (orderId 범위를 위 값으로 맞춘다)"

if ($failed -gt 0) {
    exit 1
}
