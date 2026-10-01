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
                               double quantity, double weight, double volume, int originPoiId, int destPoiId,
                               Integer routeId, int priority, String originName, String destName) {

        /** 整单总重量(kg)。cargo.weight 是单件重量，必须乘以订单数量 */
        public double totalWeight() {
            return weight * quantity;
        }

        /** 整单总体积(m³)。cargo.volume 是单件体积，必须乘以订单数量 */
        public double totalVolume() {
            return volume * quantity;
        }
    }

    /** 订单列表页：订单 + 货物 + POI + 路线 + 调度 + 司机 + 车型 的多表联合查询 */
    public List<OrderSummary> findAll() {
        return findAll(null, null, null, 0);
    }

    /** 订单列表分页查询的总条数（与 findAll 使用完全相同的筛选条件） */
    public long countOrders(String status, Integer priority) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cargo_order co "
                + whereClause(status, priority), Long.class, filterArgs(status, priority));
    }

    /**
     * 订单列表查询：支持按订单状态、优先级筛选与分页。
     *
     * @param status   订单状态（PENDING/ASSIGNED/...），null 或空 = 不筛选
     * @param priority 优先级（1高/2中/3低），null = 不筛选
     * @param limit    每页条数，null 或 &lt;=0 = 不分页
     * @param offset   起始偏移，从 0 开始
     */
    public List<OrderSummary> findAll(String status, Integer priority, Integer limit, int offset) {
        // 注意 dis 的 JOIN 条件必须带状态过滤：一个订单历史上可能有多条调度记录
        // （取消/完成 + 当前有效），不带过滤会让同一订单在列表里重复出现多行。
        String sql = "SELECT co.order_id,co.order_no,c.cargo_name,cc.category_name cargo_type,co.quantity,"
                + "p1.poi_name origin,p2.poi_name destination,r.distance,r.estimated_time,d.name driver_name,v.plate_number,"
                + "vt.type_name vehicle_type,co.status order_status,dis.status dispatch_status FROM cargo_order co "
                + "JOIN cargo c ON co.cargo_id=c.cargo_id JOIN cargo_category cc ON c.category_id=cc.category_id "
                + "JOIN poi p1 ON co.origin_poi_id=p1.poi_id JOIN poi p2 ON co.destination_poi_id=p2.poi_id "
                + "LEFT JOIN route r ON co.route_id=r.route_id "
                + "LEFT JOIN dispatch dis ON co.order_id=dis.order_id "
                + "AND dis.status IN ('DISPATCHED','IN_TRANSIT','COMPLETED') "
                + "LEFT JOIN driver d ON dis.driver_id=d.driver_id LEFT JOIN vehicle v ON dis.vehicle_id=v.vehicle_id "
                + "LEFT JOIN vehicle_type vt ON v.type_id=vt.type_id "
                + whereClause(status, priority)
                + " ORDER BY co.order_id DESC";
        // limit/offset 已由服务端 Integer 校验并钳位，非用户自由文本，直接拼入 SQL
        if (limit != null && limit > 0) {
            sql += " LIMIT " + limit + " OFFSET " + Math.max(0, offset);
        }
        return jdbc.query(sql, orderSummaryMapper(), filterArgs(status, priority));
    }

    /** 筛选条件：状态为空表示不限，其余为占位符参数 */
    private String whereClause(String status, Integer priority) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (status != null && !status.isBlank()) {
            sb.append(" AND co.status = ?");
        }
        if (priority != null) {
            sb.append(" AND co.priority = ?");
        }
        return sb.toString();
    }

    private Object[] filterArgs(String status, Integer priority) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            args.add(status);
        }
        if (priority != null) {
            args.add(priority);
        }
        return args.toArray();
    }

    /** OrderSummary 结果集映射（列表查询与分页查询共用） */
    private org.springframework.jdbc.core.RowMapper<OrderSummary> orderSummaryMapper() {
        return (rs, n) -> new OrderSummary(rs.getInt("order_id"), rs.getString("order_no"),
                rs.getString("cargo_name"), rs.getString("cargo_type"), rs.getObject("quantity", Double.class),
                rs.getString("origin"), rs.getString("destination"), rs.getObject("distance", Double.class),
                rs.getObject("estimated_time", Integer.class), rs.getString("driver_name"), rs.getString("plate_number"),
                rs.getString("vehicle_type"), rs.getString("order_status"), rs.getString("dispatch_status"));
    }

    /** 查询全部待处理（PENDING）订单，按优先级、下单时间排序 */
    public List<PendingOrder> findPendingOrders() {
        String sql = "SELECT co.order_id, co.order_no, co.cargo_id, c.cargo_name, c.category_id, "
                + "co.quantity, c.weight, c.volume, co.origin_poi_id, co.destination_poi_id, co.route_id, co.priority, "
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
                rs.getDouble("quantity"),
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
                + "co.quantity, c.weight, c.volume, co.origin_poi_id, co.destination_poi_id, co.route_id, co.priority, "
                + "p1.poi_name AS origin_name, p2.poi_name AS dest_name "
                + "FROM cargo_order co "
                + "JOIN cargo c ON co.cargo_id = c.cargo_id "
                + "JOIN poi p1 ON co.origin_poi_id = p1.poi_id "
                + "JOIN poi p2 ON co.destination_poi_id = p2.poi_id "
                + "WHERE co.order_id = ?";
        List<PendingOrder> any = jdbc.query(sql, (rs, n) -> new PendingOrder(
                rs.getInt("order_id"), rs.getString("order_no"), rs.getInt("cargo_id"),
                rs.getString("cargo_name"), rs.getInt("category_id"), rs.getDouble("quantity"),
                rs.getDouble("weight"), rs.getDouble("volume"), rs.getInt("origin_poi_id"), rs.getInt("destination_poi_id"),
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

    /** 查询订单状态编码，不存在返回 null（软删除前的状态校验用） */
    public String findStatusById(int orderId) {
        var list = jdbc.queryForList("SELECT status FROM cargo_order WHERE order_id = ?",
                String.class, orderId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 订单数量置为失效（软删除）：仅改状态不物理删行，历史轨迹仍可追溯 */
    public int softCancel(int orderId) {
        return jdbc.update("UPDATE cargo_order SET status = 'CANCELLED', remark = CONCAT(IFNULL(remark,''), '[已失效]') "
                + "WHERE order_id = ?", orderId);
    }

    /** 收益计算输入：数量、货物单价、路线距离与起终点（路线可能未回写，用 GIS 兜底估算） */
    public record RevenueInfo(double quantity, Double unitPrice, Double distance,
                              int originPoiId, int destPoiId, String orderNo, String cargoName) {
    }

    /** 查询订单收益计算所需字段，不存在返回 null */
    public RevenueInfo findRevenueInfo(int orderId) {
        String sql = "SELECT co.quantity, c.unit_price, r.distance, co.origin_poi_id, co.destination_poi_id, "
                + "co.order_no, c.cargo_name FROM cargo_order co "
                + "JOIN cargo c ON co.cargo_id = c.cargo_id LEFT JOIN route r ON co.route_id = r.route_id "
                + "WHERE co.order_id = ?";
        var list = jdbc.query(sql, (rs, n) -> new RevenueInfo(
                rs.getDouble("quantity"), rs.getObject("unit_price", Double.class),
                rs.getObject("distance", Double.class), rs.getInt("origin_poi_id"),
                rs.getInt("destination_poi_id"), rs.getString("order_no"), rs.getString("cargo_name")), orderId);
        return list.isEmpty() ? null : list.get(0);
    }
}
