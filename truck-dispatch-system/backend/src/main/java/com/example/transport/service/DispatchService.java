package com.example.transport.service;

import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.RouteInfo;
import com.example.transport.repository.DispatchRepository;
import com.example.transport.repository.OrderRepository;
import com.example.transport.repository.PoiRepository;
import com.example.transport.repository.VehicleRepository;
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

        // 4) GIS 解析/补算路线
        PoiSummary origin = poiRepository.findById(order.originPoiId());
        PoiSummary dest = poiRepository.findById(order.destPoiId());
        RouteInfo route = gisService.resolveRoute(origin, dest);

        // 5) 写调度记录（预计到达 = 现在 + 路线预计耗时）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime eta = now.plusMinutes(route.estimatedTime());
        dispatchRepository.insert(orderId, vehicleId, vehicle.driverId(), route.routeId(), now, eta);

        // 6) 订单流转为 ASSIGNED 并回写路线
        orderRepository.updateStatus(orderId, "ASSIGNED", null);
        orderRepository.updateRoute(orderId, route.routeId());
        return route;
    }
}
