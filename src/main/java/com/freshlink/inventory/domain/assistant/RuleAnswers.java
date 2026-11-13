package com.freshlink.inventory.domain.assistant;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI를 부를 수 없을 때 코드가 만드는 답 (문서 4-3, source = RULE). 버튼 질문만 요약에서 목록을 만들고,
 * 자유 질문은 안내 문구를 준다. 숫자는 요약 그대로 쓴다
 */
final class RuleAnswers {

    static final String FREE_QUESTION = "지금은 AI가 답할 수 없어요. 위 버튼 질문을 눌러 보세요.";
    /** 목록은 이만큼만 */
    static final int TOP = 5;

    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("M/d HH:mm");

    private RuleAnswers() {
    }

    static String answer(AssistantPreset preset, StoreSummary summary) {
        if (preset == null) {
            return FREE_QUESTION;
        }
        return switch (preset) {
            case Q1 -> expiring(summary);
            case Q2 -> reorder(summary);
            case Q3 -> lowStock(summary);
            case Q4 -> byHour(summary);
            case Q5 -> disposed(summary);
        };
    }

    private static String expiring(StoreSummary summary) {
        if (summary.lots().isEmpty()) {
            return "오늘까지 유통기한이 끝나거나 지난 상품은 없어요.";
        }
        return "유통기한이 24시간 안에 끝나거나 지난 상품이에요. " + summary.lots().stream()
                .map(lot -> "%s %d개(%s까지%s)".formatted(lot.name(), lot.quantity(), EXPIRY.format(lot.expiresAt()),
                        "EXPIRED".equals(lot.status()) ? ", 기한 지남" : ""))
                .collect(Collectors.joining(", ")) + ".";
    }

    /** 이번 달에 팔렸고 남은 수량이 안전재고 이하인 상품을 이번 달 판매가 많은 순으로 */
    private static String reorder(StoreSummary summary) {
        List<StoreSummary.ProductSummary> picks = summary.products().stream()
                .filter(p -> p.soldThisMonth() > 0 && p.available() <= p.safetyStock())
                .sorted(Comparator.comparingInt(StoreSummary.ProductSummary::soldThisMonth).reversed())
                .limit(TOP)
                .toList();
        if (picks.isEmpty()) {
            return "이번 달 판매 기준으로 지금 더 발주할 상품은 없어요.";
        }
        return "이번 달에 팔렸는데 남은 수량이 안전재고 이하인 상품이에요. " + picks.stream()
                .map(p -> "%s(이번 달 %d개 판매, 남은 %d개, 안전재고 %d개)"
                        .formatted(p.name(), p.soldThisMonth(), p.available(), p.safetyStock()))
                .collect(Collectors.joining(", ")) + ".";
    }

    /** 남은 수량이 안전재고보다 적거나, 최근 7일 하루 평균 판매로 2일 안에 떨어질 상품 */
    private static String lowStock(StoreSummary summary) {
        List<StoreSummary.ProductSummary> picks = summary.products().stream()
                .filter(p -> p.available() < p.safetyStock() || daysLeft(p) <= 2)
                .sorted(Comparator.comparingInt(StoreSummary.ProductSummary::available))
                .limit(TOP)
                .toList();
        if (picks.isEmpty()) {
            return "지금 부족하거나 곧 떨어질 상품은 없어요.";
        }
        return "재고가 부족하거나 곧 떨어질 상품이에요. " + picks.stream()
                .map(p -> "%s(남은 %d개, 안전재고 %d개)".formatted(p.name(), p.available(), p.safetyStock()))
                .collect(Collectors.joining(", ")) + ".";
    }

    /** 최근 7일 하루 평균 판매로 남은 일수. 판매가 없으면 아주 큰 값 */
    static double daysLeft(StoreSummary.ProductSummary p) {
        List<Integer> daily = p.dailySales14d();
        if (daily.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double average = daily.subList(Math.max(0, daily.size() - 7), daily.size()).stream()
                .mapToInt(Integer::intValue).sum() / 7.0;
        return average == 0 ? Double.MAX_VALUE : p.available() / average;
    }

    /** 시간대마다 가장 많이 팔린 상품 */
    private static String byHour(StoreSummary summary) {
        Map<String, StoreSummary.ProductSummary> best = new HashMap<>();
        Map<String, Integer> bestQuantity = new HashMap<>();
        for (StoreSummary.ProductSummary p : summary.products()) {
            p.salesByHour7d().forEach((bucket, quantity) -> {
                if (quantity > bestQuantity.getOrDefault(bucket, 0)) {
                    bestQuantity.put(bucket, quantity);
                    best.put(bucket, p);
                }
            });
        }
        if (best.isEmpty()) {
            return "최근 7일 판매 기록이 없어요.";
        }
        return "최근 7일 시간대별로 가장 많이 팔린 상품이에요. " + best.keySet().stream()
                .sorted()
                .map(bucket -> "%s시 %s %d개".formatted(bucket, best.get(bucket).name(), bestQuantity.get(bucket)))
                .collect(Collectors.joining(", ")) + ".";
    }

    /** 최근 30일 폐기가 있는 상품을 폐기 많은 순으로 */
    private static String disposed(StoreSummary summary) {
        List<StoreSummary.ProductSummary> picks = summary.products().stream()
                .filter(p -> p.disposed30d() > 0)
                .sorted(Comparator.comparingInt(StoreSummary.ProductSummary::disposed30d).reversed())
                .limit(TOP)
                .toList();
        if (picks.isEmpty()) {
            return "최근 30일 동안 폐기한 상품은 없어요.";
        }
        return "최근 30일 폐기가 많은 상품이에요. " + picks.stream()
                .map(p -> "%s(폐기 %d개, 입고 %d개)".formatted(p.name(), p.disposed30d(), p.inbound30d()))
                .collect(Collectors.joining(", ")) + ".";
    }
}
