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
import java.util.List;
import java.util.Random;

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
        for (int i = 0; i < need; i++) {
            MasterDataRepository.FactoryRelation rel = relations.get(random.nextInt(relations.size()));
            CargoOption cargo = cargos.get(random.nextInt(cargos.size()));

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
