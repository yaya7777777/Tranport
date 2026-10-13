package com.example.transport.service;

import com.example.transport.dto.CargoOption;
import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.RouteInfo;
import com.example.transport.repository.MasterDataRepository;
import com.example.transport.repository.OrderRepository;
import com.example.transport.repository.PoiRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 货源订单自动生成服务（任务书第 3 项："货源地生成、工厂与货源关系处理（采购、生产、销售）"）。
 * <p>
 * 仿真运行时，若待处理订单过少，就从 factory_warehouse 厂仓关系中随机抽取关系生成新订单：
 * PROCURE 采购：仓库 -> 工厂（原料流入工厂）；
 * PRODUCE 生产：工厂 -> 仓库（成品下线入仓）；
 * SALE   销售：工厂 -> 仓库/物流（成品外运）。
 * 从而保证多车仿真可以无限循环运行。
 * </p>
 */
@Service
public class OrderGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(OrderGeneratorService.class);

    private final MasterDataRepository masterDataRepository;
    private final OrderRepository orderRepository;
    private final PoiRepository poiRepository;
    private final GisService gisService;

    /** 固定随机种子，便于演示结果可复现；如需每次不同可改为 new Random() */
    private final Random random = new Random(20260915);

    /** 工厂类型 → 可生产的货物分类 ID（如机车厂不能产生鲜食品） */
    private static final Map<String, Set<Integer>> FACTORY_CARGO_MAP = new HashMap<>();
    /** 仓库类型 → 可存放的货物分类 ID（如冷库只放生鲜） */
    private static final Map<String, Set<Integer>> WAREHOUSE_CARGO_MAP = new HashMap<>();

    static {
        // 工厂类型映射
        FACTORY_CARGO_MAP.put("食品厂", Set.of(1));            // 生鲜食品
        FACTORY_CARGO_MAP.put("建材厂", Set.of(2));            // 建材
        FACTORY_CARGO_MAP.put("电子厂", Set.of(3));            // 电子产品
        FACTORY_CARGO_MAP.put("加工厂", Set.of(4, 5));         // 化工品、日用品
        FACTORY_CARGO_MAP.put("制造厂", Set.of(2, 4, 5));      // 建材、化工品、日用品
        FACTORY_CARGO_MAP.put("组装厂", Set.of(3, 5));         // 电子产品、日用品
        // 仓库类型映射
        WAREHOUSE_CARGO_MAP.put("冷库", Set.of(1));            // 生鲜食品
        WAREHOUSE_CARGO_MAP.put("原料仓", Set.of(2, 4));       // 建材、化工品
        WAREHOUSE_CARGO_MAP.put("成品仓", Set.of(3, 5));       // 电子产品、日用品
        WAREHOUSE_CARGO_MAP.put("电商仓", Set.of(3, 5));       // 电子产品、日用品
        WAREHOUSE_CARGO_MAP.put("中转仓", Set.of(1, 2, 3, 4, 5)); // 综合
        WAREHOUSE_CARGO_MAP.put("综合仓", Set.of(1, 2, 3, 4, 5)); // 综合
    }

    /**
     * 计算某厂仓关系允许的货物分类 ID 集合 = 工厂可生产 ∩ 仓库可存放。
     * 若交集为空（如加工厂+冷库），返回空集合，由调用方跳过该条关系。
     */
    private Set<Integer> allowedCategoryIds(String factoryType, String warehouseType) {
        Set<Integer> fac = FACTORY_CARGO_MAP.getOrDefault(factoryType,
                Set.of(1, 2, 3, 4, 5));
        Set<Integer> wh = WAREHOUSE_CARGO_MAP.getOrDefault(warehouseType,
                Set.of(1, 2, 3, 4, 5));
        Set<Integer> intersection = new HashSet<>(fac);
        intersection.retainAll(wh);
        return intersection;
    }

    public OrderGeneratorService(MasterDataRepository masterDataRepository, OrderRepository orderRepository,
                                 PoiRepository poiRepository, GisService gisService) {
        this.masterDataRepository = masterDataRepository;
        this.orderRepository = orderRepository;
        this.poiRepository = poiRepository;
        this.gisService = gisService;
    }

    /**
     * 保证待处理订单数不低于阈值
     *
     * @param minPending 期望的最少待处理订单数
     * @param maxAdd     本次最多补多少张
     * @return 实际新增的订单数
     */
    public int ensureOrders(int minPending, int maxAdd) {
        long pending = orderRepository.countPending();
        if (pending >= minPending) {
            return 0;
        }
        List<MasterDataRepository.FactoryRelation> relations = masterDataRepository.findFactoryRelations();
        List<CargoOption> cargos = masterDataRepository.findCargos();
        if (relations.isEmpty() || cargos.isEmpty()) {
            log.warn("缺少厂仓关系或货物主数据，无法自动生成货源订单");
            return 0;
        }

        int need = (int) Math.min(maxAdd, minPending - pending);
        int created = 0;
        int attempts = 0;
        while (created < need && attempts < need * 10) {
            attempts++;
            MasterDataRepository.FactoryRelation rel = relations.get(random.nextInt(relations.size()));

            // 按工厂类型 ∩ 仓库类型筛选可运输的货物分类
            Set<Integer> allowed = allowedCategoryIds(rel.factoryType(), rel.warehouseType());
            List<CargoOption> matched = new ArrayList<>();
            for (CargoOption c : cargos) {
                if (allowed.contains(c.categoryId())) {
                    matched.add(c);
                }
            }
            if (matched.isEmpty()) {
                continue; // 该组厂仓类型无匹配货物，跳过
            }
            CargoOption cargo = matched.get(random.nextInt(matched.size()));

            // 货物流向：采购(仓->厂) / 生产、销售(厂->仓)，由 FactoryRelation 统一判定
            int originPoiId = rel.originPoiId();
            int destPoiId = rel.destPoiId();
            String relationText = "PROCURE".equals(rel.relationType()) ? "采购（仓库->工厂）"
                    : "PRODUCE".equals(rel.relationType()) ? "生产（工厂->成品仓）" : "销售（工厂->外运仓）";

            PoiSummary origin = poiRepository.findById(originPoiId);
            PoiSummary dest = poiRepository.findById(destPoiId);
            if (origin == null || dest == null) {
                continue;
            }
            // 路线缺失时 GIS 服务会自动补算并回写
            RouteInfo route = gisService.resolveRoute(origin, dest);

            LocalDateTime now = LocalDateTime.now();
            String orderNo = "ORD" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + String.format("%02d", random.nextInt(100));
            LocalDateTime required = now.plusHours(3 + random.nextInt(6)); // 要求 3-8 小时内送达
            int priority = 1 + random.nextInt(3);
            String remark = "仿真自动生成 · " + relationText + " · " + cargo.cargoName();

            orderRepository.insertGeneratedOrder(orderNo, cargo.cargoId(), 1,
                    originPoiId, destPoiId, route.routeId(), now, required, priority, remark);
            created++;
        }
        if (created > 0) {
            log.info("根据厂仓关系自动生成 {} 张货源订单（{}）", created, "采购/生产/销售");
        }
        return created;
    }
}
