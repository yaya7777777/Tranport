package com.example.transport.service;

import com.example.transport.dto.OrderCreateRequest;
import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.RevenueResult;
import com.example.transport.dto.VehicleCreateRequest;
import com.example.transport.dto.VehicleLocationRequest;
import com.example.transport.dto.VehicleStatusRequest;
import com.example.transport.repository.DispatchRepository;
import com.example.transport.repository.GpsRepository;
import com.example.transport.repository.MasterDataRepository;
import com.example.transport.repository.OrderRepository;
import com.example.transport.repository.PoiRepository;
import com.example.transport.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 需求与车队管理服务：覆盖"增加/失效需求、增加/失效车辆、设置位置/状态、收益计算"等管理类操作。
 * 失效均为软删除（改状态不删行），已派车数据级联处理，保证调度链路不留脏数据。
 */
@Service
public class FleetManagementService {

    /** 综合运输成本单价（元/km，含油费/路桥/人工折算）；收益算法前期简单规则，后续可细化 */
    private static final double COST_PER_KM = 8.0;
    /** 新车默认油量（L） */
    private static final double DEFAULT_FUEL = 200.0;

    private final OrderRepository orderRepository;
    private final DispatchRepository dispatchRepository;
    private final VehicleRepository vehicleRepository;
    private final MasterDataRepository masterDataRepository;
    private final PoiRepository poiRepository;
    private final GisService gisService;
    private final GpsRepository gpsRepository;
    private final Random random = new Random();

    public FleetManagementService(OrderRepository orderRepository, DispatchRepository dispatchRepository,
                                  VehicleRepository vehicleRepository, MasterDataRepository masterDataRepository,
                                  PoiRepository poiRepository, GisService gisService, GpsRepository gpsRepository) {
        this.orderRepository = orderRepository;
        this.dispatchRepository = dispatchRepository;
        this.vehicleRepository = vehicleRepository;
        this.masterDataRepository = masterDataRepository;
        this.poiRepository = poiRepository;
        this.gisService = gisService;
        this.gpsRepository = gpsRepository;
    }

    /* ====================== 1) 增加需求（基于工厂生成） ====================== */

    /**
     * 基于工厂-仓库关系生成一条运输需求（PENDING 订单）。
     * 起点取工厂所在 POI、终点取仓库所在 POI，路线由 GIS 服务自动解析/补算。
     *
     * @return 新订单 ID
     */
    @Transactional
    public int createOrder(OrderCreateRequest req) {
        if (req.factoryId() == null || req.warehouseId() == null || req.cargoId() == null) {
            throw new IllegalArgumentException("factoryId、warehouseId、cargoId 均必填");
        }
        if (!masterDataRepository.existsFactory(req.factoryId())) {
            throw new IllegalArgumentException("工厂不存在：" + req.factoryId());
        }
        if (!masterDataRepository.existsWarehouse(req.warehouseId())) {
            throw new IllegalArgumentException("仓库不存在：" + req.warehouseId());
        }
        if (!masterDataRepository.existsCargo(req.cargoId())) {
            throw new IllegalArgumentException("货物不存在：" + req.cargoId());
        }
        // 须存在厂仓关系才能确定合法货源流向
        MasterDataRepository.FactoryRelation rel = masterDataRepository.findFactoryRelations().stream()
                .filter(r -> r.factoryId() == req.factoryId() && r.warehouseId() == req.warehouseId())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工厂 " + req.factoryId()
                        + " 与仓库 " + req.warehouseId() + " 之间没有厂仓关系，无法生成需求"));
        double quantity = req.quantity() == null || req.quantity() <= 0 ? 1 : req.quantity();
        int priority = req.priority() == null ? 2 : req.priority();

        // 起终点按厂仓关系类型决定（采购为仓->厂，生产/销售为厂->仓），由 FactoryRelation 统一判定
        PoiSummary origin = poiRepository.findById(rel.originPoiId());
        PoiSummary dest = poiRepository.findById(rel.destPoiId());
        if (origin == null || dest == null) {
            throw new IllegalArgumentException("工厂或仓库关联的 POI 站点缺失，请先补全基础数据");
        }
        // 路线缺失时 GIS 服务会自动补算并回写 route 表
        var route = gisService.resolveRoute(origin, dest);

        LocalDateTime now = LocalDateTime.now();
        String orderNo = "ORD" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%02d", random.nextInt(100));
        String remark = (req.remark() == null || req.remark().isBlank() ? "人工创建" : req.remark().trim());
        return orderRepository.insertGeneratedOrder(orderNo, req.cargoId(), quantity,
                rel.originPoiId(), rel.destPoiId(), route.routeId(), now, now.plusHours(6), priority, remark);
    }

    /* ====================== 2) 失效需求（软删除） ====================== */

    /**
     * 需求失效：状态置 CANCELLED（软删除）。
     * 已派车/在途的订单会级联取消调度记录并让车辆回到空闲，避免"幽灵任务"。
     */
    @Transactional
    public String disableOrder(int orderId) {
        String status = orderRepository.findStatusById(orderId);
        if (status == null) {
            throw new IllegalArgumentException("需求不存在：" + orderId);
        }
        if ("DELIVERED".equals(status) || "CANCELLED".equals(status)) {
            throw new IllegalArgumentException("需求当前为 " + status + " 状态，无需重复失效");
        }
        int released = 0;
        if ("ASSIGNED".equals(status) || "TRANSPORTING".equals(status)) {
            released = dispatchRepository.cancelByOrder(orderId);
        }
        orderRepository.softCancel(orderId);
        return released > 0 ? "需求已失效，并级联取消 " + released + " 条调度任务（车辆已释放）" : "需求已失效";
    }

    /* ====================== 3) 增加车辆 ====================== */

    /**
     * 新增车辆：初始空闲、默认 200L 油量、续航按车型油耗折算。
     *
     * @return 新车辆 ID
     */
    @Transactional
    public int createVehicle(VehicleCreateRequest req) {
        String plate = req.plateNumber() == null ? "" : req.plateNumber().trim();
        if (plate.isEmpty()) {
            throw new IllegalArgumentException("车牌号不能为空");
        }
        if (req.typeId() == null) {
            throw new IllegalArgumentException("车型 ID 不能为空");
        }
        if (vehicleRepository.existsPlate(plate)) {
            throw new IllegalArgumentException("车牌号已存在：" + plate);
        }
        boolean typeExists = masterDataRepository.findVehicleTypes().stream()
                .anyMatch(t -> t.typeId() == req.typeId());
        if (!typeExists) {
            throw new IllegalArgumentException("车型不存在：" + req.typeId());
        }
        if (req.driverId() != null && !masterDataRepository.existsDriver(req.driverId())) {
            throw new IllegalArgumentException("司机不存在：" + req.driverId());
        }
        if (req.poiId() != null && poiRepository.findById(req.poiId()) == null) {
            throw new IllegalArgumentException("POI 站点不存在：" + req.poiId());
        }
        LocalDate purchaseDate;
        try {
            purchaseDate = (req.purchaseDate() == null || req.purchaseDate().isBlank())
                    ? LocalDate.now() : LocalDate.parse(req.purchaseDate().trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("购置日期格式应为 yyyy-MM-dd：" + req.purchaseDate());
        }
        return vehicleRepository.insert(plate, req.typeId(), req.driverId(), req.poiId(), purchaseDate);
    }

    /* ====================== 4) 失效车辆（软删除） ====================== */

    /** 车辆失效：启用标志置 0；有未完成调度任务时拒绝，需先完成或失效对应需求 */
    @Transactional
    public String disableVehicle(int vehicleId) {
        Integer flag = vehicleRepository.findActiveFlag(vehicleId);
        if (flag == null) {
            throw new IllegalArgumentException("车辆不存在：" + vehicleId);
        }
        if (flag == 0) {
            throw new IllegalArgumentException("车辆已是停用状态");
        }
        if (dispatchRepository.findBusyVehicleIds().contains(vehicleId)) {
            throw new IllegalArgumentException("车辆有未完成的调度任务，请先完成运输或失效对应需求");
        }
        vehicleRepository.softDisable(vehicleId);
        return "车辆已停用（软删除）";
    }

    /* ====================== 6) 设置（更新）车辆位置 ====================== */

    /**
     * 设置车辆当前位置（锚定 POI 站点），同时写入一条 GPS 定位记录便于"设备定位"页查看。
     */
    @Transactional
    public String updateLocation(int vehicleId, VehicleLocationRequest req) {
        if (req == null || req.poiId() == null) {
            throw new IllegalArgumentException("poiId 不能为空");
        }
        Integer flag = vehicleRepository.findActiveFlag(vehicleId);
        if (flag == null) {
            throw new IllegalArgumentException("车辆不存在：" + vehicleId);
        }
        if (flag == 0) {
            throw new IllegalArgumentException("车辆已停用，不能设置位置");
        }
        PoiSummary poi = poiRepository.findById(req.poiId());
        if (poi == null) {
            throw new IllegalArgumentException("POI 站点不存在：" + req.poiId());
        }
        vehicleRepository.updateLocation(vehicleId, req.poiId());
        gpsRepository.insert(vehicleId, poi.longitude(), poi.latitude(), 0.0, 0.0, null, LocalDateTime.now());
        return "车辆位置已更新为：" + poi.poiName();
    }

    /* ====================== 7) 设置（更新）车辆其他状态 ====================== */

    /**
     * 手动设置车辆状态。与仿真引擎共用同一张状态转换规则表（vehicle_status.allowed_next），
     * 非法跳转会被拒绝并提示允许的下一状态；每次变更写入 vehicle_status_log 日志。
     */
    @Transactional
    public String updateStatus(int vehicleId, VehicleStatusRequest req) {
        String target = req == null || req.statusCode() == null ? "" : req.statusCode().trim().toUpperCase();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("状态编码不能为空");
        }
        Integer flag = vehicleRepository.findActiveFlag(vehicleId);
        if (flag == null) {
            throw new IllegalArgumentException("车辆不存在：" + vehicleId);
        }
        if (flag == 0) {
            throw new IllegalArgumentException("车辆已停用，不能设置状态");
        }
        Map<String, Integer> statusIds = masterDataRepository.statusIdMap();
        Integer targetId = statusIds.get(target);
        if (targetId == null) {
            throw new IllegalArgumentException("未知状态编码：" + target + "（可选 IDLE/LOADING/UNLOADING/TRANSPORT/REFUEL/MAINTAIN）");
        }
        String current = vehicleRepository.findCurrentStatus(vehicleId);
        if (current != null && !current.equals(target)) {
            Set<String> allowed = masterDataRepository.allowedNextMap().getOrDefault(current, Set.of());
            if (!allowed.contains(target)) {
                throw new IllegalArgumentException("状态不允许从 " + current + " 跳到 " + target
                        + "（规则表允许：" + allowed + "）");
            }
        }
        vehicleRepository.updateCurrentStatus(vehicleId, target);
        Integer fromId = current == null ? null : statusIds.get(current);
        vehicleRepository.logStatusChange(vehicleId, fromId, targetId,
                vehicleRepository.findCurrentPoiId(vehicleId), "人工设置状态");
        return "车辆状态已由 " + current + " 更新为 " + target;
    }

    /* ====================== 8) 计算运输收益（前期简单规则） ====================== */

    /**
     * 收益 = 货物数量 × 单价 - 运输距离 × 综合成本单价（元/km）。
     * 订单未回写路线时用 GIS 直线距离 × 1.3 弯路系数估算。算法后续可持续完善。
     */
    public RevenueResult calculateRevenue(int orderId) {
        OrderRepository.RevenueInfo info = orderRepository.findRevenueInfo(orderId);
        if (info == null) {
            throw new IllegalArgumentException("需求不存在：" + orderId);
        }
        if (info.unitPrice() == null) {
            throw new IllegalArgumentException("货物[" + info.cargoName() + "]未配置单价 unit_price，无法计算收益");
        }
        double distance;
        if (info.distance() != null && info.distance() > 0) {
            distance = info.distance();
        } else {
            PoiSummary origin = poiRepository.findById(info.originPoiId());
            PoiSummary dest = poiRepository.findById(info.destPoiId());
            if (origin == null || dest == null) {
                throw new IllegalArgumentException("起终点 POI 缺失，无法估算运输距离");
            }
            distance = gisService.distanceKm(
                    new GisService.Point(origin.longitude(), origin.latitude()),
                    new GisService.Point(dest.longitude(), dest.latitude())) * 1.3;
        }
        double income = info.quantity() * info.unitPrice();
        double cost = distance * COST_PER_KM;
        double profit = income - cost;
        String detail = String.format("收入 %.2f 元（%.2f × 单价 %.2f）－ 成本 %.2f 元（%.1f km × %.1f 元/km）",
                income, info.quantity(), info.unitPrice(), cost, distance, COST_PER_KM);
        return new RevenueResult(orderId, round2(income), round2(cost), round2(profit), detail);
    }

    /* ====================== 表单下拉数据（前端新增/设置面板用） ====================== */

    /** 新增需求表单数据：厂仓关系 + 货物 */
    public com.example.transport.dto.OrderOptions orderOptions() {
        var relations = masterDataRepository.findFwOptions().stream()
                .map(o -> new com.example.transport.dto.OrderOptions.FwRelation(
                        o.factoryId(), o.factoryName(), o.warehouseId(), o.warehouseName(), o.relationType()))
                .toList();
        return new com.example.transport.dto.OrderOptions(relations, masterDataRepository.findCargos());
    }

    /** 新增车辆/设置位置表单数据：车型 + 司机 + POI 站点 */
    public com.example.transport.dto.VehicleOptions vehicleOptions() {
        var drivers = masterDataRepository.findDrivers().stream()
                .map(d -> new com.example.transport.dto.VehicleOptions.DriverOption(
                        d.driverId(), d.name(), d.licenseType()))
                .toList();
        // 站点只取工厂/仓库/物流中心三类，数量有限便于下拉选择
        var pois = poiRepository.search(null, null, 500);
        return new com.example.transport.dto.VehicleOptions(
                masterDataRepository.findVehicleTypes(), drivers, pois);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
