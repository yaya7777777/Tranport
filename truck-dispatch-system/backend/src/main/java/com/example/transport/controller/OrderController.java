package com.example.transport.controller;

import com.example.transport.dto.ApiResult;
import com.example.transport.dto.OrderCreateRequest;
import com.example.transport.dto.OrderSummary;
import com.example.transport.dto.RevenueResult;
import com.example.transport.repository.OrderRepository;
import com.example.transport.service.FleetManagementService;
import com.example.transport.service.MatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final MatchService matchService;

    public OrderController(OrderRepository repository, FleetManagementService fleetManagementService,
                           MatchService matchService) {
        this.repository = repository;
        this.fleetManagementService = fleetManagementService;
        this.matchService = matchService;
    }

    /**
     * 某订单的候选车辆评分列表（与 {@code GET /api/match/vehicles} 同一算法）。
     * 供派单控制器做批量自动派单时复用，避免在控制器之间发 HTTP 调用。
     */
    public List<com.example.transport.dto.MatchResult> rankCandidates(int orderId) {
        OrderRepository.PendingOrder order = repository.findOrderById(orderId);
        return order == null ? List.of() : matchService.rankVehicles(order);
    }

    /**
     * 查询订单列表（关联起终点 POI 与货物表，按创建时间倒序）。
     * 全部参数可选，不传即与旧版行为一致（返回全部订单）。
     *
     * @param status   订单状态筛选（PENDING/ASSIGNED/TRANSPORTING/DELIVERED/CANCELLED）
     * @param priority 优先级筛选（1高/2中/3低）
     * @param limit    每页条数（最大 500）
     * @param offset   起始偏移，从 0 开始
     */
    @GetMapping
    public List<OrderSummary> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) Integer priority,
                                   @RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) Integer offset,
                                   jakarta.servlet.http.HttpServletResponse response) {
        // 分页时把总条数放进响应头，响应体保持原有的纯数组结构，老前端不受影响
        if (limit != null && limit > 0) {
            response.setHeader("X-Total-Count", String.valueOf(repository.countOrders(status, priority)));
        }
        int size = (limit == null || limit <= 0) ? 0 : Math.min(limit, 500);
        return repository.findAll(status, priority, size, offset == null ? 0 : Math.max(0, offset));
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
