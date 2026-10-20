package com.example.transport.service;

import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.RouteInfo;
import com.example.transport.repository.DispatchRepository;
import com.example.transport.repository.OrderRepository;
import com.example.transport.repository.PoiRepository;
import com.example.transport.repository.VehicleRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 调度派车服务：把一张 PENDING 订单正式派给某辆车。
 * 人工派车（前端按钮）与仿真自动派车共用本服务，保证业务规则唯一。
 * 涉及 dispatch 写入、订单状态更新、路线回写，使用事务保证多表一致。
 */
@Service
public class DispatchService {

    private final JdbcTemplate jdbcTemplate;
    private final OrderRepository orderRepository;
    private final VehicleRepository vehicleRepository;
    private final PoiRepository poiRepository;
    private final DispatchRepository dispatchRepository;
    private final GisService gisService;

    public DispatchService(JdbcTemplate jdbcTemplate, OrderRepository orderRepository,
                           VehicleRepository vehicleRepository, PoiRepository poiRepository,
                           DispatchRepository dispatchRepository, GisService gisService) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
        this.vehicleRepository = vehicleRepository;
        this.poiRepository = poiRepository;
        this.dispatchRepository = dispatchRepository;
        this.gisService = gisService;
    }

    /**
     * 执行派车
     *
     * @param orderId   订单 ID
     * @param vehicleId 车辆 ID
     * @return 本次派单采用的路线（含距离/耗时）
     * @throws IllegalArgumentException 订单/车辆不存在、状态不允许、一车多单时抛出
     */
    @Transactional
    public RouteInfo assign(int orderId, int vehicleId) {
        // 1) 校验订单存在
        OrderRepository.PendingOrder order = orderRepository.findOrderById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在：" + orderId);
        }
        // 2) 只有 PENDING 订单允许派车（防止重复派单）
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM cargo_order WHERE order_id = ?", String.class, orderId);
        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException("订单 " + order.orderNo() + " 当前为 " + status + " 状态，无法派车");
        }

        // 3) 校验车辆存在且没有未完成调度
        VehicleRepository.SimVehicle vehicle = vehicleRepository.findSimVehicles().stream()
                .filter(v -> v.vehicleId() == vehicleId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("车辆不存在或已停用"));
        Set<Integer> busy = dispatchRepository.findBusyVehicleIds();
        if (busy.contains(vehicleId)) {
            throw new IllegalArgumentException("车辆 " + vehicle.plate() + " 已有未完成的调度任务");
        }

        // 3.5) 运力兜底校验：整单总量必须装得下。
        // 候选列表筛选只是前端展示层，本接口可能被直接调用，故执行派单前必须再校验一次，
        // 否则会出现"640 吨货物派给 8 吨车"这类物理上不可能的调度。
        if (vehicle.maxLoad() < order.totalWeight() || vehicle.maxVolume() < order.totalVolume()) {
            throw new IllegalArgumentException("车辆 " + vehicle.plate() + " 载重 " + vehicle.maxLoad()
                    + "kg / 容积 " + vehicle.maxVolume() + "m³，装不下整单 "
                    + Math.round(order.totalWeight()) + "kg / " + Math.round(order.totalVolume()) + "m³");
        }

        // 4) GIS 解析/补算路线
        PoiSummary origin = poiRepository.findById(order.originPoiId());
        PoiSummary dest = poiRepository.findById(order.destPoiId());
        RouteInfo route = gisService.resolveRoute(origin, dest);

        // 5) 写调度记录（预计到达 = 现在 + 路线预计耗时）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime eta = now.plusMinutes(route.estimatedTime());
        try {
            dispatchRepository.insert(orderId, vehicleId, vehicle.driverId(), route.routeId(), now, eta);
        } catch (DuplicateKeyException e) {
            // 并发兜底：uk_dispatch_active_vehicle 唯一索引拒绝"同一辆车同时两条未完成任务"。
            // 上面的忙碌校验是"先查后写"，并非原子；并发时由数据库在这里拦下，转成业务提示。
            throw new IllegalArgumentException("车辆 " + vehicle.plate()
                    + " 刚被其它调度任务占用（并发冲突），请刷新后重试");
        }

        // 6) 订单流转为 ASSIGNED 并回写路线
        orderRepository.updateStatus(orderId, "ASSIGNED", null);
        orderRepository.updateRoute(orderId, route.routeId());
        return route;
    }

    /**
     * 撤单：取消该订单未完成的调度任务、释放车辆，并把订单放回待处理池。
     * 撤单后订单状态回到 PENDING，可重新走"智能匹配"派给别的车，即完成改派。
     *
     * @return 结果说明（含被释放的调度单数量）
     * @throws IllegalArgumentException 订单不存在或当前状态不可撤单
     */
    @Transactional
    public String cancelDispatch(int orderId) {
        String status = orderRepository.findStatusById(orderId);
        if (status == null) {
            throw new IllegalArgumentException("订单不存在：" + orderId);
        }
        if (!"ASSIGNED".equals(status) && !"TRANSPORTING".equals(status)) {
            throw new IllegalArgumentException("订单当前为 " + status + " 状态，只有已分配/运输中的订单可撤单");
        }
        // 找出占用该订单的车辆，撤单后需将其释放回空闲
        Integer vehicleId = dispatchRepository.findActiveVehicleId(orderId);
        int cancelled = dispatchRepository.cancelByOrder(orderId);
        if (vehicleId != null) {
            vehicleRepository.updateCurrentStatus(vehicleId, "IDLE");
        }
        // 订单回到待处理池，清空实际送达时间
        orderRepository.updateStatus(orderId, "PENDING", null);
        return "撤单成功：取消 " + cancelled + " 条调度任务"
                + (vehicleId == null ? "" : "，车辆 " + vehicleId + " 已释放为空闲");
    }
}
