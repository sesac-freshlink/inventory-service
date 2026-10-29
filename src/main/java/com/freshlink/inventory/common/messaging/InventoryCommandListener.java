package com.freshlink.inventory.common.messaging;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.DisposeMessage;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReserveMessage;

import com.freshlink.inventory.common.metrics.CommandMetrics;
import com.freshlink.inventory.domain.inventory.InventoryCommandService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class InventoryCommandListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);

    /** 읽지 못한 본문은 이만큼만 로그에 남긴다 */
    private static final int BODY_PREVIEW_LENGTH = 300;

    private final InventoryCommandService inventoryCommandService;
    private final CommandMetrics commandMetrics;
    private final int maxReceiveCount;

    public InventoryCommandListener(
            InventoryCommandService inventoryCommandService,
            CommandMetrics commandMetrics,
            @Value("${app.queue.max-receive-count}") int maxReceiveCount
    ) {
        this.inventoryCommandService = inventoryCommandService;
        this.commandMetrics = commandMetrics;
        this.maxReceiveCount = maxReceiveCount;
    }

    /**
     * 트랜잭션(handle) 바깥에서 시간을 잰다. 커밋 실패까지 failed로 잡힌다.
     * 예외는 그대로 던져 SQS가 다시 보내게 한다 (maxReceiveCount번 실패하면 DLQ).
     * 던지기 전에 쪽지 번호(messageId·orderId 등)와 받은 횟수를 로그 한 줄로 남긴다.
     * Spring Cloud AWS도 "Error processing message"를 찍지만 SQS 쪽 ID만 있어서 Loki에서 쪽지로 찾을 수 없다
     */
    @SqsListener("${app.queue.commands}")
    public void onCommand(
            String body,
            @Header(name = SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT, required = false)
            String receiveCount
    ) {
        BaseMessage message;
        try {
            message = MessageJson.fromJson(body);
        } catch (RuntimeException e) {
            commandMetrics.recordUnreadable();
            log.atLevel(level(receiveCount)).log("쪽지 본문을 읽지 못함 {} 이유={} 본문={}",
                    attempt(receiveCount), reason(e), preview(body));
            throw e;
        }

        try {
            commandMetrics.record(
                    message.type().name(),
                    () -> inventoryCommandService.handle(message)
            );
        } catch (RuntimeException e) {
            log.atLevel(level(receiveCount)).log("쪽지 처리 실패 type={} messageId={} storeId={} {} {} 이유={}",
                    message.type(), message.messageId(), message.storeId(), refs(message),
                    attempt(receiveCount), reason(e));
            throw e;
        }
    }

    /** 종류마다 Loki에서 찾을 때 쓰는 번호. 성공 로그와 같은 key=value 모양 */
    private static String refs(BaseMessage message) {
        return switch (message) {
            case ReserveMessage m -> "orderId=%d productId=%d quantity=%d"
                    .formatted(m.orderId(), m.productId(), m.quantity());
            case ConfirmMessage m -> "orderId=%d".formatted(m.orderId());
            case ReleaseMessage m -> "orderId=%d reason=%s".formatted(m.orderId(), m.reason());
            case InboundMessage m -> "shipmentId=%s productCodes=%s"
                    .formatted(m.shipmentId(), m.items().stream().map(InboundItem::productCode).toList());
            case DisposeMessage m -> "disposalId=%d lotId=%d quantity=%d"
                    .formatted(m.disposalId(), m.lotId(), m.quantity());
            default -> "";
        };
    }

    /** 마지막 시도면 DLQ로 가므로 error, 아니면 SQS가 다시 보내므로 warn */
    private Level level(String receiveCount) {
        return isLastAttempt(receiveCount) ? Level.ERROR : Level.WARN;
    }

    private String attempt(String receiveCount) {
        String count = receiveCount == null ? "?" : receiveCount;
        String text = "receiveCount=" + count + "/" + maxReceiveCount;
        return isLastAttempt(receiveCount) ? text + " (DLQ로 간다)" : text;
    }

    private boolean isLastAttempt(String receiveCount) {
        if (receiveCount == null) {
            return false;
        }
        try {
            return Integer.parseInt(receiveCount) >= maxReceiveCount;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String reason(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String preview(String body) {
        if (body == null) {
            return "null";
        }
        return body.length() <= BODY_PREVIEW_LENGTH ? body : body.substring(0, BODY_PREVIEW_LENGTH) + "…";
    }
}
