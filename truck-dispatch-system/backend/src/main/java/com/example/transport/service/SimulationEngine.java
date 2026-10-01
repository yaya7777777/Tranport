package com.example.transport.service;

import com.example.transport.dto.AnomalyView;
import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.RouteInfo;
import com.example.transport.dto.SimulationSnapshot;
import com.example.transport.dto.SimulationStatus;
import com.example.transport.dto.StatusLogView;
import com.example.transport.dto.VehiclePosition;
import com.example.transport.repository.DispatchRepository;
import com.example.transport.repository.DispatchRepository.DispatchedTask;
import com.example.transport.repository.GpsRepository;
import com.example.transport.repository.MasterDataRepository;
import com.example.transport.repository.OrderRepository;
import com.example.transport.repository.OrderRepository.PendingOrder;
import com.example.transport.repository.PoiRepository;
import com.example.transport.repository.RouteRepository;
import com.example.transport.repository.SimulationRepository;
import com.example.transport.repository.VehicleRepository;
import com.example.transport.repository.VehicleRepository.SimVehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 车辆运行仿真引擎（任务书第 3 项核心）。
 * <p>
 * 以固定节拍（默认真实 2 秒 = 仿真 5 分钟）推进所有车辆实体，每辆车严格按照
 * vehicle_status 表的"状态转换规则表"在 6 种状态间流转：
 * </p>
 * <pre>
 *   IDLE 空闲 ──接订单──&gt; LOADING 装载(含去发货地) ──装车完成──&gt; TRANSPORT 运输
 *     ↑                                                    │
 *     └──────── UNLOADING 卸货 &lt;─────────────────────────────┘
 *   IDLE ──油量不足──&gt; REFUEL 加油(含去加油站) ──加油完成──&gt; IDLE
 *   IDLE ──例行保养──&gt; MAINTAIN 保养 ──完成──&gt; IDLE
 * </pre>
 * 行驶中按车型平均速度推进经纬度（线性插值），按百公里油耗扣减油量；
 * 随机产生拥堵/事故/施工/天气四类交通异常并使车辆减速；
 * 空闲车辆通过 {@link MatchService} 的规则自动抢单（实现"找货"），
 * 订单不足时由 {@link OrderGeneratorService} 按厂仓关系自动生成货源，
 * 从而支持 10 辆车以上无限循环运行。
 */
@Service
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 无坐标车辆的兜底点（成都市中心） */
    private static final GisService.Point CENTER = new GisService.Point(104.0668, 30.5728);

    /* ---------------- 依赖 ---------------- */
    private final VehicleRepository vehicleRepository;
    private final PoiRepository poiRepository;
    private final OrderRepository orderRepository;
    private final DispatchRepository dispatchRepository;
    private final GpsRepository gpsRepository;
    private final RouteRepository routeRepository;
    private final SimulationRepository simulationRepository;
    private final MasterDataRepository masterDataRepository;
    private final GisService gisService;
    private final DispatchService dispatchService;
    private final OrderGeneratorService orderGenerator;

    /** 一个节拍代表的仿真分钟数（application.properties 可配） */
    @Value("${transport.sim.minutes-per-tick:5}")
    private double minutesPerTick;
    /** 每 N 个节拍落一次 GPS 轨迹 */
    @Value("${transport.sim.gps-every-n-ticks:1}")
    private long gpsEveryNTicks;
    /** 每个行驶节拍触发交通异常的概率 */
    private static final double ANOMALY_PROBABILITY = 0.04;
    /** 空闲车辆每节拍触发例行保养的概率 */
    private static final double MAINTAIN_PROBABILITY = 0.02;
    /** 低于该油量（L）优先去加油 */
    private static final double LOW_FUEL = 20.0;
    /** 加油目标油量（L） */
    private static final double FULL_FUEL = 80.0;

    private final Random random = new Random(7);

    /* ---------------- 运行时状态（只在 synchronized(lock) 内读写） ---------------- */
    private final Object lock = new Object();
    private volatile boolean running = false;
    private volatile Integer simId;
    private volatile String simStart;
    private long tick;
    private int processedOrders;
    private boolean autoOrders = true;
    private List<Agent> agents = List.of();
    private final List<AnomalyRuntime> anomalies = new ArrayList<>();
    /** POI 缓存：poiId -> 站点信息（含坐标） */
    private final Map<Integer, PoiSummary> poiCache = new HashMap<>();
    /** 状态编码 -> 状态主键 ID（写日志外键用） */
    private Map<String, Integer> statusIds = Map.of();
    /** 状态编码 -> 允许跳转的下一状态集合（来自"状态转换规则表"） */
    private Map<String, Set<String>> allowedNext = Map.of();

    public SimulationEngine(VehicleRepository vehicleRepository, PoiRepository poiRepository,
                            OrderRepository orderRepository, DispatchRepository dispatchRepository,
                            GpsRepository gpsRepository, RouteRepository routeRepository,
                            SimulationRepository simulationRepository, MasterDataRepository masterDataRepository,
                            GisService gisService, DispatchService dispatchService,
                            OrderGeneratorService orderGenerator) {
        this.vehicleRepository = vehicleRepository;
        this.poiRepository = poiRepository;
        this.orderRepository = orderRepository;
        this.dispatchRepository = dispatchRepository;
        this.gpsRepository = gpsRepository;
        this.routeRepository = routeRepository;
        this.simulationRepository = simulationRepository;
        this.masterDataRepository = masterDataRepository;
        this.gisService = gisService;
        this.dispatchService = dispatchService;
        this.orderGenerator = orderGenerator;
    }

    /* ============================================================
     * 仿真生命周期：启动 / 停止 / 复位
     * ============================================================ */

    /**
     * 启动仿真
     *
     * @param requestedCount 期望参与车辆数（超过在库数自动截断）
     * @param auto           是否自动生成货源订单
     */
    public SimulationStatus start(Integer requestedCount, Boolean auto) {
        synchronized (lock) {
            if (running) {
                return buildStatus();
            }
            // 1) 装载车辆、状态规则、POI 缓存
            List<SimVehicle> vehicles = vehicleRepository.findSimVehicles();
            if (vehicles.isEmpty()) {
                throw new IllegalStateException("数据库中没有启用的车辆，无法启动仿真");
            }
            statusIds = masterDataRepository.statusIdMap();
            allowedNext = masterDataRepository.allowedNextMap();
            poiCache.clear();
            poiRepository.search(null, null, 5000).forEach(p -> poiCache.put(p.poiId(), p));

            int n = requestedCount == null ? vehicles.size()
                    : Math.max(1, Math.min(requestedCount, vehicles.size()));

            // 2) 构造车辆代理；非空闲又没有在途上下文的状态统一修正为空闲，避免车辆"卡死"
            List<Agent> list = new ArrayList<>();
            Map<Integer, Agent> byVehicleId = new HashMap<>();
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < n; i++) {
                SimVehicle sv = vehicles.get(i);
                Agent agent = new Agent(sv);
                agent.pos = (sv.lng() != null && sv.lat() != null)
                        ? new GisService.Point(sv.lng(), sv.lat()) : CENTER;
                if (!"IDLE".equals(agent.status)) {
                    agent.status = "IDLE";
                }
                list.add(agent);
                byVehicleId.put(agent.vehicleId, agent);
            }

            // 3) 接续数据库里"已派单未发车"的任务（如演示数据中的 ORD...002）
            for (DispatchedTask task : dispatchRepository.findDispatchedTasks()) {
                Agent agent = byVehicleId.get(task.vehicleId());
                if (agent != null) {
                    adoptDispatchedTask(agent, task, now);
                }
            }

            // 4) 初始化运行时状态并登记仿真记录
            this.agents = list;
            this.anomalies.clear();
            this.tick = 0;
            this.processedOrders = 0;
            this.autoOrders = auto == null || auto;
            if (autoOrders) {
                orderGenerator.ensureOrders(4, 6);
            }
            String config = String.format(
                    "{\"minutesPerTick\":%s,\"gpsEveryNTicks\":%s,\"anomalyProbability\":%s,\"autoOrders\":%s}",
                    minutesPerTick, gpsEveryNTicks, ANOMALY_PROBABILITY, autoOrders);
            simId = simulationRepository.createRecord(
                    "多车循环仿真（" + n + "车）", n >= 10 ? "FULL" : "MULTI", n, config);
            simStart = now.format(FMT);
            running = true;
            log.info("仿真启动 simId={}，参与车辆 {} 辆", simId, n);
            return buildStatus();
        }
    }

    /**
     * 让车辆接续一条未完成任务：
     * DISPATCHED 已派车未发车 -> 进入 LOADING（不在发货地则先空驶过去）；
     * IN_TRANSIT 在途 -> 经 LOADING 合规跳转到 TRANSPORT，从当前位置直接开向收货地。
     * 规则表不允许 IDLE 直接到 TRANSPORT，故在途接续时先落一次 LOADING 日志。
     *
     * @return true=已成功接续；false=装卸点数据缺失，放弃接续（车辆保持空闲）
     */
    private boolean adoptDispatchedTask(Agent agent, DispatchedTask task, LocalDateTime now) {
        agent.orderId = task.orderId();
        agent.orderNo = task.orderNo();
        agent.destPoiId = task.destPoiId();
        PoiSummary origin = poiCache.get(task.originPoiId());
        PoiSummary dest = poiCache.get(task.destPoiId());
        if (dest == null) {
            log.warn("任务 {} 的收货 POI {} 缺失，车辆 {} 放弃接续", task.orderNo(), task.destPoiId(), agent.plate);
            agent.orderId = null;
            agent.orderNo = null;
            agent.destPoiId = null;
            return false;
        }
        agent.destName = dest.poiName();
        if (origin != null) {
            agent.originName = origin.poiName();
        }

        if ("IN_TRANSIT".equals(task.dispatchStatus())) {
            // 在途接续：IDLE -> LOADING -> TRANSPORT 两次合法跳转，然后从当前位置续跑
            changeStatus(agent, "LOADING", now, "接续在途订单 " + task.orderNo());
            changeStatus(agent, "TRANSPORT", now, "继续前往 " + dest.poiName());
            beginTrip(agent, new GisService.Point(dest.longitude(), dest.latitude()),
                    task.destPoiId(), task.routeId());
            return true;
        }

        changeStatus(agent, "LOADING", now, "接续已派订单 " + task.orderNo());
        if (origin != null && !Integer.valueOf(task.originPoiId()).equals(agent.poiId)) {
            beginTrip(agent, new GisService.Point(origin.longitude(), origin.latitude()),
                    task.originPoiId(), task.routeId());
        } else {
            agent.dwell = 2; // 已在发货地，直接装车 2 个节拍
        }
        return true;
    }

    /** 停止仿真：解除异常、完结仿真记录；车辆最后状态保留在数据库中 */
    public SimulationStatus stop() {
        synchronized (lock) {
            if (running) {
                running = false;
                LocalDateTime now = LocalDateTime.now();
                anomalies.forEach(a -> routeRepository.resolveAnomaly(a.id, now));
                anomalies.clear();
                if (simId != null) {
                    simulationRepository.finishRecord(simId, processedOrders);
                }
                log.info("仿真停止 simId={}，完成订单 {} 张", simId, processedOrders);
            }
            return buildStatus();
        }
    }

    /** 复位：停止仿真、解除未决交通异常，并把所有车辆状态置为空闲 */
    public SimulationStatus reset() {
        synchronized (lock) {
            if (running) {
                running = false;
                if (simId != null) {
                    simulationRepository.finishRecord(simId, processedOrders);
                }
            }
            // 无论是否在运行，都把库里残留的未决异常解除（停止时同样处理，保持两接口一致）
            LocalDateTime now = LocalDateTime.now();
            anomalies.forEach(a -> routeRepository.resolveAnomaly(a.id, now));
            anomalies.clear();
            // 清理"车空闲、任务未完结"的脏数据：取消在途调度单并把对应订单放回待处理池
            int cancelled = dispatchRepository.cancelOpenTasks();
            int reopened = orderRepository.reopenOpenOrders();
            log.info("仿真复位：取消未完成调度 {} 条，重开订单 {} 张", cancelled, reopened);
            vehicleRepository.markAllIdle();
            return buildStatus();
        }
    }

    /* ============================================================
     * 节拍调度（@EnableScheduling 已在启动类开启）
     * ============================================================ */

    /** 固定延迟触发；非运行态直接跳过，避免空转 */
    @Scheduled(fixedDelayString = "${transport.sim.tick-ms:2000}")
    public void scheduledTick() {
        if (!running) {
            return;
        }
        try {
            tickOnce();
        } catch (Exception e) {
            // 单车异常不应导致整个调度线程终止
            log.error("仿真节拍执行异常", e);
        }
    }

    /** 推进一个仿真节拍：异常生命周期 -> 车辆决策/运动 -> 状态与轨迹落库 */
    private void tickOnce() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            tick++;
            LocalDateTime now = LocalDateTime.now();

            // 货源补给：每 6 个节拍检查一次待处理订单数量
            if (autoOrders && tick % 6 == 0) {
                orderGenerator.ensureOrders(3, 4);
            }

            anomalyLifecycle(now);

            // 本节拍的匹配规则（分类 -> 车型集合 / 优选标记），所有车辆共用，避免重复查库
            Map<Integer, Set<Integer>> compatTypes = new HashMap<>();
            Map<Integer, Map<Integer, Boolean>> preferred = new HashMap<>();
            for (MasterDataRepository.MatchRule rule : masterDataRepository.findMatchRules()) {
                compatTypes.computeIfAbsent(rule.categoryId(), k -> new HashSet<>()).add(rule.vehicleTypeId());
                preferred.computeIfAbsent(rule.categoryId(), k -> new HashMap<>())
                        .put(rule.vehicleTypeId(), rule.preferred());
            }

            List<PendingOrder> pendingOrders = orderRepository.findPendingOrders();
            Set<Integer> claimedOrders = new HashSet<>(); // 本节拍已被某辆车抢走的订单
            // 库里仍有未完成调度的车辆（异常遗留的"幽灵任务"），空闲且未接续任务者跳过决策，
            // 否则派车服务会一车多单校验失败并每拍刷一条错误日志
            Set<Integer> busyVehicles = dispatchRepository.findBusyVehicleIds();

            for (Agent agent : agents) {
                agent.movedThisTick = false;
                agent.speed = 0;
                try {
                    if ("IDLE".equals(agent.status) && agent.orderId == null
                            && busyVehicles.contains(agent.vehicleId)) {
                        log.warn("车辆 {} 存在未接续的在途调度，跳过抢单（建议复位后重新启动）", agent.plate);
                    } else {
                        stepAgent(agent, pendingOrders, claimedOrders, compatTypes, preferred, now);
                    }
                } catch (Exception e) {
                    log.error("车辆 {} 状态推进失败", agent.plate, e);
                }
                // 车辆实时状态落库（在途时 current_poi_id 置空）
                double estRange = agent.fuelConsumption > 0
                        ? agent.fuel / (agent.fuelConsumption / 100.0) : 0;
                vehicleRepository.updateSimState(agent.vehicleId, agent.status,
                        agent.trip != null ? null : agent.poiId,
                        round2(agent.fuel), round1(estRange), round1(agent.totalMileage));
                // 行驶轨迹落 gps_data（sim_id 非空，标记为仿真数据）
                if (agent.movedThisTick && simId != null && tick % gpsEveryNTicks == 0) {
                    gpsRepository.insert(agent.vehicleId, agent.pos.lng(), agent.pos.lat(),
                            round1(agent.speed), round1(agent.heading), simId, now);
                }
            }
        }
    }

    /* ============================================================
     * 各状态的节拍处理（状态转换规则表的具体落地）
     * ============================================================ */

    /** 空闲态决策：加油 > 保养 > 找货接单（优先级体现司机/车辆行为习惯） */
    private void stepAgent(Agent a, List<PendingOrder> pendingOrders, Set<Integer> claimed,
                           Map<Integer, Set<Integer>> compatTypes,
                           Map<Integer, Map<Integer, Boolean>> preferred, LocalDateTime now) {
        switch (a.status) {
            case "IDLE" -> decideIdle(a, pendingOrders, claimed, compatTypes, preferred, now);
            case "LOADING" -> loadingTick(a, now);
            case "TRANSPORT" -> transportTick(a, now);
            case "UNLOADING" -> unloadingTick(a, now);
            case "REFUEL" -> refuelTick(a, now);
            case "MAINTAIN" -> maintainTick(a, now);
            default -> { /* 未知状态不做处理 */ }
        }
    }

    /** 空闲车辆的决策逻辑 */
    private void decideIdle(Agent a, List<PendingOrder> pendingOrders, Set<Integer> claimed,
                            Map<Integer, Set<Integer>> compatTypes,
                            Map<Integer, Map<Integer, Boolean>> preferred, LocalDateTime now) {
        // 1) 油量不足：去最近的加油站（规则表允许 IDLE -> REFUEL）
        if (a.fuel < LOW_FUEL) {
            Integer stationId = nearestPoi(a.pos, 3);
            if (stationId != null && changeStatus(a, "REFUEL", now, "油量不足，前往加油站")) {
                PoiSummary station = poiCache.get(stationId);
                beginTrip(a, new GisService.Point(station.longitude(), station.latitude()), stationId, 0);
                return;
            }
        }
        // 2) 小概率例行保养（IDLE -> MAINTAIN）
        if (random.nextDouble() < MAINTAIN_PROBABILITY
                && changeStatus(a, "MAINTAIN", now, "例行保养")) {
            a.dwell = 3;
            return;
        }
        // 3) 找货：在可装车型、载重容积满足的待处理订单中选综合得分最高者
        PendingOrder best = null;
        double bestScore = -1;
        for (PendingOrder order : pendingOrders) {
            if (claimed.contains(order.orderId())) {
                continue;
            }
            if (!compatTypes.getOrDefault(order.categoryId(), Set.of()).contains(a.typeId)) {
                continue; // 货物分类与车型不匹配
            }
            // cargo.weight/volume 是单件重量与体积，须用整单总量比较（totalWeight/totalVolume 已乘数量）
            if (a.maxLoad < order.totalWeight() || a.maxVolume < order.totalVolume()) {
                continue; // 载重或容积不足
            }
            GisService.Point originPoint = pointOf(order.originPoiId());
            if (originPoint == null) {
                continue;
            }
            double distance = gisService.distanceKm(a.pos, originPoint);
            double score = 30 * Math.exp(-distance / 25.0); // 离发货地越近分越高
            // 订单紧急度：priority 1(高)/2(中)/3(低) -> +30/+15/+0。
            // 缺了这一项时 priority 完全不参与决策（上面 SQL 的 ORDER BY priority 会被
            // "取最高分"的循环逻辑抹掉），加急订单与普通订单没有区别。
            score += (3 - order.priority()) * 15;
            if (Boolean.TRUE.equals(preferred.getOrDefault(order.categoryId(), Map.of()).get(a.typeId))) {
                score += 25; // 优选车型
            } else {
                score += 10;
            }
            if (order.routeId() != null && a.favoriteRoutes.contains(order.routeId())) {
                score += 15; // 司机常跑路线偏好
            }
            if (score > bestScore) {
                bestScore = score;
                best = order;
            }
        }
        if (best == null) {
            return; // 没有合适货源，继续空闲待命
        }
        claimed.add(best.orderId());
        // 调用统一的派车服务（事务化写 dispatch + 更新订单），仿真与人工派车规则完全一致
        RouteInfo route = dispatchService.assign(best.orderId(), a.vehicleId);
        a.orderId = best.orderId();
        a.orderNo = best.orderNo();
        a.destPoiId = best.destPoiId();
        a.originName = best.originName();
        a.destName = best.destName();
        changeStatus(a, "LOADING", now, "匹配到订单 " + a.orderNo);
        if (Integer.valueOf(best.originPoiId()).equals(a.poiId)) {
            a.dwell = 2; // 已在发货地，直接装车
        } else {
            beginTrip(a, pointOf(best.originPoiId()), best.originPoiId(), route.routeId());
        }
    }

    /** 装载态：先空驶到发货地，到达后停留 2 个节拍装车，随后转入运输 */
    private void loadingTick(Agent a, LocalDateTime now) {
        if (a.trip != null) {
            advance(a, now);
            if (a.trip == null) {
                a.dwell = 2; // 到达发货地，开始装车
            }
            return;
        }
        if (a.dwell > 0) {
            a.dwell--;
            return;
        }
        // 装车完成：解析发货地->收货地路线后正式发车
        PoiSummary origin = poiCache.get(a.poiId);
        PoiSummary dest = a.destPoiId == null ? null : poiCache.get(a.destPoiId);
        if (origin == null || dest == null) {
            changeStatus(a, "IDLE", now, "起点/终点数据缺失，回空闲");
            // Integer 与 String 字段不能链式赋 null（右侧表达式会被推断为 String），分开置空
            a.orderId = null;
            a.orderNo = null;
            return;
        }
        RouteInfo route = gisService.resolveRoute(origin, dest);
        a.originName = origin.poiName();
        a.destName = dest.poiName();
        changeStatus(a, "TRANSPORT", now, "装车完成，发车 " + a.orderNo);
        orderRepository.updateStatus(a.orderId, "TRANSPORTING", null);
        dispatchRepository.markInTransit(a.orderId);
        beginTrip(a, new GisService.Point(dest.longitude(), dest.latitude()), dest.poiId(), route.routeId());
    }

    /** 运输态：沿路线前进，到达后卸货并完结订单 */
    private void transportTick(Agent a, LocalDateTime now) {
        if (a.trip == null) {
            return;
        }
        advance(a, now);
        if (a.trip == null) {
            // 到达收货地：UNLOADING + 订单送达 + 调度完成
            changeStatus(a, "UNLOADING", now, "到达 " + a.destName + "，开始卸货");
            a.dwell = 2;
            orderRepository.updateStatus(a.orderId, "DELIVERED", now);
            dispatchRepository.completeByOrder(a.orderId, now);
            processedOrders++;
        }
    }

    /** 卸货态：停留 2 个节拍后回空闲，可立即接下一单（循环运行的关键） */
    private void unloadingTick(Agent a, LocalDateTime now) {
        if (a.dwell > 0) {
            a.dwell--;
            if (a.dwell == 0) {
                changeStatus(a, "IDLE", now, "卸货完成，等待新任务");
                a.orderId = null;
                a.orderNo = null;
                a.destPoiId = null;
                a.originName = null;
                a.destName = null;
                a.progress = 0;
            }
        }
    }

    /** 加油态：先行驶到加油站，停留加油后油量补满，回空闲 */
    private void refuelTick(Agent a, LocalDateTime now) {
        if (a.trip != null) {
            advance(a, now);
            if (a.trip == null) {
                a.dwell = 2;
            }
            return;
        }
        if (a.dwell > 0) {
            a.dwell--;
            if (a.dwell == 0) {
                a.fuel = FULL_FUEL;
                changeStatus(a, "IDLE", now, "加油完成，油量补至 " + (int) FULL_FUEL + "L");
            }
        } else {
            changeStatus(a, "IDLE", now, "加油流程结束");
        }
    }

    /** 保养态：停留 3 个节拍后回空闲 */
    private void maintainTick(Agent a, LocalDateTime now) {
        if (a.dwell > 0) {
            a.dwell--;
            if (a.dwell == 0) {
                changeStatus(a, "IDLE", now, "保养完成，恢复运营");
            }
        }
    }

    /* ============================================================
     * 运动学与交通异常
     * ============================================================ */

    /** 开始一段行程：记录起终点、距离；具体位置在 advance 中按节拍插值 */
    private void beginTrip(Agent a, GisService.Point target, int toPoiId, int routeId) {
        double distance = Math.max(0.3, gisService.distanceKm(a.pos, target));
        a.trip = new Trip(a.pos, target, a.poiId == null ? -1 : a.poiId, toPoiId, routeId, distance);
        a.progress = 0;
    }

    /**
     * 车辆沿当前行程前进一个节拍：
     * 行驶里程 = 车型平均速度 × 异常系数 × (节拍分钟/60)；
     * 同时扣减油量、累计里程、插值经纬度、计算朝向；里程走完即到达。
     */
    private void advance(Agent a, LocalDateTime now) {
        Trip trip = a.trip;
        maybeSpawnAnomaly(a, now);

        double factor = trip.anomalyId == null ? 1.0 : trip.factor;
        double km = a.avgSpeed * factor * (minutesPerTick / 60.0);
        a.speed = round1(a.avgSpeed * factor);
        GisService.Point before = a.pos;

        trip.traveledKm += km;
        a.fuel = Math.max(0, a.fuel - km * a.fuelConsumption / 100.0);
        a.totalMileage += km;
        a.movedThisTick = true;

        double ratio = Math.min(1.0, trip.traveledKm / trip.distanceKm);
        a.pos = gisService.interpolate(trip.from, trip.to, ratio);
        a.heading = gisService.bearing(before, a.pos);
        a.progress = ratio;

        if (ratio >= 1.0) {
            a.pos = trip.to;
            a.poiId = trip.toPoi;
            a.progress = 1.0;
            a.trip = null;
            a.speed = 0;
        }
    }

    /** 行驶中按概率产生交通异常，异常车辆立即减速，异常在异常生命周期中按时解除 */
    private void maybeSpawnAnomaly(Agent a, LocalDateTime now) {
        Trip trip = a.trip;
        if (trip.anomalyId != null || random.nextDouble() >= ANOMALY_PROBABILITY) {
            return;
        }
        // 异常类型：拥堵 50% / 事故 20% / 施工 20% / 天气 10%
        double r = random.nextDouble();
        String type = r < 0.5 ? "CONGESTION" : r < 0.7 ? "ACCIDENT" : r < 0.9 ? "CONSTRUCTION" : "WEATHER";
        int severity = 1 + random.nextInt(3);
        String desc = "车辆 " + a.plate + " 途中遭遇"
                + (type.equals("CONGESTION") ? "拥堵" : type.equals("ACCIDENT") ? "交通事故"
                : type.equals("CONSTRUCTION") ? "道路施工" : "恶劣天气");
        Integer nearPoi = nearestPoi(a.pos, null);
        int anomalyId = routeRepository.insertAnomaly(trip.routeId == 0 ? null : trip.routeId,
                nearPoi, type, severity, desc, now);
        double factor = 0.35 + random.nextDouble() * 0.35; // 异常时只能跑正常速度的 35%-70%
        trip.anomalyId = anomalyId;
        trip.factor = factor;
        anomalies.add(new AnomalyRuntime(anomalyId, 2 + random.nextInt(3), factor));
        log.info("交通异常 #{} {}（严重度 {}，车辆 {} 限速至 {}）", anomalyId, type, severity,
                a.plate, Math.round(factor * 100) + "%");
    }

    /** 异常生命周期：剩余节拍归零后解除，并恢复受影响车辆的速度 */
    private void anomalyLifecycle(LocalDateTime now) {
        Iterator<AnomalyRuntime> it = anomalies.iterator();
        while (it.hasNext()) {
            AnomalyRuntime anomaly = it.next();
            anomaly.remainingTicks--;
            if (anomaly.remainingTicks <= 0) {
                routeRepository.resolveAnomaly(anomaly.id, now);
                for (Agent a : agents) {
                    // 注意 anomalyId 为 Integer 可空，必须先判空再比较，否则 null 自动拆箱会抛 NPE
                    if (a.trip != null && Integer.valueOf(anomaly.id).equals(a.trip.anomalyId)) {
                        a.trip.anomalyId = null;
                        a.trip.factor = 1.0;
                    }
                }
                it.remove();
                log.info("交通异常 #{} 已解除", anomaly.id);
            }
        }
    }

    /** 状态跳转：先查"状态转换规则表"是否允许，允许才跳转并写 vehicle_status_log */
    private boolean changeStatus(Agent a, String to, LocalDateTime now, String remark) {
        Set<String> allowed = allowedNext.getOrDefault(a.status, Set.of());
        if (!allowed.contains(to)) {
            log.warn("状态转换规则表不允许 {}（{}） -> {}，已拦截（订单 {}）",
                    a.status, a.plate, to, a.orderNo);
            return false;
        }
        Integer fromId = statusIds.get(a.status);
        Integer toId = statusIds.get(to);
        if (toId != null) {
            simulationRepository.insertStatusLog(a.vehicleId, fromId, toId, a.poiId, now, remark);
        }
        a.status = to;
        return true;
    }

    /** 找距离某点最近的 POI；categoryId 非空时限分类（如找加油站传 3） */
    private Integer nearestPoi(GisService.Point p, Integer categoryId) {
        Integer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PoiSummary poi : poiCache.values()) {
            if (categoryId != null && !categoryId.equals(poi.categoryId())) {
                continue;
            }
            double d = gisService.distanceKm(p, new GisService.Point(poi.longitude(), poi.latitude()));
            if (d < bestDistance) {
                bestDistance = d;
                best = poi.poiId();
            }
        }
        return best;
    }

    /** 安全取 POI 坐标（POI 不存在返回 null） */
    private GisService.Point pointOf(Integer poiId) {
        PoiSummary p = poiId == null ? null : poiCache.get(poiId);
        return p == null ? null : new GisService.Point(p.longitude(), p.latitude());
    }

    /* ============================================================
     * 给控制器提供的只读快照
     * ============================================================ */

    public boolean isRunning() {
        return running;
    }

    /** 仿真状态摘要（运行中取内存计数，停止后取数据库实时统计） */
    public SimulationStatus buildStatus() {
        synchronized (lock) {
            Map<String, Integer> counts = new HashMap<>();
            int vehicleTotal;
            if (running) {
                vehicleTotal = agents.size();
                for (Agent a : agents) {
                    counts.merge(a.status, 1, Integer::sum);
                }
            } else {
                List<SimVehicle> vehicles = vehicleRepository.findSimVehicles();
                vehicleTotal = vehicles.size();
                for (SimVehicle v : vehicles) {
                    counts.merge(v.status(), 1, Integer::sum);
                }
            }
            int activeAnomaly = running ? anomalies.size()
                    : routeRepository.findActiveAnomalies().size();
            return new SimulationStatus(running, simId, running ? "多车循环仿真" : "仿真未运行",
                    tick, vehicleTotal, counts, processedOrders,
                    (int) orderRepository.countPending(), activeAnomaly, simStart);
        }
    }

    /** 沙盘完整快照：状态 + 车辆实时位置 + 交通异常 */
    public SimulationSnapshot snapshot() {
        synchronized (lock) {
            List<VehiclePosition> positions;
            if (running) {
                positions = agents.stream().map(this::toPosition).toList();
            } else {
                // 未启动仿真时，直接展示数据库中各车辆所在 POI 的静态位置
                positions = vehicleRepository.findSimVehicles().stream()
                        .map(v -> new VehiclePosition(v.vehicleId(), v.plate(), v.typeName(), v.driverName(),
                                v.status(), v.lng(), v.lat(), 0.0, 0.0, v.fuel(),
                                null, null, null, 0.0))
                        .toList();
            }
            return new SimulationSnapshot(buildStatus(), positions, routeRepository.findActiveAnomalies());
        }
    }

    /** Agent 内存对象 -> 前端车辆位置 DTO */
    private VehiclePosition toPosition(Agent a) {
        return new VehiclePosition(a.vehicleId, a.plate, a.typeName, a.driverName,
                a.status, round6(a.pos.lng()), round6(a.pos.lat()), round1(a.speed),
                round1(a.heading), round2(a.fuel), a.orderNo, a.originName, a.destName,
                Math.round(a.progress * 100.0) / 100.0);
    }

    /** 最近状态变更日志（前端时间线） */
    public List<StatusLogView> recentLogs(int limit) {
        return simulationRepository.findRecentLogs(limit);
    }

    /* ---------------- 数值工具 ---------------- */
    private double round1(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    private double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private double round6(double d) {
        return Math.round(d * 1_000_000.0) / 1_000_000.0;
    }

    /* ============================================================
     * 内部值对象：车辆代理 / 行程 / 运行时异常
     * ============================================================ */

    /** 车辆代理：保存仿真期间的全部可变状态（每节拍更新） */
    private static class Agent {
        final int vehicleId;
        final Integer driverId;
        final int typeId;
        final String plate;
        final String typeName;
        final String driverName;
        final double maxLoad;
        final double maxVolume;
        final double avgSpeed;
        final double fuelConsumption;
        final Set<Integer> favoriteRoutes;

        String status;
        GisService.Point pos;
        Integer poiId;
        double fuel;
        double totalMileage;

        int dwell;              // 静止操作（装车/卸货/加油/保养）剩余节拍
        Trip trip;              // 非空=正在路上
        Integer orderId;
        String orderNo;
        Integer destPoiId;
        String originName;
        String destName;

        double speed;
        double heading;
        double progress;
        boolean movedThisTick;

        Agent(SimVehicle sv) {
            this.vehicleId = sv.vehicleId();
            this.driverId = sv.driverId();
            this.typeId = sv.typeId();
            this.plate = sv.plate();
            this.typeName = sv.typeName();
            this.driverName = sv.driverName();
            this.maxLoad = sv.maxLoad();
            this.maxVolume = sv.maxVolume();
            this.avgSpeed = sv.avgSpeed();
            this.fuelConsumption = sv.fuelConsumption();
            this.favoriteRoutes = sv.favoriteRoutes();
            this.status = sv.status();
            this.poiId = sv.poiId();
            this.fuel = sv.fuel();
            this.totalMileage = sv.totalMileage();
        }
    }

    /** 一段行程：起终点、距离与已行驶里程、异常影响 */
    private static class Trip {
        final GisService.Point from;
        final GisService.Point to;
        final int fromPoi;
        final int toPoi;
        final int routeId;
        final double distanceKm;
        double traveledKm;
        Integer anomalyId;
        double factor = 1.0;

        Trip(GisService.Point from, GisService.Point to, int fromPoi, int toPoi, int routeId, double distanceKm) {
            this.from = from;
            this.to = to;
            this.fromPoi = fromPoi;
            this.toPoi = toPoi;
            this.routeId = routeId;
            this.distanceKm = distanceKm;
        }
    }

    /** 运行时交通异常（数据库记录 + 剩余持续节拍 + 限速系数） */
    private static class AnomalyRuntime {
        final int id;
        int remainingTicks;
        final double factor;

        AnomalyRuntime(int id, int remainingTicks, double factor) {
            this.id = id;
            this.remainingTicks = remainingTicks;
            this.factor = factor;
        }
    }
}
