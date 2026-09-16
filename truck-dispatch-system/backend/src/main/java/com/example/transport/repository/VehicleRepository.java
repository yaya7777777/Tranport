package com.example.transport.repository;

import com.example.transport.dto.VehicleSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 车辆持久层（vehicle 表 + 车型/司机/POI 关联查询）。
 * 列表页用 {@link #findAll()}；仿真引擎启动时用 {@link #findSimVehicles()} 装载车辆代理对象。
 */
@Repository
public class VehicleRepository {

    private final JdbcTemplate jdbc;

    public VehicleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 仿真车辆对象：包含状态推进所需的全部静态/动态字段。
     * 坐标在车辆位于路上（current_poi_id 为空）时为 null，由引擎自行插值。
     */
    public record SimVehicle(int vehicleId, String plate, int typeId, String typeName,
                             Integer driverId, String driverName,
                             double maxLoad, double maxVolume, double avgSpeed, double fuelConsumption,
                             Set<Integer> favoriteRoutes,
                             String status, Integer poiId, Double lng, Double lat,
                             double fuel, double totalMileage) {
    }

    /** 车辆管理页：车辆 + 车型 + 司机 + 当前站点 的联合查询 */
    public List<VehicleSummary> findAll() {
        String sql = "SELECT v.vehicle_id,v.plate_number,vt.type_name vehicle_type,d.name driver_name,p.poi_name current_location,"
                + "v.current_status,v.remaining_fuel,v.estimated_range,v.total_mileage FROM vehicle v "
                + "LEFT JOIN vehicle_type vt ON v.type_id=vt.type_id LEFT JOIN driver d ON v.driver_id=d.driver_id "
                + "LEFT JOIN poi p ON v.current_poi_id=p.poi_id ORDER BY v.vehicle_id";
        return jdbc.query(sql, (rs, n) -> new VehicleSummary(rs.getInt("vehicle_id"), rs.getString("plate_number"),
                rs.getString("vehicle_type"), rs.getString("driver_name"), rs.getString("current_location"),
                rs.getString("current_status"), rs.getObject("remaining_fuel", Double.class),
                rs.getObject("estimated_range", Double.class), rs.getObject("total_mileage", Double.class)));
    }

    /**
     * 装载全部启用车辆（含车型能力参数、司机偏好路线、当前坐标）。
     * fav_routes 用 MySQL GROUP_CONCAT 子查询一次性带出"偏好路线 ID，逗号分隔"，避免 N+1 查询。
     */
    public List<SimVehicle> findSimVehicles() {
        String sql = "SELECT v.vehicle_id, v.plate_number, v.type_id, vt.type_name, v.driver_id, d.name AS driver_name, "
                + "vt.max_load, vt.max_volume, vt.avg_speed, vt.fuel_consumption, "
                + "v.current_status, v.current_poi_id, p.longitude, p.latitude, "
                + "v.remaining_fuel, v.total_mileage, "
                + "(SELECT GROUP_CONCAT(route_id) FROM driver_route dr "
                + "  WHERE dr.driver_id = v.driver_id AND dr.is_favorite = 1) AS fav_routes "
                + "FROM vehicle v "
                + "JOIN vehicle_type vt ON v.type_id = vt.type_id "
                + "LEFT JOIN driver d ON v.driver_id = d.driver_id "
                + "LEFT JOIN poi p ON v.current_poi_id = p.poi_id "
                + "WHERE v.status = 1 ORDER BY v.vehicle_id";
        return jdbc.query(sql, (rs, n) -> new SimVehicle(
                rs.getInt("vehicle_id"),
                rs.getString("plate_number"),
                rs.getInt("type_id"),
                rs.getString("type_name"),
                (Integer) rs.getObject("driver_id"),
                rs.getString("driver_name"),
                rs.getDouble("max_load"),
                rs.getDouble("max_volume"),
                rs.getDouble("avg_speed"),
                rs.getDouble("fuel_consumption"),
                parseIdList(rs.getString("fav_routes")),
                rs.getString("current_status"),
                (Integer) rs.getObject("current_poi_id"),
                rs.getObject("longitude", Double.class),
                rs.getObject("latitude", Double.class),
                rs.getObject("remaining_fuel", Double.class) == null ? 0 : rs.getDouble("remaining_fuel"),
                rs.getObject("total_mileage", Double.class) == null ? 0 : rs.getDouble("total_mileage")));
    }

    /** 把 "1,8,12" 这样的字符串解析为路线 ID 集合 */
    private Set<Integer> parseIdList(String csv) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(s -> ids.add(Integer.parseInt(s)));
        }
        return ids;
    }

    /** 按车牌号查车辆 ID（北斗上报报文可能只带车牌），找不到返回 null */
    public Integer findIdByPlate(String plate) {
        List<Integer> ids = jdbc.queryForList(
                "SELECT vehicle_id FROM vehicle WHERE plate_number = ?", Integer.class, plate);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 仿真节拍后回写车辆实时状态（状态、所在站点、油量、可运行里程、累计里程）。
     * 车辆在途时 current_poi_id 写 NULL。
     */
    public void updateSimState(int vehicleId, String status, Integer poiId, double fuel,
                               double estimatedRange, double totalMileage) {
        jdbc.update("UPDATE vehicle SET current_status = ?, current_poi_id = ?, remaining_fuel = ?, "
                        + "estimated_range = ?, total_mileage = ? WHERE vehicle_id = ?",
                status, poiId, fuel, estimatedRange, totalMileage, vehicleId);
    }

    /** 一键复位：所有启用车辆回到空闲态（停止/重置仿真时使用） */
    public void markAllIdle() {
        jdbc.update("UPDATE vehicle SET current_status = 'IDLE' WHERE status = 1");
    }
}
