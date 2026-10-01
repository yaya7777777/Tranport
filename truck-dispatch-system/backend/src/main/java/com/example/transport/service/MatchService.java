package com.example.transport.service;

import com.example.transport.dto.MatchResult;
import com.example.transport.dto.MatchTypeResult;
import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.VehicleTypeView;
import com.example.transport.repository.DispatchRepository;
import com.example.transport.repository.MasterDataRepository;
import com.example.transport.repository.OrderRepository;
import com.example.transport.repository.PoiRepository;
import com.example.transport.repository.VehicleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调度匹配服务（任务书第 2 项核心："实现匹配或筛选调用函数，用于运输任务派发时的匹配计算"）。
 * <p>两级匹配：</p>
 * <ol>
 *   <li>货物 -> 车型：货物分类匹配规则 + 载重/容积硬约束，输出可装车型（优选标记）；</li>
 *   <li>订单 -> 车辆：在可装车型车辆中，按"车型优选 / 运力贴合 / 距离 / 司机常跑路线 / 续航"打分排序。</li>
 * </ol>
 */
@Service
public class MatchService {

    private final MasterDataRepository masterDataRepository;
    private final VehicleRepository vehicleRepository;
    private final PoiRepository poiRepository;
    private final DispatchRepository dispatchRepository;
    private final GisService gisService;

    public MatchService(MasterDataRepository masterDataRepository, VehicleRepository vehicleRepository,
                        PoiRepository poiRepository, DispatchRepository dispatchRepository,
                        GisService gisService) {
        this.masterDataRepository = masterDataRepository;
        this.vehicleRepository = vehicleRepository;
        this.poiRepository = poiRepository;
        this.dispatchRepository = dispatchRepository;
        this.gisService = gisService;
    }

    /**
     * 第一级：为指定货物筛选适用车型
     *
     * @param categoryId 货物分类 ID
     * @param weight     货物重量 kg（硬约束：车型最大载重必须 ≥ 该值）
     * @param volume     货物体积 m³（硬约束：车型最大容积必须 ≥ 该值）
     * @return 可装车型列表，优选车型排在前面
     */
    public List<MatchTypeResult> matchVehicleTypes(int categoryId, double weight, double volume) {
        // 匹配规则：categoryId -> (typeId -> 是否优选)
        Map<Integer, Map<Integer, Boolean>> ruleMap = loadRuleMap();
        Map<Integer, Boolean> typeRule = ruleMap.getOrDefault(categoryId, Map.of());

        List<MatchTypeResult> results = new ArrayList<>();
        for (VehicleTypeView type : masterDataRepository.findVehicleTypes()) {
            Boolean preferred = typeRule.get(type.typeId());
            if (preferred == null) {
                continue; // 匹配表中没有记录 = 该车型不能装此类货物
            }
            // 载重、容积硬约束
            if (type.maxLoad() < weight) {
                continue;
            }
            if (type.maxVolume() < volume) {
                continue;
            }
            String reason = (preferred ? "优选车型；" : "可装车型；")
                    + "载重余量 " + Math.round(type.maxLoad() - weight) + "kg，"
                    + "容积余量 " + Math.round(type.maxVolume() - volume) + "m³";
            results.add(new MatchTypeResult(type.typeId(), type.typeName(), preferred, reason));
        }
        // 优选优先，其次按载重贴合度（越经济的车越靠前）
        results.sort(Comparator.comparing(MatchTypeResult::preferred).reversed()
                .thenComparing(t -> t.typeId()));
        return results;
    }

    /**
     * 第二级：为订单对全部空闲车辆打分排序（智能派车的候选列表）。
     * 已被未完成调度占用的车辆直接排除。
     *
     * @param order 订单（含货物重量体积与起终点）
     * @return 按综合评分降序的候选车辆（最多 8 辆）
     */
    public List<MatchResult> rankVehicles(OrderRepository.PendingOrder order) {
        PoiSummary origin = poiRepository.findById(order.originPoiId());
        PoiSummary dest = poiRepository.findById(order.destPoiId());
        if (origin == null || dest == null) {
            return List.of();
        }

        Map<Integer, Map<Integer, Boolean>> ruleMap = loadRuleMap();
        Set<Integer> typeIds = ruleMap.getOrDefault(order.categoryId(), Map.of()).keySet();
        Set<Integer> busyVehicles = dispatchRepository.findBusyVehicleIds();
        double routeDistance = gisService.distanceKm(
                new GisService.Point(origin.longitude(), origin.latitude()),
                new GisService.Point(dest.longitude(), dest.latitude())) * 1.3;

        List<MatchResult> results = new ArrayList<>();
        for (VehicleRepository.SimVehicle v : vehicleRepository.findSimVehicles()) {
            // 硬约束：空闲、未被占用、车型可装、载重容积足够
            if (!"IDLE".equals(v.status()) || busyVehicles.contains(v.vehicleId())) {
                continue;
            }
            Boolean preferred = ruleMap.getOrDefault(order.categoryId(), Map.of()).get(v.typeId());
            // cargo.weight/volume 是单件重量与体积，须用整单总量比较（totalWeight/totalVolume 已乘数量）
            if (preferred == null || v.maxLoad() < order.totalWeight() || v.maxVolume() < order.totalVolume()) {
                continue;
            }

            double score = 0;
            List<String> reasons = new ArrayList<>();

            // 1) 车型优选（+25 / +10）
            if (preferred) {
                score += 25;
                reasons.add("车型为优选车型 +25");
            } else {
                score += 10;
                reasons.add("车型可装 +10");
            }

            // 2) 运力贴合度（+20）：载重利用率落在 60%-95% 最经济（按整单总重计算）
            double loadRatio = order.totalWeight() / v.maxLoad();
            if (loadRatio >= 0.6 && loadRatio <= 0.95) {
                score += 20;
                reasons.add("载重利用率 " + Math.round(loadRatio * 100) + "%，运力贴合 +20");
            } else if (loadRatio > 0.95) {
                score += 12;
                reasons.add("接近满载 +12");
            } else {
                score += 8;
                reasons.add("运力冗余偏大 +8");
            }

            // 3) 车辆当前位置到发货地的距离（指数衰减，最高 +30）
            double distanceToOrigin = Double.MAX_VALUE;
            String currentPoiName = "途中";
            if (v.lng() != null && v.lat() != null) {
                distanceToOrigin = gisService.distanceKm(
                        new GisService.Point(v.lng(), v.lat()),
                        new GisService.Point(origin.longitude(), origin.latitude()));
            }
            if (v.poiId() != null) {
                PoiSummary cur = poiRepository.findById(v.poiId());
                if (cur != null) {
                    currentPoiName = cur.poiName();
                }
            }
            double distanceScore = 30 * Math.exp(-distanceToOrigin / 25.0);
            score += distanceScore;
            reasons.add("距发货地约 " + Math.round(distanceToOrigin) + " km +" + Math.round(distanceScore));

            // 4) 司机常跑路线偏好（最高 +15）
            if (order.routeId() != null && v.favoriteRoutes().contains(order.routeId())) {
                score += 15;
                reasons.add("司机常跑该路线 +15");
            }

            // 5) 续航能否支撑往返（+10 / +5 / 0）
            double estRange = v.fuelConsumption() > 0
                    ? v.fuel() / (v.fuelConsumption() / 100.0) : 0;
            if (estRange >= routeDistance * 2) {
                score += 10;
                reasons.add("续航充足 +10");
            } else if (estRange >= routeDistance) {
                score += 5;
                reasons.add("续航仅够单程 +5");
            } else {
                reasons.add("续航不足，建议先加油 +0");
            }

            results.add(new MatchResult(v.vehicleId(), v.plate(), v.typeName(), v.driverName(),
                    currentPoiName, Math.round(distanceToOrigin * 10.0) / 10.0,
                    v.fuel(), Math.round(score * 10.0) / 10.0, reasons));
        }
        results.sort(Comparator.comparing(MatchResult::score).reversed());
        return results.size() > 8 ? results.subList(0, 8) : results;
    }

    /**
     * 构建"货物分类 × 车型"完整匹配矩阵（基础数据页表格用）。
     * 矩阵每个单元格标记该车型对该货物分类是：不可装 / 可装 / 优选。
     */
    public com.example.transport.dto.MatchMatrix buildMatrix() {
        Map<Integer, Map<Integer, Boolean>> ruleMap = loadRuleMap();
        List<VehicleTypeView> types = masterDataRepository.findVehicleTypes();
        List<com.example.transport.dto.MatchMatrix.Row> rows = new ArrayList<>();
        for (var category : masterDataRepository.findCargoCategories()) {
            Map<Integer, Boolean> typeRule = ruleMap.getOrDefault(category.categoryId(), Map.of());
            List<com.example.transport.dto.MatchMatrix.Cell> cells = new ArrayList<>();
            for (VehicleTypeView type : types) {
                Boolean preferred = typeRule.get(type.typeId());
                cells.add(new com.example.transport.dto.MatchMatrix.Cell(type.typeId(), type.typeName(),
                        preferred != null, Boolean.TRUE.equals(preferred)));
            }
            rows.add(new com.example.transport.dto.MatchMatrix.Row(
                    category.categoryId(), category.categoryName(), cells));
        }
        return new com.example.transport.dto.MatchMatrix(rows);
    }

    /**
     * 加载匹配规则为 Map：货物分类 ID -> (车型 ID -> 是否优选)。
     * 每次调用实时查库，规则在"基础数据"页修改后立即生效。
     */
    private Map<Integer, Map<Integer, Boolean>> loadRuleMap() {
        Map<Integer, Map<Integer, Boolean>> map = new HashMap<>();
        for (MasterDataRepository.MatchRule rule : masterDataRepository.findMatchRules()) {
            map.computeIfAbsent(rule.categoryId(), k -> new HashMap<>())
                    .put(rule.vehicleTypeId(), rule.preferred());
        }
        return map;
    }
}
