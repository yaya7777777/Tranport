package com.example.transport.controller;

import com.example.transport.dto.OrderSummary;
import com.example.transport.repository.OrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运输订单接口：返回全部运输订单及其起止站点、货物、车型要求、状态与绑定路线，
 * 供前端订单管理列表使用。智能匹配与派单见 {@link MatchController}、{@link DispatchController}。
 * 跨域由 {@link com.example.transport.config.CorsConfig} 统一处理。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository repository;

    public OrderController(OrderRepository repository) {
        this.repository = repository;
    }

    /** 查询全部订单（关联起终点 POI 与货物表，按创建时间倒序） */
    @GetMapping
    public List<OrderSummary> list() {
        return repository.findAll();
    }
}
