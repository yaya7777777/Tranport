package com.example.transport.service;

import com.example.transport.dto.PoiSummary;
import com.example.transport.dto.RouteInfo;
import com.example.transport.repository.PoiRepository;
import com.example.transport.repository.RouteRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // 配置了高德 Key 时尝试用真实驾车路径结果覆盖本地估算
        if (amapKey != null && !amapKey.isBlank()) {
            try {
                double[] amap = queryAmapDriving(origin, dest);
                if (amap != null) {
                    distance = amap[0];
                    etaMinutes = (int) Math.max(1, Math.round(amap[1] / 60.0)); // 高德返回秒
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
                Math.round(distance * 100.0) / 100.0, etaMinutes, roadType, difficulty);
        log.info("GIS 自动生成路线 #{} {}（{} km / {} 分钟）", routeId, routeName,
                Math.round(distance * 10.0) / 10.0, etaMinutes);
        return new RouteInfo(routeId, origin.poiId(), dest.poiId(), routeName,
                Math.round(distance * 100.0) / 100.0, etaMinutes, roadType, difficulty, true);
    }

    /**
     * 调用高德驾车路径规划 API。
     * 文档：https://lbs.amap.com/api/webservice/guide/api/direction
     *
     * @return [距离 km, 耗时 秒]；失败返回 null
     */
    private double[] queryAmapDriving(PoiSummary origin, PoiSummary dest) throws Exception {
        String originStr = origin.longitude() + "," + origin.latitude();
        String destStr = dest.longitude() + "," + dest.latitude();
        String url = "https://restapi.amap.com/v3/direction/driving?origin="
                + URLEncoder.encode(originStr, StandardCharsets.UTF_8)
                + "&destination=" + URLEncoder.encode(destStr, StandardCharsets.UTF_8)
                + "&key=" + amapKey;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        // 只取第一条 path 的 distance / duration，避免为简单解析再引入 JSON 依赖
        Matcher m = Pattern.compile("\"distance\"\\s*:\\s*\"?(\\d+)\"?.*?\"duration\"\\s*:\\s*\"?(\\d+)\"?",
                Pattern.DOTALL).matcher(body);
        if (m.find()) {
            long meters = Long.parseLong(m.group(1));
            long seconds = Long.parseLong(m.group(2));
            if (meters > 0) {
                return new double[]{meters / 1000.0, (double) seconds};
            }
        }
        return null;
    }
}
