package com.example.transport.service;

import com.example.transport.dto.GpsPoint;
import com.example.transport.dto.GpsReport;
import com.example.transport.repository.GpsRepository;
import com.example.transport.repository.VehicleRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * GPS/北斗定位接收服务（任务书第 1 项云端入口）。
 * 接收 ARM 上位机（tools/modbus_collector.py）以 HTTP+JSON 上报的经纬度，
 * 完成"车牌 -> 车辆 ID"映射、时间解析后落入 gps_data 表（sim_id 为空 = 真实设备数据）。
 */
@Service
public class GpsService {

    private final VehicleRepository vehicleRepository;
    private final GpsRepository gpsRepository;

    /** 支持两种常见时间格式：yyyy-MM-dd HH:mm:ss 与带 T 的 ISO 格式 */
    private static final DateTimeFormatter SPACE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public GpsService(VehicleRepository vehicleRepository, GpsRepository gpsRepository) {
        this.vehicleRepository = vehicleRepository;
        this.gpsRepository = gpsRepository;
    }

    /**
     * 接收并存储一条定位上报
     *
     * @throws IllegalArgumentException 车辆无法识别或经纬度缺失时抛出（由控制器转成失败响应）
     */
    public GpsPoint report(GpsReport report) {
        if (report.longitude() == null || report.latitude() == null) {
            throw new IllegalArgumentException("报文缺少经纬度字段");
        }
        // 车辆识别：优先 vehicleId，其次车牌号
        Integer vehicleId = report.vehicleId();
        if (vehicleId == null && report.vehicleCode() != null && !report.vehicleCode().isBlank()) {
            vehicleId = vehicleRepository.findIdByPlate(report.vehicleCode().trim());
        }
        if (vehicleId == null) {
            throw new IllegalArgumentException("无法识别车辆（vehicleId 或车牌号 vehicleCode 无效）");
        }

        // 时间解析：报文没带时间戳就用服务器当前时间
        LocalDateTime time = LocalDateTime.now();
        if (report.timestamp() != null && !report.timestamp().isBlank()) {
            String ts = report.timestamp().trim().replace("T", " ");
            if (ts.length() > 19) {
                ts = ts.substring(0, 19); // 截掉毫秒/时区部分，匹配 SPACE_FORMAT
            }
            time = LocalDateTime.parse(ts, SPACE_FORMAT);
        }

        long gpsId = gpsRepository.insert(vehicleId, report.longitude(), report.latitude(),
                report.speed(), report.heading(), null, time);
        String plate = report.vehicleCode();
        if (plate == null) {
            // 按 ID 上报时车牌号查一次，便于响应与前端展示；
            // vehicleId 在上面被重新赋值过，不是事实上终态，lambda 需引用一个 final 副本；
            // SimVehicle.vehicleId() 为基本类型 int，== 会自动拆箱比较（此处已判空）
            final int vid = vehicleId;
            plate = vehicleRepository.findSimVehicles().stream()
                    .filter(v -> v.vehicleId() == vid).map(VehicleRepository.SimVehicle::plate)
                    .findFirst().orElse(null);
        }
        return new GpsPoint(gpsId, vehicleId, plate, report.longitude(), report.latitude(),
                report.speed(), report.heading(), false, time.format(SPACE_FORMAT));
    }

    /** 最新定位数据（真实设备 + 仿真混合，按时间倒序） */
    public List<GpsPoint> latest(int limit) {
        return gpsRepository.findLatest(limit);
    }

    /** 某辆车的历史轨迹 */
    public List<GpsPoint> history(int vehicleId, int limit) {
        return gpsRepository.findHistory(vehicleId, limit);
    }
}
