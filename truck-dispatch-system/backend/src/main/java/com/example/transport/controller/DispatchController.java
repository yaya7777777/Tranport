package com.example.transport.controller;

import com.example.transport.dto.ApiResult;
import com.example.transport.dto.DispatchRequest;
import com.example.transport.dto.RouteInfo;
import com.example.transport.service.DispatchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手动调度接口：前端在智能匹配结果上点"派车"时调用。
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
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
}
