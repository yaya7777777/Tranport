package com.example.transport.repository;

import com.example.transport.dto.CategoryCount;
import com.example.transport.dto.PoiSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * POI 站点持久层：站点查询、分类统计。
 * 数据来源除手工种子数据外，还可由 tools/poi_crawler.py 爬虫批量补充到 1000 个以上。
 */
@Repository
public class PoiRepository {

    private final JdbcTemplate jdbc;

    public PoiRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 复用的查询列与 JOIN（poi 表本身不含分类名，需 JOIN poi_category） */
    private static final String BASE_SELECT =
            "SELECT p.poi_id, p.poi_name, p.category_id, pc.category_name, "
                    + "p.longitude, p.latitude, p.address, p.capacity "
                    + "FROM poi p JOIN poi_category pc ON p.category_id = pc.category_id ";

    /** ResultSet -> PoiSummary 的统一映射 */
    private final RowMapper<PoiSummary> mapper = (rs, n) -> new PoiSummary(
            rs.getInt("poi_id"),
            rs.getString("poi_name"),
            rs.getInt("category_id"),
            rs.getString("category_name"),
            rs.getObject("longitude", Double.class),
            rs.getObject("latitude", Double.class),
            rs.getString("address"),
            rs.getObject("capacity", Double.class));

    /**
     * 按条件分页查询 POI
     *
     * @param categoryId 分类 ID（可空 = 不限分类）
     * @param keyword    名称/地址关键字（可空）
     * @param limit      最多返回条数（防止 POI 达 1000 个时前端一次拉取过多）
     */
    public List<PoiSummary> search(Integer categoryId, String keyword, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SELECT).append("WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (categoryId != null) {
            sql.append("AND p.category_id = ? ");
            args.add(categoryId);
        }
        if (StringUtils.hasText(keyword)) {
            sql.append("AND (p.poi_name LIKE ? OR p.address LIKE ?) ");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append("ORDER BY p.poi_id LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 5000)));
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    /** 按主键查单个站点，不存在返回 null */
    public PoiSummary findById(int poiId) {
        List<PoiSummary> list = jdbc.query(BASE_SELECT + "WHERE p.poi_id = ?", mapper, poiId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 各分类 POI 数量统计（用于评定"3 类/5 类分类"达标情况） */
    public List<CategoryCount> categoryCounts() {
        String sql = "SELECT pc.category_id, pc.category_name, COUNT(p.poi_id) AS cnt "
                + "FROM poi_category pc LEFT JOIN poi p ON p.category_id = pc.category_id "
                + "GROUP BY pc.category_id, pc.category_name ORDER BY pc.category_id";
        return jdbc.query(sql, (rs, n) -> new CategoryCount(
                rs.getInt("category_id"),
                rs.getString("category_name"),
                rs.getLong("cnt")));
    }

    /** POI 总数 */
    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM poi", Long.class);
        return value == null ? 0L : value;
    }
}
