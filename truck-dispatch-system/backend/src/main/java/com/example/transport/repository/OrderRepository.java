package com.example.transport.repository;

import com.example.transport.dto.OrderSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运输订单持久层（cargo_order 表）。
 * 除列表查询外，还为智能调度与仿真提供：待处理订单查询、订单状态流转、仿真订单自动写入。
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 待派单/在途订单（含货物重量体积、起终点 ID 与名称），匹配算法的输入对象。
     */
    public record PendingOrder(int orderId, String orderNo, int cargoId, String cargoName, int categoryId,
                               double weight, double volume, int originPoiId, int destPoiId,
                               Integer routeId, int priority, String originName, String destName) {
    }

    /** 订单列表页：订单 + 货物 + POI + 路线 + 调度 + 司机 + 车型 的多表联合查询 */
    public List<OrderSummary> findAll() {
        String sql = "SELECT co.order_id,co.order_no,c.cargo_name,cc.category_name cargo_type,co.quantity,"
                + "p1.poi_name origin,p2.poi_name destination,r.distance,r.estimated_time,d.name driver_name,v.plate_number,"
                + "vt.type_name vehicle_type,co.status order_status,dis.status dispatch_status FROM cargo_order co "
                + "JOIN cargo c ON co.cargo_id=c.cargo_id JOIN cargo_category cc ON c.category_id=cc.category_id "
                + "JOIN poi p1 ON co.origin_poi_id=p1.poi_id JOIN poi p2 ON co.destination_poi_id=p2.poi_id "
                + "LEFT JOIN route r ON co.route_id=r.route_id LEFT JOIN dispatch dis ON co.order_id=dis.order_id "
                + "LEFT JOIN driver d ON dis.driver_id=d.driver_id LEFT JOIN vehicle v ON dis.vehicle_id=v.vehicle_id "
                + "LEFT JOIN vehicle_type vt ON v.type_id=vt.type_id ORDER BY co.order_id DESC";
        return jdbc.query(sql, (rs, n) -> new OrderSummary(rs.getInt("order_id"), rs.getString("order_no"),
                rs.getString("cargo_name"), rs.getString("cargo_type"), rs.getObject("quantity", Double.class),
                rs.getString("origin"), rs.getString("destination"), rs.getObject("distance", Double.class),
                rs.getObject("estimated_time", Integer.class), rs.getString("driver_name"), rs.getString("plate_number"),
                rs.getString("vehicle_type"), rs.getString("order_status"), rs.getString("dispatch_status")));
    }

    /** 查询全部待处理（PENDING）订单，按优先级、下单时间排序 */
    public List<PendingOrder> findPendingOrders() {
        String sql = "SELECT co.order_id, co.order_no, co.cargo_id, c.cargo_name, c.category_id, "
                + "c.weight, c.volume, co.origin_poi_id, co.destination_poi_id, co.route_id, co.priority, "
                + "p1.poi_name AS origin_name, p2.poi_name AS dest_name "
                + "FROM cargo_order co "
                + "JOIN cargo c ON co.cargo_id = c.cargo_id "
                + "JOIN poi p1 ON co.origin_poi_id = p1.poi_id "
                + "JOIN poi p2 ON co.destination_poi_id = p2.poi_id "
                + "WHERE co.status = 'PENDING' ORDER BY co.priority ASC, co.order_id ASC";
        return jdbc.query(sql, (rs, n) -> new PendingOrder(
                rs.getInt("order_id"),
                rs.getString("order_no"),
                rs.getInt("cargo_id"),
                rs.getString("cargo_name"),
                rs.getInt("category_id"),
                rs.getDouble("weight"),
                rs.getDouble("volume"),
                rs.getInt("origin_poi_id"),
                rs.getInt("destination_poi_id"), // 结果集列名与表字段一致（SQL 中未起别名）
                (Integer) rs.getObject("route_id"),
                rs.getInt("priority"),
                rs.getString("origin_name"),
                rs.getString("dest_name")));
    }

    /** 按主键查订单详情（含匹配所需的重量体积/起终点），不存在返回 null。任意状态均可查到。 */
    public PendingOrder findOrderById(int orderId) {
        String sql = "SELECT co.order_id, co.order_no, co.cargo_id, c.cargo_name, c.category_id, "
                + "c.weight, c.volume, co.origin_poi_id, co.destination_poi_id, co.route_id, co.priority, "
                + "p1.poi_name AS origin_name, p2.poi_name AS dest_name "
                + "FROM cargo_order co "
                + "JOIN cargo c ON co.cargo_id = c.cargo_id "
                + "JOIN poi p1 ON co.origin_poi_id = p1.poi_id "
                + "JOIN poi p2 ON co.destination_poi_id = p2.poi_id "
                + "WHERE co.order_id = ?";
        List<PendingOrder> any = jdbc.query(sql, (rs, n) -> new PendingOrder(
                rs.getInt("order_id"), rs.getString("order_no"), rs.getInt("cargo_id"),
                rs.getString("cargo_name"), rs.getInt("category_id"), rs.getDouble("weight"),
                rs.getDouble("volume"), rs.getInt("origin_poi_id"), rs.getInt("destination_poi_id"),
                (Integer) rs.getObject("route_id"), rs.getInt("priority"),
                rs.getString("origin_name"), rs.getString("dest_name")), orderId);
        return any.isEmpty() ? null : any.get(0);
    }

    /** 待处理订单数 */
    public long countPending() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM cargo_order WHERE status='PENDING'", Long.class);
        return value == null ? 0L : value;
    }

    /**
     * 仿真按厂仓关系自动生成货源订单
     *
     * @return 新订单 ID
     */
    public int insertGeneratedOrder(String orderNo, int cargoId, double quantity, int originPoiId, int destPoiId,
                                    Integer routeId, LocalDateTime orderTime, LocalDateTime requiredTime,
                                    int priority, String remark) {
        String sql = "INSERT INTO cargo_order (order_no, cargo_id, quantity, origin_poi_id, destination_poi_id, "
                + "route_id, order_time, required_delivery_time, priority, status, remark) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)";
        jdbc.update(sql, orderNo, cargoId, quantity, originPoiId, destPoiId, routeId,
                Timestamp.valueOf(orderTime), Timestamp.valueOf(requiredTime), priority, remark);
        // 订单号有唯一索引，直接用它反查自增 ID，避免再处理 KeyHolder
        return jdbc.queryForObject("SELECT order_id FROM cargo_order WHERE order_no = ?", Integer.class, orderNo);
    }

    /**
     * 订单状态流转（PENDING->ASSIGNED->TRANSPORTING->DELIVERED 等）。
     *
     * @param actualDelivery 实际送达时间，非空时一并回填
     */
    public void updateStatus(int orderId, String status, LocalDateTime actualDelivery) {
        jdbc.update("UPDATE cargo_order SET status = ?, actual_delivery_time = ? WHERE order_id = ?",
                status, actualDelivery == null ? null : Timestamp.valueOf(actualDelivery), orderId);
    }

    /** 派车成功后把选定路线回写到订单上 */
    public void updateRoute(int orderId, int routeId) {
        jdbc.update("UPDATE cargo_order SET route_id = ? WHERE order_id = ?", routeId, orderId);
    }

    /**
     * 复位时把未完结订单（ASSIGNED 已派车 / TRANSPORTING 运输中）重新放回待处理池。
     * 与 {@code DispatchRepository.cancelOpenTasks} 配套使用，
     * 避免非正常停止仿真后留下"车已空闲、单却锁死"的脏数据。
     */
    public int reopenOpenOrders() {
        return jdbc.update("UPDATE cargo_order SET status = 'PENDING' WHERE status IN ('ASSIGNED','TRANSPORTING')");
    }
}
