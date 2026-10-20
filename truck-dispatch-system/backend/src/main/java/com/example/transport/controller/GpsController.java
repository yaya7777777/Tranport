package com.example.transport.controller;

import com.example.transport.dto.ApiResult;
import com.example.transport.dto.GpsPoint;
import com.example.transport.dto.GpsReport;
import com.example.transport.service.GpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 北斗/GPS 定位接口（任务书第 1 项的云端接收入口）。
 * tools/modbus_collector.py 采集 ARM 板上北斗模块的经纬度后，
 * 以 HTTP POST JSON 方式调用 {@link #report}。
 */
@RestController
@RequestMapping("/api/gps")
public class GpsController {

    private final GpsService gpsService;

    public GpsController(GpsService gpsService) {
        this.gpsService = gpsService;
    }

    /**
     * 接收一条定位上报报文。
     * 报文示例：{"vehicleCode":"川A10001","longitude":104.06,"latitude":30.57,"speed":42.5,"heading":90}
     */
    @PostMapping("/report")
    public ApiResult<GpsPoint> report(@RequestBody GpsReport report) {
        try {
            return ApiResult.ok("定位数据已接收", gpsService.report(report));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(e.getMessage());
        }
    }

    /** 最新定位数据（真实设备与仿真混合，倒序） */
    @GetMapping("/latest")
    public List<GpsPoint> latest(@RequestParam(defaultValue = "20") int limit) {
        return gpsService.latest(limit);
    }

    /** 某辆车的历史轨迹 */
    @GetMapping("/history")
    public List<GpsPoint> history(@RequestParam int vehicleId,
                                  @RequestParam(defaultValue = "50") int limit) {
        return gpsService.history(vehicleId, limit);
    }
}
