CREATE TABLE outbox (
                        id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                        message_id   VARCHAR(36)  NOT NULL,
                        message_type VARCHAR(30)  NOT NULL,
                        queue_name   VARCHAR(200) NOT NULL,
                        group_id     VARCHAR(64)  NULL,
                        payload      TEXT         NOT NULL,
                        trace_parent VARCHAR(55)  NULL,
                        published    BOOLEAN      NOT NULL DEFAULT FALSE,
                        created_at   DATETIME(6)  NOT NULL,
                        published_at DATETIME(6)  NULL,
                        UNIQUE KEY uk_outbox_message_id (message_id),
                        KEY idx_outbox_unpublished (published, id)
);

CREATE TABLE processed_message (
                                   message_id   VARCHAR(36) PRIMARY KEY,
                                   processed_at DATETIME(6) NOT NULL
);