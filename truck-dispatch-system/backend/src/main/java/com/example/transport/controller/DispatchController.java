package com.example.transport.controller;

import com.example.transport.dto.ApiResult;
import com.example.transport.dto.DispatchRequest;
import com.example.transport.dto.MatchResult;
import com.example.transport.dto.RouteInfo;
import com.example.transport.repository.OrderRepository;
import com.example.transport.service.DispatchService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动调度接口：前端在智能匹配结果上点"派车"时调用。
 * 另提供批量自动派单与撤单（改派）能力。
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final DispatchService dispatchService;
    private final OrderRepository orderRepository;
    private final OrderController orderController;

    public DispatchController(DispatchService dispatchService, OrderRepository orderRepository,
                              OrderController orderController) {
        this.dispatchService = dispatchService;
        this.orderRepository = orderRepository;
        this.orderController = orderController;
    }

    /**
     * 把订单派给指定车辆
     * 报文：{"orderId":1,"vehicleId":4}
     */
    @PostMapping("/assign")
    public ApiResult<RouteInfo> assign(@RequestBody DispatchRequest request) {
        try {
            RouteInfo route = dispatchService.assign(request.orderId(), request.vehicleId());
            return ApiResult.ok("派车成功，路线：" + route.routeName()
                    + "（" + route.distance() + "km / 约" + route.estimatedTime() + "分钟）", route);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(e.getMessage());
        }
    }

    /**
     * 一键自动派单：按优先级顺序，为每张待处理订单挑评分最高的空闲车并派单。
     * 单张订单失败（如无合适车辆）只跳过该单并记录原因，不影响其余订单。
     *
     * @param max 本批最多处理几张订单（默认 20，上限 100）
     */
    @PostMapping("/auto")
    public ApiResult<List<String>> autoAssign(@RequestParam(defaultValue = "20") int max) {
        int limit = Math.max(1, Math.min(max, 100));
        List<OrderRepository.PendingOrder> pending = orderRepository.findPendingOrders();
        List<String> details = new ArrayList<>();
        int success = 0;
        for (OrderRepository.PendingOrder order : pending) {
            if (success >= limit) {
                break;
            }
            List<MatchResult> candidates = orderController.rankCandidates(order.orderId());
            if (candidates.isEmpty()) {
                details.add(order.orderNo() + "：无可用候选车辆，跳过");
                continue;
            }
            MatchResult best = candidates.get(0);
            try {
                dispatchService.assign(order.orderId(), best.vehicleId());
                success++;
                details.add(order.orderNo() + " -> " + best.plateNumber()
                        + "（评分 " + best.score() + "）");
            } catch (IllegalArgumentException e) {
                details.add(order.orderNo() + "：派单失败 - " + e.getMessage());
            }
        }
        String msg = "自动派单完成：成功 " + success + " 张，待处理共 " + pending.size() + " 张";
        return ApiResult.ok(msg, details);
    }

    /**
     * 撤单：取消该订单未完成的调度任务、释放车辆，并把订单放回待处理池。
     * 撤单后可重新"智能匹配"派给别的车，即完成改派。
     */
    @PostMapping("/{orderId}/cancel")
    public ApiResult<Void> cancel(@PathVariable int orderId) {
        try {
            return ApiResult.ok(dispatchService.cancelDispatch(orderId));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(e.getMessage());
        }
    }
}
