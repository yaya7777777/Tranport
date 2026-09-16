package com.example.transport.repository;

import com.example.transport.dto.AnomalyView;
import com.example.transport.dto.RouteInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 路线与交通异常持久层（route / traffic_anomaly 两张表）。
 * 路线优先取库里人工维护的数据；不存在时由 GIS 服务计算后通过本类回写，越用越"聪明"。
 */
@Repository
public class RouteRepository {

    /** 时间统一输出为 yyyy-MM-dd HH:mm:ss（避免 Timestamp.toString() 的 ".0" 后缀） */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按起点-终点查路线（方向敏感：A->B 与 B->A 是不同记录），找不到返回 null */
    public RouteInfo findRoute(int originPoiId, int destPoiId) {
        String sql = "SELECT route_id, origin_poi_id, destination_poi_id, route_name, distance, "
                + "estimated_time, road_type, difficulty_level FROM route "
                + "WHERE origin_poi_id = ? AND destination_poi_id = ?";
        List<RouteInfo> list = jdbc.query(sql, (rs, n) -> new RouteInfo(
                rs.getInt("route_id"),
                rs.getInt("origin_poi_id"),
                rs.getInt("destination_poi_id"),
                rs.getString("route_name"),
                rs.getObject("distance", Double.class),
                rs.getObject("estimated_time", Integer.class),
                rs.getString("road_type"),
                rs.getInt("difficulty_level"),
                false), originPoiId, destPoiId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 回写一条由 GIS 服务计算得到的新路线
     *
     * @return 新路线的自增 ID
     */
    public int insertRoute(int originPoiId, int destPoiId, String routeName, double distance,
                           int estimatedTime, String roadType, int difficulty) {
        String sql = "INSERT INTO route (origin_poi_id, destination_poi_id, route_name, distance, "
                + "estimated_time, road_type, difficulty_level) VALUES (?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, originPoiId);
            ps.setInt(2, destPoiId);
            ps.setString(3, routeName);
            ps.setDouble(4, distance);
            ps.setInt(5, estimatedTime);
            ps.setString(6, roadType);
            ps.setInt(7, difficulty);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.intValue();
    }

    /** 新增一条交通异常，返回异常 ID（poiId 允许为空，表示异常点不靠近任何站点） */
    public int insertAnomaly(Integer routeId, Integer poiId, String type, int severity,
                             String description, LocalDateTime startTime) {
        String sql = "INSERT INTO traffic_anomaly (route_id, poi_id, anomaly_type, start_time, severity, description) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            if (routeId == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setInt(1, routeId);
            }
            if (poiId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, poiId);
            }
            ps.setString(3, type);
            ps.setTimestamp(4, Timestamp.valueOf(startTime));
            ps.setInt(5, severity);
            ps.setString(6, description);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.intValue();
    }

    /** 异常解除：回填结束时间 */
    public void resolveAnomaly(int anomalyId, LocalDateTime endTime) {
        jdbc.update("UPDATE traffic_anomaly SET end_time = ? WHERE anomaly_id = ?",
                Timestamp.valueOf(endTime), anomalyId);
    }

    /** 查询所有持续中的异常（LEFT JOIN poi 取坐标，便于直接画到地图上） */
    public List<AnomalyView> findActiveAnomalies() {
        String sql = "SELECT a.anomaly_id, a.anomaly_type, a.severity, a.description, a.start_time, "
                + "p.longitude, p.latitude FROM traffic_anomaly a "
                + "LEFT JOIN poi p ON a.poi_id = p.poi_id WHERE a.end_time IS NULL "
                + "ORDER BY a.anomaly_id DESC";
        return jdbc.query(sql, (rs, n) -> new AnomalyView(
                rs.getInt("anomaly_id"),
                rs.getString("anomaly_type"),
                rs.getInt("severity"),
                rs.getObject("longitude", Double.class),
                rs.getObject("latitude", Double.class),
                rs.getString("description"),
                // 内联全限定类名格式化，避免 Timestamp.toString() 自带的 ".0" 纳秒后缀
                rs.getTimestamp("start_time").toLocalDateTime()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                true));
    }
}
