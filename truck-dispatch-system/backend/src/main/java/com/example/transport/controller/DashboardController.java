package com.example.transport.controller;

import com.example.transport.dto.DashboardStats;
import com.example.transport.repository.DashboardRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页驾驶舱接口：聚合车辆各状态数量、待派订单数、在途调度数、POI 总数与活跃交通异常数，
 * 供前端 Dashboard 页面顶部统计卡片展示。跨域由 {@link com.example.transport.config.CorsConfig} 统一处理。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardRepository repository;

    public DashboardController(DashboardRepository repository) {
        this.repository = repository;
    }

    /** 获取驾驶舱聚合统计数据（一次 GROUP BY 查询车辆状态，其余为 COUNT 查询） */
    @GetMapping("/stats")
    public DashboardStats stats() {
        return repository.stats();
    }

    /** 后端存活探针，供前端或部署脚本检查服务是否启动 */
    @GetMapping("/health")
    public String health() {
        return "transport backend is running";
    }
}
