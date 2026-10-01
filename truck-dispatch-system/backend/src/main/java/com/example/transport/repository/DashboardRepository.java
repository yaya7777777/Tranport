package com.example.transport.repository;

import com.example.transport.dto.DashboardStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * 运行总览持久层：聚合 vehicle / cargo_order / dispatch / poi / traffic_anomaly 的统计数据。
 * 采用"一次 GROUP BY 查询拿到全部状态分布"的方式，避免 6 次 COUNT 往返数据库。
 */
@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbc;

    /** 构造注入 JdbcTemplate（Spring Boot 已根据 application.properties 自动装配数据源） */
    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询总览统计
     *
     * @return 填充完成的统计对象
     */
    public DashboardStats stats() {
        // 1) 一次查询拿到启用车辆在 6 种状态上的数量分布
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("SELECT current_status AS s, COUNT(*) AS c FROM vehicle WHERE status=1 GROUP BY current_status",
                rs -> {
                    byStatus.put(rs.getString("s"), rs.getLong("c"));
                });

        // 2) 其余指标各自 COUNT
        long vehicleCount = count("SELECT COUNT(*) FROM vehicle WHERE status=1");
        long pendingOrderCount = count("SELECT COUNT(*) FROM cargo_order WHERE status='PENDING'");
        long activeDispatchCount = count("SELECT COUNT(*) FROM dispatch WHERE status IN ('DISPATCHED','IN_TRANSIT')");
        long poiCount = count("SELECT COUNT(*) FROM poi");
        long activeAnomalyCount = count("SELECT COUNT(*) FROM traffic_anomaly WHERE end_time IS NULL");

        return new DashboardStats(
                vehicleCount,
                byStatus.getOrDefault("IDLE", 0L),
                byStatus.getOrDefault("LOADING", 0L),
                byStatus.getOrDefault("TRANSPORT", 0L),
                byStatus.getOrDefault("UNLOADING", 0L),
                byStatus.getOrDefault("REFUEL", 0L),
                byStatus.getOrDefault("MAINTAIN", 0L),
                pendingOrderCount,
                activeDispatchCount,
                poiCount,
                activeAnomalyCount);
    }

    /** 执行返回单个 COUNT 值的简单工具方法 */
    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
