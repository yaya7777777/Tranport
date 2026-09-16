package com.example.transport.repository;

import com.example.transport.dto.GpsPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * GPS 定位数据持久层（gps_data 表）。
 * 数据有两个来源：
 * 1) tools/modbus_collector.py 读取北斗模块后通过 HTTP 上报（sim_id 为空，表示真实数据）；
 * 2) 仿真引擎每个节拍为行驶车辆写入的轨迹（sim_id 非空，表示模拟数据）。
 */
@Repository
public class GpsRepository {

    /** 统一的时间字符串格式（Timestamp.toString() 会带 ".0" 纳秒后缀，直接格式化更整洁；使用全限定类名，避免 IDE 自动整理导入时被误删） */
    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public GpsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条定位数据
     *
     * @param vehicleId 车辆 ID
     * @param lng       经度
     * @param lat       纬度
     * @param speed     速度 km/h
     * @param heading   方向角
     * @param simId     仿真 ID（真实设备上报传 null）
     * @param time      定位时间
     * @return 新生成的 gps_id
     */
    public long insert(int vehicleId, double lng, double lat, Double speed, Double heading,
                       Integer simId, LocalDateTime time) {
        String sql = "INSERT INTO gps_data (vehicle_id, longitude, latitude, speed, heading, sim_id, timestamp) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, vehicleId);
            ps.setDouble(2, lng);
            ps.setDouble(3, lat);
            ps.setObject(4, speed);
            ps.setObject(5, heading);
            if (simId == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, simId);
            }
            ps.setTimestamp(7, Timestamp.valueOf(time));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    /**
     * 最新定位数据（跨所有车辆），按 gps_id 倒序。
     * 表达式 sim_id IS NOT NULL 用于区分仿真数据与真实设备数据。
     */
    public List<GpsPoint> findLatest(int limit) {
        String sql = "SELECT g.gps_id, g.vehicle_id, v.plate_number, g.longitude, g.latitude, "
                + "g.speed, g.heading, (g.sim_id IS NOT NULL) AS simulated, g.timestamp "
                + "FROM gps_data g JOIN vehicle v ON g.vehicle_id = v.vehicle_id "
                + "ORDER BY g.gps_id DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> mapPoint(rs), limit);
    }

    /** 某辆车的历史轨迹 */
    public List<GpsPoint> findHistory(int vehicleId, int limit) {
        String sql = "SELECT g.gps_id, g.vehicle_id, v.plate_number, g.longitude, g.latitude, "
                + "g.speed, g.heading, (g.sim_id IS NOT NULL) AS simulated, g.timestamp "
                + "FROM gps_data g JOIN vehicle v ON g.vehicle_id = v.vehicle_id "
                + "WHERE g.vehicle_id = ? ORDER BY g.gps_id DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> mapPoint(rs), vehicleId, limit);
    }

    /** ResultSet -> GpsPoint 统一映射 */
    private GpsPoint mapPoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GpsPoint(
                rs.getLong("gps_id"),
                rs.getInt("vehicle_id"),
                rs.getString("plate_number"),
                rs.getObject("longitude", Double.class),
                rs.getObject("latitude", Double.class),
                rs.getObject("speed", Double.class),
                rs.getObject("heading", Double.class),
                rs.getBoolean("simulated"),
                rs.getTimestamp("timestamp") == null ? null
                        : rs.getTimestamp("timestamp").toLocalDateTime().format(TS_FMT));
    }
}
