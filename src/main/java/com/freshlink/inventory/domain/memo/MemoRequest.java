package com.freshlink.inventory.domain.memo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "점주 상품 메모 저장. 처음 만들 때는 version 없이, 고칠 때는 읽은 version을 보낸다. "
        + "그 사이 다른 화면이 바꿨으면 409 MEMO_CHANGED. 내용을 다 지우려면 DELETE를 쓴다")
public record MemoRequest(
        @NotBlank
        @Size(max = ProductMemo.MAX_LENGTH)
        String content,

        Long version
) {
}
