package com.example.transport.controller;

import com.example.transport.dto.CargoCategoryView;
import com.example.transport.dto.CategoryCount;
import com.example.transport.dto.MatchMatrix;
import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.VehicleTypeView;
import com.example.transport.repository.MasterDataRepository;
import com.example.transport.repository.PoiRepository;
import com.example.transport.service.MatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 基础数据接口（任务书第 2 项）：
 * POI 站点、POI 分类统计、车型与装载能力、货物分类、货物-车型匹配矩阵。
 */
@RestController
@RequestMapping("/api")
public class PoiController {

    private final PoiRepository poiRepository;
    private final MasterDataRepository masterDataRepository;
    private final MatchService matchService;

    public PoiController(PoiRepository poiRepository, MasterDataRepository masterDataRepository,
                         MatchService matchService) {
        this.poiRepository = poiRepository;
        this.masterDataRepository = masterDataRepository;
        this.matchService = matchService;
    }

    /** POI 列表：支持分类过滤与关键字搜索，默认最多返回 2000 条 */
    @GetMapping("/pois")
    public List<PoiSummary> pois(@RequestParam(required = false) Integer categoryId,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(defaultValue = "2000") int limit) {
        return poiRepository.search(categoryId, keyword, limit);
    }

    /** POI 分类及数量（用于展示"3 类/5 类、500/1000 个"的达标情况） */
    @GetMapping("/poi-categories")
    public List<CategoryCount> poiCategories() {
        return poiRepository.categoryCounts();
    }

    /** 车型与装载能力列表 */
    @GetMapping("/vehicle-types")
    public List<VehicleTypeView> vehicleTypes() {
        return masterDataRepository.findVehicleTypes();
    }

    /** 货物分类列表 */
    @GetMapping("/cargo-categories")
    public List<CargoCategoryView> cargoCategories() {
        return masterDataRepository.findCargoCategories();
    }

    /** 货物分类 × 车型匹配矩阵 */
    @GetMapping("/match/matrix")
    public MatchMatrix matchMatrix() {
        return matchService.buildMatrix();
    }
}
