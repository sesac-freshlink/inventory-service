package com.freshlink.inventory.domain.clearance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 점포·분류별 마감세일 설정(clearance_setting). 값이 단순해 선착순·안전재고처럼 JPA 엔티티 없이 SQL로 다룬다.
 * 행이 없는 분류는 {@link ClearanceSetting#DEFAULT}다.
 */
@Repository
public class ClearanceSettingRepository {

    private static final String COLUMNS = "category, enabled, discount_percent, sale_start_hour, sale_end_hour";

    private final JdbcTemplate jdbc;

    public ClearanceSettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 점포·분류의 설정. 정한 적이 없으면 기본값 */
    public ClearanceSetting find(Long storeId, String category) {
        List<ClearanceSetting> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM clearance_setting WHERE store_id = ? AND category = ?",
                (rs, i) -> toSetting(rs), storeId, category);
        return rows.isEmpty() ? ClearanceSetting.DEFAULT : rows.getFirst();
    }

    /** 점포가 정한 설정만(분류 → 설정). 없는 분류는 부르는 쪽이 기본값으로 본다 */
    public Map<String, ClearanceSetting> findAll(Long storeId) {
        Map<String, ClearanceSetting> settings = new HashMap<>();
        jdbc.query("SELECT " + COLUMNS + " FROM clearance_setting WHERE store_id = ?",
                rs -> {
                    settings.put(rs.getString("category"), toSetting(rs));
                }, storeId);
        return settings;
    }

    /** 한 분류를 정한 점포들의 설정(점포 ID → 설정). 주변 점포 재고가 상품 하나의 점포별 수량을 셀 때 쓴다 */
    public Map<Long, ClearanceSetting> findByCategory(String category) {
        Map<Long, ClearanceSetting> settings = new HashMap<>();
        jdbc.query("SELECT store_id, " + COLUMNS + " FROM clearance_setting WHERE category = ?",
                rs -> {
                    settings.put(rs.getLong("store_id"), toSetting(rs));
                }, category);
        return settings;
    }

    /** 안전재고·선착순과 같은 이유(동시에 처음 저장해도 유니크 충돌이 없게)로 한 문장으로 한다 */
    public void upsert(Long storeId, String category, ClearanceSetting setting, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO clearance_setting
                    (store_id, category, enabled, discount_percent, sale_start_hour, sale_end_hour, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE enabled = new.enabled, discount_percent = new.discount_percent,
                    sale_start_hour = new.sale_start_hour, sale_end_hour = new.sale_end_hour, updated_at = new.updated_at
                """, storeId, category, setting.enabled(), setting.discountPercent(),
                setting.saleStartHour(), setting.saleEndHour(), now, now);
    }

    private static ClearanceSetting toSetting(ResultSet rs) throws SQLException {
        return new ClearanceSetting(rs.getBoolean("enabled"), rs.getInt("discount_percent"),
                rs.getInt("sale_start_hour"), rs.getInt("sale_end_hour"));
    }
}
