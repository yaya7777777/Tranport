package com.example.transport.repository;

import com.example.transport.dto.StatusLogView;
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
 * 仿真运行持久层：仿真记录（simulation_record）与车辆状态变更日志（vehicle_status_log）。
 */
@Repository
public class SimulationRepository {

    /** 时间统一输出为 yyyy-MM-dd HH:mm:ss（避免 Timestamp.toString() 的 ".0" 后缀；全限定类名，避免 IDE 整理导入时误删） */
    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public SimulationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建一条仿真记录（仿真启动时调用）
     *
     * @param name         仿真名称
     * @param type         仿真类型 SINGLE/MULTI/FULL
     * @param vehicleCount 参与车辆数
     * @param configJson   配置参数 JSON（节拍间隔、异常概率等）
     * @return 仿真 ID（sim_id）
     */
    public int createRecord(String name, String type, int vehicleCount, String configJson) {
        String sql = "INSERT INTO simulation_record (sim_name, sim_type, start_time, vehicle_count, status, config_json) "
                + "VALUES (?, ?, NOW(), ?, 'RUNNING', ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, type);
            ps.setInt(3, vehicleCount);
            ps.setString(4, configJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.intValue();
    }

    /** 结束仿真：回填结束时间、处理订单数并置状态为 COMPLETED */
    public void finishRecord(int simId, int processedOrders) {
        jdbc.update("UPDATE simulation_record SET end_time = NOW(), status = 'COMPLETED', "
                        + "order_count = ? WHERE sim_id = ?",
                processedOrders, simId);
    }

    /**
     * 写入一条车辆状态变更日志（严格记录"原状态 -> 新状态"的每次跳转）
     *
     * @param vehicleId    车辆 ID
     * @param fromStatusId 原状态 ID（首次进入可空）
     * @param toStatusId   新状态 ID
     * @param poiId        发生站点 ID（路上可空）
     * @param time         变更时间
     * @param remark       备注（订单号、异常说明等）
     */
    public void insertStatusLog(int vehicleId, Integer fromStatusId, int toStatusId, Integer poiId,
                                LocalDateTime time, String remark) {
        String sql = "INSERT INTO vehicle_status_log (vehicle_id, from_status_id, to_status_id, poi_id, change_time, remark) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setInt(1, vehicleId);
            if (fromStatusId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, fromStatusId);
            }
            ps.setInt(3, toStatusId);
            if (poiId == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, poiId);
            }
            ps.setTimestamp(5, Timestamp.valueOf(time));
            ps.setString(6, remark);
            return ps;
        });
    }

    /** 最近的状态变更日志（JOIN 出车牌号、状态编码与站点名，供前端时间线展示） */
    public List<StatusLogView> findRecentLogs(int limit) {
        String sql = "SELECT l.log_id, l.vehicle_id, v.plate_number, "
                + "s1.status_code AS from_code, s2.status_code AS to_code, "
                + "p.poi_name AS poi_name, l.change_time, l.remark "
                + "FROM vehicle_status_log l "
                + "JOIN vehicle v ON l.vehicle_id = v.vehicle_id "
                + "JOIN vehicle_status s2 ON l.to_status_id = s2.status_id "
                + "LEFT JOIN vehicle_status s1 ON l.from_status_id = s1.status_id "
                + "LEFT JOIN poi p ON l.poi_id = p.poi_id "
                + "ORDER BY l.log_id DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> new StatusLogView(
                rs.getLong("log_id"),
                rs.getInt("vehicle_id"),
                rs.getString("plate_number"),
                rs.getString("from_code"),
                rs.getString("to_code"),
                rs.getString("poi_name"),
                rs.getTimestamp("change_time").toLocalDateTime().format(TS_FMT),
                rs.getString("remark")), limit);
    }
}
