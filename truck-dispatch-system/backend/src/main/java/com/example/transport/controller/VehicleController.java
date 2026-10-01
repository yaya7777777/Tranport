package com.example.transport.controller;

import com.example.transport.dto.ApiResult;
import com.example.transport.dto.VehicleCreateRequest;
import com.example.transport.dto.VehicleLocationRequest;
import com.example.transport.dto.VehicleStatusRequest;
import com.example.transport.dto.VehicleSummary;
import com.example.transport.repository.VehicleRepository;
import com.example.transport.service.FleetManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车辆接口：台账查询、增加车辆、软删除失效、设置位置与状态。
 * 跨域由 {@link com.example.transport.config.CorsConfig} 统一处理。
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleRepository repository;
    private final FleetManagementService fleetManagementService;

    public VehicleController(VehicleRepository repository, FleetManagementService fleetManagementService) {
        this.repository = repository;
        this.fleetManagementService = fleetManagementService;
    }

    /** 查询全部车辆（关联车型表与 POI 表，带出车型名称和所在站点名称） */
    @GetMapping
    public List<VehicleSummary> list() {
        return repository.findAll();
    }

    /** 新增车辆/设置位置表单的下拉数据：车型 + 司机 + POI 站点 */
    @GetMapping("/options")
    public ApiResult<com.example.transport.dto.VehicleOptions> options() {
        return ApiResult.ok("表单数据加载完成", fleetManagementService.vehicleOptions());
    }

    /**
     * 增加车辆：初始空闲、默认 200L 油量
     * 报文：{"plateNumber":"川A60001","typeId":1,"driverId":2,"poiId":3,"purchaseDate":"2026-09-01"}
     */
    @PostMapping
    public ApiResult<Integer> create(@RequestBody VehicleCreateRequest request) {
        int vehicleId = fleetManagementService.createVehicle(request);
        return ApiResult.ok("车辆新增成功，ID：" + vehicleId, vehicleId);
    }

    /** 失效车辆（软删除）：启用标志置 0；有未完成任务时拒绝 */
    @PostMapping("/{vehicleId}/disable")
    public ApiResult<Void> disable(@PathVariable int vehicleId) {
        return ApiResult.ok(fleetManagementService.disableVehicle(vehicleId));
    }

    /**
     * 设置（更新）车辆位置：锚定到 POI 站点并写一条 GPS 记录
     * 报文：{"poiId":5}
     */
    @PutMapping("/{vehicleId}/location")
    public ApiResult<Void> updateLocation(@PathVariable int vehicleId, @RequestBody VehicleLocationRequest request) {
        return ApiResult.ok(fleetManagementService.updateLocation(vehicleId, request));
    }

    /**
     * 设置（更新）车辆状态：按 vehicle_status 状态转换规则表校验并写变更日志
     * 报文：{"statusCode":"REFUEL"}
     */
    @PutMapping("/{vehicleId}/status")
    public ApiResult<Void> updateStatus(@PathVariable int vehicleId, @RequestBody VehicleStatusRequest request) {
        return ApiResult.ok(fleetManagementService.updateStatus(vehicleId, request));
    }
}
