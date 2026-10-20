package com.example.transport.controller;

import com.example.transport.dto.MatchResult;
import com.example.transport.dto.MatchTypeResult;
import com.example.transport.repository.OrderRepository;
import com.example.transport.service.MatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能匹配接口（任务书第 2 项："运输任务派发时的匹配计算"）。
 * 两级匹配：货物 -> 适用车型；订单 -> 可派车辆评分排序。
 */
@RestController
@RequestMapping("/api/match")
public class MatchController {

    private final MatchService matchService;
    private final OrderRepository orderRepository;

    public MatchController(MatchService matchService, OrderRepository orderRepository) {
        this.matchService = matchService;
        this.orderRepository = orderRepository;
    }

    /**
     * 第一级：按货物分类与重量体积筛选可装车型
     * 示例：/api/match/types?categoryId=1&weight=800&volume=4.5
     */
    @GetMapping("/types")
    public List<MatchTypeResult> matchTypes(@RequestParam int categoryId,
                                            @RequestParam double weight,
                                            @RequestParam double volume) {
        return matchService.matchVehicleTypes(categoryId, weight, volume);
    }

    /**
     * 第二级：为指定订单返回候选车辆评分列表（前端点"智能匹配"时调用）
     *
     * @param orderId 订单 ID
     */
    @GetMapping("/vehicles")
    public List<MatchResult> matchVehicles(@RequestParam int orderId) {
        OrderRepository.PendingOrder order = orderRepository.findOrderById(orderId);
        if (order == null) {
            return List.of();
        }
        return matchService.rankVehicles(order);
    }
}
