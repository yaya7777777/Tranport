package com.example.transport.service;

import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.RouteInfo;
import com.example.transport.repository.PoiRepository;
import com.example.transport.repository.RouteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * GIS 地理信息服务（任务书第 3 项："线路获取、交通环境数据获取的 API"）。
 * <p>
 * 职责：
 * 1) 两点间球面距离（Haversine 公式）与方位角计算；
 * 2) 路线解析：优先取 route 表人工数据，缺失时本地估算并回写；
 * 3) 配置高德 Web 服务 Key 后，自动改用高德驾车路径规划的真实里程/耗时（失败自动降级）。
 * </p>
 */
@Service
public class GisService {

    private static final Logger log = LoggerFactory.getLogger(GisService.class);

    /** 无真实 GIS 数据时的路网平均时速（km/h），用于把直线距离换算成预计耗时 */
    private static final double FALLBACK_AVG_KMH = 50.0;
    /** 地球平均半径（km） */
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final RouteRepository routeRepository;
    private final PoiRepository poiRepository;

    /** 高德 Web 服务 Key，application.properties 中配置；为空则只用本地估算 */
    @Value("${transport.amap.key:}")
    private String amapKey;

    /* ---------- 货车路径规划档案（v4/direction/truck，物流收费接口，需单独开通权限） ---------- */
    /** 是否启用货车路径规划；false 或接口不可用（未开通权限）时自动降级为驾车规划 */
    @Value("${transport.amap.truck.enabled:false}")
    private boolean truckEnabled;
    /** 车辆大小：1微型 / 2轻型 / 3中型 / 4重型 */
    @Value("${transport.amap.truck.size:4}")
    private int truckSize;
    /** 车辆高度(米)，用于限高规避 */
    @Value("${transport.amap.truck.height:4.0}")
    private double truckHeight;
    /** 车辆宽度(米)，用于限宽规避 */
    @Value("${transport.amap.truck.width:2.55}")
    private double truckWidth;
    /** 车辆总重(吨) = 核定载重 + 自重，用于限重规避 */
    @Value("${transport.amap.truck.load:31}")
    private double truckLoad;
    /** 核定载重(吨) */
    @Value("${transport.amap.truck.weight:18}")
    private double truckWeight;
    /** 轴数，影响通行费计算 */
    @Value("${transport.amap.truck.axis:4}")
    private int truckAxis;
    /** 规划策略：10=无路况速度优先（结果稳定，适合仿真）；11=高德推荐（含实时路况） */
    @Value("${transport.amap.truck.strategy:10}")
    private int truckStrategy;

    /** 货车接口本次运行内熔断：一旦调用失败（如无权限 errcode=10012）不再重试，直接用驾车规划 */
    private volatile boolean truckDown = false;

    /** Jackson 解析器：货车接口响应含 steps 与 tmcs 两级 polyline，必须按 JSON 树精确取 steps 级 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** JDK 内置 HTTP 客户端（用于调用高德 REST API，无需引入额外依赖） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public GisService(RouteRepository routeRepository, PoiRepository poiRepository) {
        this.routeRepository = routeRepository;
        this.poiRepository = poiRepository;
    }

    /** 经纬度点（不可变值对象） */
    public record Point(double lng, double lat) {
    }

    /**
     * Haversine 公式计算两点间大圆距离（km）。
     * 仿真中车辆每节拍的行驶里程、匹配时"车辆到发货地距离"都使用该方法。
     */
    public double distanceKm(Point a, Point b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    /**
     * 从 a 点指向 b 点的初始方位角（0-360 度，正北为 0，顺时针），用于车辆图标朝向。
     */
    public double bearing(Point a, Point b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /** 按比例 t∈[0,1] 在 a、b 两点之间做线性插值（经纬度分别线性，市域尺度误差可忽略） */
    public Point interpolate(Point a, Point b, double t) {
        double tt = Math.max(0.0, Math.min(1.0, t));
        return new Point(a.lng() + (b.lng() - a.lng()) * tt,
                a.lat() + (b.lat() - a.lat()) * tt);
    }

    /**
     * 解析两 POI 间的运输路线：
     * 先查 route 表；查不到则（高德 API 或本地直线估算）计算后回写，下次直接复用。
     */
    public RouteInfo resolveRoute(PoiSummary origin, PoiSummary dest) {
        if (origin == null || dest == null) {
            throw new IllegalArgumentException("路线起点或终点不存在");
        }
        RouteInfo existing = routeRepository.findRoute(origin.poiId(), dest.poiId());
        if (existing != null) {
            return existing;
        }

        // 本地兜底：Haversine 直线距离 × 1.3 系数模拟道路绕行
        double straight = distanceKm(new Point(origin.longitude(), origin.latitude()),
                new Point(dest.longitude(), dest.latitude()));
        double distance = Math.max(0.5, straight * 1.3);
        int etaMinutes = (int) Math.max(1, Math.round(distance / FALLBACK_AVG_KMH * 60));

        // 配置了高德 Key 时尝试用真实路径规划结果覆盖本地估算（货车优先，失败降级驾车）
        String polyline = null;
        String polylineSrc = null;
        if (amapKey != null && !amapKey.isBlank()) {
            try {
                AmapRoute amap = planRoute(origin.longitude(), origin.latitude(),
                        dest.longitude(), dest.latitude());
                if (amap != null) {
                    distance = amap.distanceKm();
                    etaMinutes = (int) Math.max(1, Math.round(amap.durationSec() / 60.0));
                    polyline = amap.polyline();
                    polylineSrc = amap.src();
                }
            } catch (Exception e) {
                // 网络抖动/配额用尽都不应阻断仿真，降级为本地估算
                log.warn("高德路径规划调用失败，使用本地直线估算：{}", e.getMessage());
            }
        }

        String roadType = distance >= 30 ? "高速" : distance >= 10 ? "国道" : "城市道路";
        int difficulty = distance >= 40 ? 2 : 1;
        String routeName = origin.poiName() + "-" + dest.poiName();
        int routeId = routeRepository.insertRoute(origin.poiId(), dest.poiId(), routeName,
                Math.round(distance * 100.0) / 100.0, etaMinutes, roadType, difficulty, polyline, polylineSrc);
        log.info("GIS 自动生成路线 #{} {}（{} km / {} 分钟）{}", routeId, routeName,
                Math.round(distance * 10.0) / 10.0, etaMinutes,
                polyline != null ? "[含高德" + ("truck".equals(polylineSrc) ? "货车" : "驾车") + "真实路线]" : "");
        return new RouteInfo(routeId, origin.poiId(), dest.poiId(), routeName,
                Math.round(distance * 100.0) / 100.0, etaMinutes, roadType, difficulty, true);
    }

    /* ============================================================
     * 高德驾车路径规划：真实道路坐标（polyline）获取
     * ============================================================ */

    /**
     * 高德规划结果：距离 km、耗时秒、真实道路坐标串（GCJ-02, "lng,lat;lng,lat;..."）、
     * 来源标记（truck=货车规划 / car=驾车规划，用于 route 表 polyline_src 列与前端标签）。
     */
    record AmapRoute(double distanceKm, long durationSec, String polyline, String src) {
    }

    /**
     * 提供给前端的路线几何结果：沿真实道路的坐标点列表（GCJ-02 [lng,lat]）+ 里程/耗时 + 是否货车规划。
     * points 为空表示无法规划（未配 Key / 网络失败），前端降级为起终点直线。
     */
    public record RoutePath(List<double[]> points, double distanceKm, int etaMinutes, boolean truck) {
    }

    /** 任意两点路线内存缓存（键为 4 位小数坐标对），避免前端轮询重复调用高德浪费配额 */
    private final Map<String, RoutePath> routePathCache = new ConcurrentHashMap<>();

    /** 返回给前端的路线点数上限：跨市长途全量点可达上万，抽稀后减轻传输与绘制负担 */
    private static final int MAX_DISPLAY_POINTS = 3000;

    /**
     * 获取两点间沿真实道路的驾车路线（供前端沙盘绘制）。
     * <ul>
     *   <li>POI 对：优先读 route 表已持久化的 polyline；缺失时调高德补算并回写路线里程/耗时/坐标；</li>
     *   <li>任意点（如车辆空驶去取货位）：仅用内存缓存，坐标四舍五入到 4 位小数（约 11 米）合并重复请求。</li>
     * </ul>
     *
     * @return 规划失败返回 null，由调用方决定降级策略
     */
    public RoutePath routePath(Double fromLng, Double fromLat, Double toLng, Double toLat,
                               Integer originPoiId, Integer destPoiId) {
        if (fromLng == null || fromLat == null || toLng == null || toLat == null) {
            return null;
        }
        String key = round4(fromLng) + "," + round4(fromLat) + "->" + round4(toLng) + "," + round4(toLat);
        RoutePath cached = routePathCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (routePathCache.size() > 2000) {
            routePathCache.clear();   // 简单防膨胀：极端情况下清空重来
        }

        boolean poiPair = originPoiId != null && destPoiId != null;
        // 1) POI 对优先复用 route 表持久化的真实路线；
        //    货车接口可用时，旧的驾车(car)路线不再复用，落下去重新规划并升级为货车路线
        if (poiPair) {
            String stored = routeRepository.findPolyline(originPoiId, destPoiId);
            String storedSrc = routeRepository.findPolylineSrc(originPoiId, destPoiId);
            boolean needTruckUpgrade = truckEnabled && !truckDown && "car".equals(storedSrc);
            if (stored != null && !stored.isBlank() && !needTruckUpgrade) {
                RouteInfo ri = routeRepository.findRoute(originPoiId, destPoiId);
                RoutePath rp = new RoutePath(
                        decimate(decodePolyline(stored), MAX_DISPLAY_POINTS),
                        ri != null ? ri.distance() : 0, ri != null ? ri.estimatedTime() : 0,
                        "truck".equals(storedSrc));
                routePathCache.put(key, rp);
                return rp;
            }
        }

        // 2) 未配 Key 无法规划
        if (amapKey == null || amapKey.isBlank()) {
            return null;
        }

        // 3) 调高德规划：货车优先，失败自动降级驾车（失败/超时不抛出，返回 null 让前端降级为直线）
        try {
            AmapRoute amap = planRoute(fromLng, fromLat, toLng, toLat);
            if (amap == null || amap.polyline().isBlank()) {
                return null;
            }
            int etaMinutes = (int) Math.max(1, Math.round(amap.durationSec() / 60.0));
            RoutePath rp = new RoutePath(decimate(decodePolyline(amap.polyline()), MAX_DISPLAY_POINTS),
                    amap.distanceKm(), etaMinutes, "truck".equals(amap.src()));
            // POI 对把结果回写 route 表，重启/重跑直接复用，不再消耗配额
            if (poiPair) {
                routeRepository.updateRouteGeometry(originPoiId, destPoiId,
                        amap.distanceKm(), etaMinutes, amap.polyline(), amap.src());
            }
            routePathCache.put(key, rp);
            log.info("高德{}路线规划成功：{} km / {} 分钟 / {} 个路径点",
                    "truck".equals(amap.src()) ? "货车" : "驾车", amap.distanceKm(), etaMinutes, rp.points().size());
            return rp;
        } catch (Exception e) {
            log.warn("高德路线规划失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 统一规划入口：货车接口启用且未熔断时先试货车规划（v4/direction/truck，按车高/宽/重/轴规避限行），
     * 失败（如 Key 未开通物流接口权限 errcode=10012）则本次运行内熔断并降级为驾车规划（v3）。
     *
     * @return 规划结果（含来源标记 src）；两种接口都失败返回 null
     */
    private AmapRoute planRoute(double fromLng, double fromLat, double toLng, double toLat) throws Exception {
        if (truckEnabled && !truckDown) {
            try {
                AmapRoute truck = queryAmapTruckRoute(fromLng, fromLat, toLng, toLat);
                if (truck != null) {
                    return truck;
                }
                truckDown = true;
                log.warn("货车路径规划不可用（Key 可能未开通物流接口权限），本次运行降级为驾车规划，重启后重试");
            } catch (Exception e) {
                truckDown = true;
                log.warn("货车路径规划调用异常，本次运行降级为驾车规划：{}", e.getMessage());
            }
        }
        return queryAmapRoute(fromLng, fromLat, toLng, toLat);
    }

    /**
     * 调用高德货车路径规划 API（v4/direction/truck）。
     * 文档：https://lbs.amap.com/api/logistic-service/guide/wagon_path/truck-route-plan-basis
     * <p>
     * 按配置的车高/宽/总重/载重/轴数规避物理限行与路牌限制；响应中 steps 与 tmcs 两级
     * 都有 polyline，必须用 JSON 树只取 steps 级（tmcs 是 steps 的路况子分段，混入会重复）。
     *
     * @return 距离 km、耗时秒、道路坐标串（src=truck）；errcode!=0 或无路径时返回 null
     */
    private AmapRoute queryAmapTruckRoute(double fromLng, double fromLat, double toLng, double toLat) throws Exception {
        String url = "https://restapi.amap.com/v4/direction/truck?origin="
                + URLEncoder.encode(round6(fromLng) + "," + round6(fromLat), StandardCharsets.UTF_8)
                + "&destination=" + URLEncoder.encode(round6(toLng) + "," + round6(toLat), StandardCharsets.UTF_8)
                + "&size=" + truckSize
                + "&height=" + truckHeight
                + "&width=" + truckWidth
                + "&load=" + truckLoad
                + "&weight=" + truckWeight
                + "&axis=" + truckAxis
                + "&strategy=" + truckStrategy
                + "&key=" + amapKey;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(body);
        if (root.path("errcode").asInt(-1) != 0) {
            log.warn("货车规划返回错误：errcode={} errmsg={}",
                    root.path("errcode").asInt(-1), root.path("errmsg").asText());
            return null;
        }
        JsonNode paths = root.path("data").path("route").path("paths");
        if (!paths.isArray() || paths.isEmpty()) {
            return null;
        }
        JsonNode path0 = paths.get(0);
        long meters = (long) path0.path("distance").asDouble(0);
        long seconds = (long) path0.path("duration").asDouble(0);
        if (meters <= 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode step : path0.path("steps")) {
            String pl = step.path("polyline").asText("");
            if (!pl.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(pl);
            }
        }
        return sb.length() == 0 ? null : new AmapRoute(meters / 1000.0, seconds, sb.toString(), "truck");
    }

    /**
     * 调用高德驾车路径规划 API（v3 direction/driving），作为货车规划不可用时的降级方案。
     * 文档：https://lbs.amap.com/api/webservice/guide/api/direction
     * <p>
     * 坐标系：入参/返回均为 GCJ-02（与 poi 表一致，同源于高德爬虫），无需纠偏。
     *
     * @return 距离 km、耗时秒、真实道路坐标串；响应异常或 status!=1 时返回 null
     */
    private AmapRoute queryAmapRoute(double fromLng, double fromLat, double toLng, double toLat) throws Exception {
        String url = "https://restapi.amap.com/v3/direction/driving?origin="
                + URLEncoder.encode(round6(fromLng) + "," + round6(fromLat), StandardCharsets.UTF_8)
                + "&destination=" + URLEncoder.encode(round6(toLng) + "," + round6(toLat), StandardCharsets.UTF_8)
                + "&key=" + amapKey;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        if (body == null || !body.contains("\"status\":\"1\"")) {
            log.warn("高德驾车规划响应异常：{}", body == null ? "空" : body.substring(0, Math.min(200, body.length())));
            return null;
        }
        // 必须按 JSON 结构只取 paths[0].steps[].polyline：
        // 响应里每个 step 下还嵌套 tmcs（路况子段），每个 tmc 也有自己的 polyline 字段，
        // 若用正则全文扫描会把 step 主折线和 tmc 子段混着拼接，产生几十上百公里的直线跳变
        JsonNode root = objectMapper.readTree(body);
        JsonNode path0 = root.path("route").path("paths").path(0);
        long meters = (long) path0.path("distance").asDouble(0);
        long seconds = (long) path0.path("duration").asDouble(0);
        if (meters <= 0) {
            return null;
        }
        // 各 step 的 polyline 顺序拼接即完整道路路径（v3 默认返回单条 path）
        StringBuilder sb = new StringBuilder();
        for (JsonNode step : path0.path("steps")) {
            String pl = step.path("polyline").asText("");
            if (!pl.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(pl);
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        return new AmapRoute(meters / 1000.0, seconds, sb.toString(), "car");
    }

    /** 解析 "lng,lat;lng,lat;..." 坐标串为点列表（GCJ-02），并去除连续重复点 */
    private List<double[]> decodePolyline(String encoded) {
        List<double[]> pts = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return pts;
        }
        for (String seg : encoded.split(";")) {
            String[] c = seg.trim().split(",");
            if (c.length < 2) {
                continue;
            }
            try {
                double lng = Double.parseDouble(c[0]), lat = Double.parseDouble(c[1]);
                if (!pts.isEmpty()) {
                    double[] last = pts.get(pts.size() - 1);
                    if (last[0] == lng && last[1] == lat) {
                        continue;   // 去除相邻 step 拼接处的重复点
                    }
                }
                pts.add(new double[]{lng, lat});
            } catch (NumberFormatException ignore) {
                // 跳过脏数据段
            }
        }
        return pts;
    }

    private String round4(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    /** 点数超限时按等间隔抽稀（保留首尾点），仅影响展示精度，落库仍存全量坐标 */
    private List<double[]> decimate(List<double[]> pts, int max) {
        if (pts.size() <= max) {
            return pts;
        }
        int step = (int) Math.ceil(pts.size() / (double) max);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i += step) {
            out.add(pts.get(i));
        }
        double[] last = pts.get(pts.size() - 1);
        if (out.get(out.size() - 1) != last) {
            out.add(last);
        }
        return out;
    }

    private String round6(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
