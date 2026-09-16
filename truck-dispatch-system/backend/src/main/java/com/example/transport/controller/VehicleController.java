package com.example.transport.controller;

import com.example.transport.dto.VehicleSummary;
import com.example.transport.repository.VehicleRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车辆台账接口：返回全部车辆的基础信息（车牌、车型、载重容积、当前位置、状态与油量），
 * 供前端车辆管理列表使用。跨域由 {@link com.example.transport.config.CorsConfig} 统一处理。
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleRepository repository;

    public VehicleController(VehicleRepository repository) {
        this.repository = repository;
    }

    /** 查询全部车辆（关联车型表与 POI 表，带出车型名称和所在站点名称） */
    @GetMapping
    public List<VehicleSummary> list() {
        return repository.findAll();
    }
}
