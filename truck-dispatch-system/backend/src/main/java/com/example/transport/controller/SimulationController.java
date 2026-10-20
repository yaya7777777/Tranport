package com.example.transport.controller;

import com.example.transport.dto.ApiResult;
import com.example.transport.dto.SimStartRequest;
import com.example.transport.dto.SimulationSnapshot;
import com.example.transport.dto.SimulationStatus;
import com.example.transport.dto.StatusLogView;
import com.example.transport.service.SimulationEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仿真控制接口（任务书第 3 项）：
 * 启动/停止/复位仿真、查询运行状态、拉取沙盘快照（车辆实时位置 + 交通异常）、状态变更日志。
 */
@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final SimulationEngine engine;

    public SimulationController(SimulationEngine engine) {
        this.engine = engine;
    }

    /** 启动仿真。报文可空（默认全部车辆参与 + 自动生成货源），如 {"vehicleCount":12,"autoOrders":true} */
    @PostMapping("/start")
    public ApiResult<SimulationStatus> start(@RequestBody(required = false) SimStartRequest request) {
        try {
            Integer count = request == null ? null : request.vehicleCount();
            Boolean auto = request == null ? null : request.autoOrders();
            return ApiResult.ok("仿真已启动", engine.start(count, auto));
        } catch (IllegalStateException e) {
            return ApiResult.fail(e.getMessage());
        }
    }

    /** 停止仿真（车辆保留在当前位置与状态） */
    @PostMapping("/stop")
    public ApiResult<SimulationStatus> stop() {
        return ApiResult.ok("仿真已停止", engine.stop());
    }

    /** 复位：停止仿真并把所有车辆置为空闲 */
    @PostMapping("/reset")
    public ApiResult<SimulationStatus> reset() {
        return ApiResult.ok("仿真已复位", engine.reset());
    }

    /** 运行状态（节拍数、6 状态分布、已完成订单、异常数等） */
    @GetMapping("/status")
    public SimulationStatus status() {
        return engine.buildStatus();
    }

    /** 沙盘快照：车辆实时位置 + 交通异常（前端定时轮询） */
    @GetMapping("/snapshot")
    public SimulationSnapshot snapshot() {
        return engine.snapshot();
    }

    /** 最近的车辆状态变更日志 */
    @GetMapping("/logs")
    public List<StatusLogView> logs(@RequestParam(defaultValue = "30") int limit) {
        return engine.recentLogs(limit);
    }
}
