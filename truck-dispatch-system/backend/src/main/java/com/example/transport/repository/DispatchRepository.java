package com.example.transport.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 调度记录持久层（dispatch 表）。
 * 手动派车（前端"智能匹配"）和仿真自动派车共用同一套写入逻辑。
 */
@Repository
public class DispatchRepository {

    private final JdbcTemplate jdbc;

    public DispatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条调度记录
     *
     * @param orderId      订单 ID
     * @param vehicleId    车辆 ID
     * @param driverId     司机 ID（车辆未绑司机时允许为空）
     * @param routeId      执行路线 ID
     * @param dispatchTime 调度时间
     * @param eta          预计到达时间
     */
    public void insert(int orderId, int vehicleId, Integer driverId, int routeId,
                       LocalDateTime dispatchTime, LocalDateTime eta) {
        String sql = "INSERT INTO dispatch (order_id, vehicle_id, driver_id, route_id, dispatch_time, "
                + "estimated_arrival, status) VALUES (?, ?, ?, ?, ?, ?, 'DISPATCHED')";
        jdbc.update(sql, orderId, vehicleId, driverId, routeId,
                Timestamp.valueOf(dispatchTime), eta == null ? null : Timestamp.valueOf(eta));
    }

    /** 车辆装载完成正式发车：调度记录从 DISPATCHED 变为 IN_TRANSIT */
    public void markInTransit(int orderId) {
        jdbc.update("UPDATE dispatch SET status = 'IN_TRANSIT' WHERE order_id = ? AND status = 'DISPATCHED'",
                orderId);
    }

    /** 订单送达后，把对应调度记录置为已完成并回填实际到达时间 */
    public void completeByOrder(int orderId, LocalDateTime arrivalTime) {
        jdbc.update("UPDATE dispatch SET status = 'COMPLETED', actual_arrival = ? WHERE order_id = ?",
                Timestamp.valueOf(arrivalTime), orderId);
    }

    /**
     * 复位场景：把所有未完成的调度单（已派车/在途）置为已取消。
     * 非正常停止仿真时车辆可能停在中途，仅把车辆置空闲会留下"幽灵任务"，
     * 导致该车以后永远无法再派单，故复位时必须一并清理。
     */
    public int cancelOpenTasks() {
        return jdbc.update("UPDATE dispatch SET status = 'CANCELLED', actual_arrival = NOW() "
                + "WHERE status IN ('DISPATCHED','IN_TRANSIT')");
    }

    /**
     * 查询全部"未完成"的调度任务（DISPATCHED 已派车未发车 / IN_TRANSIT 在途）。
     * 仿真启动时据此让车辆直接接续任务，而不是把它们漏掉或与新派单冲突。
     */
    public record DispatchedTask(int vehicleId, int orderId, String orderNo, String dispatchStatus,
                                 int originPoiId, int destPoiId, int routeId) {
    }

    /** 查询未完成调度任务（JOIN 订单拿装卸点；dispatchStatus 决定接续为装货态还是在途态） */
    public java.util.List<DispatchedTask> findDispatchedTasks() {
        String sql = "SELECT dis.vehicle_id, co.order_id, co.order_no, dis.status, co.origin_poi_id, "
                + "co.destination_poi_id, dis.route_id FROM dispatch dis "
                + "JOIN cargo_order co ON dis.order_id = co.order_id "
                + "WHERE dis.status IN ('DISPATCHED','IN_TRANSIT')";
        return jdbc.query(sql, (rs, n) -> new DispatchedTask(
                rs.getInt("vehicle_id"),
                rs.getInt("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getInt("origin_poi_id"),
                rs.getInt("destination_poi_id"),
                (Integer) rs.getObject("route_id") == null ? 0 : rs.getInt("route_id")));
    }

    /**
     * 查询已有"未完成调度"的车辆 ID 集合。
     * 这些车辆已被派单，智能匹配与仿真自动派车时必须排除，避免一车多单。
     */
    public Set<Integer> findBusyVehicleIds() {
        return Set.copyOf(jdbc.queryForList(
                "SELECT DISTINCT vehicle_id FROM dispatch WHERE status IN ('DISPATCHED','IN_TRANSIT')",
                Integer.class));
    }

    /** 订单失效（软删除）时级联取消该订单的未完成调度记录，返回受影响行数 */
    public int cancelByOrder(int orderId) {
        return jdbc.update("UPDATE dispatch SET status = 'CANCELLED', actual_arrival = NOW() "
                + "WHERE order_id = ? AND status IN ('DISPATCHED','IN_TRANSIT')", orderId);
    }

    /**
     * 查询占用某订单的车辆 ID（仅未完成的调度任务），没有则返回 null。
     * 撤单/改派时用它定位需要释放的车辆。
     */
    public Integer findActiveVehicleId(int orderId) {
        var list = jdbc.queryForList("SELECT vehicle_id FROM dispatch WHERE order_id = ? "
                + "AND status IN ('DISPATCHED','IN_TRANSIT') LIMIT 1", Integer.class, orderId);
        return list.isEmpty() ? null : list.get(0);
    }
}
