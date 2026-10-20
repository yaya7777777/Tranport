package com.example.transport.controller;

import com.example.transport.service.GisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GIS 路线接口：给前端仿真沙盘提供"沿真实道路"的驾车路线几何数据。
 * <p>
 * 坐标系说明：入参与返回均为 GCJ-02（高德坐标系），与 poi 表数据同源，
 * 前端高德底图可直接绘制，无需再做 WGS-84 纠偏。
 * </p>
 */
@RestController
@RequestMapping("/api/gis")
public class GisController {

    private final GisService gisService;

    public GisController(GisService gisService) {
        this.gisService = gisService;
    }

    /**
     * 驾车路线规划：返回沿真实道路的坐标点、里程与耗时。
     *
     * @param fromLng/fromLat   起点（GCJ-02）
     * @param toLng/toLat       终点（GCJ-02）
     * @param fromPoiId/toPoiId 可选，两端都是 POI 时传入，结果持久化到 route 表复用
     * @return points 为坐标数组 [[lng,lat],...]；规划不可用时 points 为空数组，前端降级为直线
     */
    @GetMapping("/route")
    public GisService.RoutePath route(@RequestParam double fromLng, @RequestParam double fromLat,
                                      @RequestParam double toLng, @RequestParam double toLat,
                                      @RequestParam(required = false) Integer fromPoiId,
                                      @RequestParam(required = false) Integer toPoiId) {
        GisService.RoutePath path = gisService.routePath(fromLng, fromLat, toLng, toLat, fromPoiId, toPoiId);
        return path != null ? path : new GisService.RoutePath(List.of(), 0, 0, false);
    }
}
