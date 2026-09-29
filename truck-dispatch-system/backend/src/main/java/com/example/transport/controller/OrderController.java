package com.example.transport.controller;

import com.example.transport.dto.ApiResult;
import com.example.transport.dto.OrderCreateRequest;
import com.example.transport.dto.OrderSummary;
import com.example.transport.dto.RevenueResult;
import com.example.transport.repository.OrderRepository;
import com.example.transport.service.FleetManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运输订单（需求）接口：查询全部订单、基于工厂增加需求、软删除失效需求、运输收益计算。
 * 智能匹配与派单见 {@link MatchController}、{@link DispatchController}。
 * 跨域由 {@link com.example.transport.config.CorsConfig} 统一处理。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository repository;
    private final FleetManagementService fleetManagementService;

    public OrderController(OrderRepository repository, FleetManagementService fleetManagementService) {
        this.repository = repository;
        this.fleetManagementService = fleetManagementService;
    }

    /** 查询全部订单（关联起终点 POI 与货物表，按创建时间倒序） */
    @GetMapping
    public List<OrderSummary> list() {
        return repository.findAll();
    }

    /** 新增需求表单的下拉数据：厂仓关系 + 货物 */
    @GetMapping("/options")
    public ApiResult<com.example.transport.dto.OrderOptions> options() {
        return ApiResult.ok("表单数据加载完成", fleetManagementService.orderOptions());
    }

    /**
     * 增加需求：基于工厂-仓库关系生成一条 PENDING 运输需求
     * 报文：{"factoryId":1,"warehouseId":1,"cargoId":1,"quantity":2,"priority":1,"remark":"加急补货"}
     */
    @PostMapping
    public ApiResult<Integer> create(@RequestBody OrderCreateRequest request) {
        int orderId = fleetManagementService.createOrder(request);
        return ApiResult.ok("需求创建成功，订单 ID：" + orderId, orderId);
    }

    /** 失效需求（软删除）：状态置 CANCELLED；已派车订单会级联取消调度并释放车辆 */
    @PostMapping("/{orderId}/disable")
    public ApiResult<Void> disable(@PathVariable int orderId) {
        return ApiResult.ok(fleetManagementService.disableOrder(orderId));
    }

    /** 计算运输收益：收入=数量×单价，成本=距离×综合成本单价（前期简单规则） */
    @GetMapping("/{orderId}/revenue")
    public ApiResult<RevenueResult> revenue(@PathVariable int orderId) {
        return ApiResult.ok("收益计算完成", fleetManagementService.calculateRevenue(orderId));
    }
}
