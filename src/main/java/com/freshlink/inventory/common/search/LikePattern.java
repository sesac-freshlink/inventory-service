package com.freshlink.inventory.common.search;

/**
 * 검색어를 LIKE 조건으로 바꾼다. %, _ 는 글자 그대로 찾도록 \ 로 막는다(MySQL LIKE 기본 이스케이프 문자, JPQL은 ESCAPE '\').
 * 점주 재고 검색(S-10)과 고객 상품 검색(U-10)이 같이 쓴다
 */
public final class LikePattern {

    private LikePattern() {
    }

    /** 앞뒤 공백을 뺀 검색어가 들어간 것. 비어 있으면 null(조건 없음) */
    public static String contains(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String escaped = q.strip()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
