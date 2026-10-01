package com.example.transport.repository;

import com.example.transport.dto.CargoCategoryView;
import com.example.transport.dto.CargoOption;
import com.example.transport.dto.VehicleTypeView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 调度基础数据持久层（任务书第 2 项："车型与装载能力"、"货物与适用车型"等基础数据）。
 * 集中管理：车型、货物分类、货物、车型匹配规则、车辆状态转换规则、
 * 司机常跑路线、厂仓关系（采购/生产/销售）。
 */
@Repository
public class MasterDataRepository {

    private final JdbcTemplate jdbc;

    public MasterDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ====================== 车型与货物主数据 ====================== */

    /** 查询全部车型（含装载能力与能装/不能装规则），按 ID 排序 */
    public List<VehicleTypeView> findVehicleTypes() {
        String sql = "SELECT type_id, type_name, max_load, max_volume, fuel_type, avg_speed, "
                + "fuel_consumption, can_carry, cannot_carry FROM vehicle_type ORDER BY type_id";
        return jdbc.query(sql, (rs, n) -> new VehicleTypeView(
                rs.getInt("type_id"),
                rs.getString("type_name"),
                rs.getObject("max_load", Double.class),
                rs.getObject("max_volume", Double.class),
                rs.getString("fuel_type"),
                rs.getObject("avg_speed", Double.class),
                rs.getObject("fuel_consumption", Double.class),
                rs.getString("can_carry"),
                rs.getString("cannot_carry")));
    }

    /** 查询全部货物分类 */
    public List<CargoCategoryView> findCargoCategories() {
        String sql = "SELECT category_id, category_name, storage_req FROM cargo_category ORDER BY category_id";
        return jdbc.query(sql, (rs, n) -> new CargoCategoryView(
                rs.getInt("category_id"),
                rs.getString("category_name"),
                rs.getString("storage_req")));
    }

    /** 查询全部货物（自动生成订单与匹配计算时使用） */
    public List<CargoOption> findCargos() {
        String sql = "SELECT cargo_id, cargo_name, category_id, weight, volume FROM cargo ORDER BY cargo_id";
        return jdbc.query(sql, (rs, n) -> new CargoOption(
                rs.getInt("cargo_id"),
                rs.getString("cargo_name"),
                rs.getInt("category_id"),
                rs.getObject("weight", Double.class),
                rs.getObject("volume", Double.class)));
    }

    /**
     * 查询货物分类-车型匹配规则
     *
     * @return 匹配规则列表（MatchRule 中 preferred=true 表示优选车型）
     */
    public List<MatchRule> findMatchRules() {
        String sql = "SELECT cargo_category_id, vehicle_type_id, is_preferred "
                + "FROM cargo_vehicle_type_match ORDER BY cargo_category_id, vehicle_type_id";
        return jdbc.query(sql, (rs, n) -> new MatchRule(
                rs.getInt("cargo_category_id"),
                rs.getInt("vehicle_type_id"),
                rs.getInt("is_preferred") == 1));
    }

    /** 匹配规则内部对象：某货物分类与某车型的适配关系 */
    public record MatchRule(int categoryId, int vehicleTypeId, boolean preferred) {
    }

    /* ====================== 车辆状态转换规则（vehicle_status 表） ====================== */

    /** 状态编码 -> 状态主键 ID（写 vehicle_status_log 时外键需要 ID） */
    public java.util.Map<String, Integer> statusIdMap() {
        return jdbc.query("SELECT status_id, status_code FROM vehicle_status",
                rs -> {
                    java.util.Map<String, Integer> map = new java.util.HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("status_code"), rs.getInt("status_id"));
                    }
                    return map;
                });
    }

    /**
     * 状态转换规则表：状态编码 -> 允许跳转的下一状态编码集合。
     * 仿真引擎每次状态跳转前都要校验该规则，体现"按状态转换规则表运行"。
     */
    public java.util.Map<String, Set<String>> allowedNextMap() {
        return jdbc.query("SELECT status_code, allowed_next FROM vehicle_status",
                rs -> {
                    java.util.Map<String, Set<String>> map = new java.util.HashMap<>();
                    while (rs.next()) {
                        String code = rs.getString("status_code");
                        String allowed = rs.getString("allowed_next");
                        Set<String> next = allowed == null || allowed.isBlank()
                                ? Set.of()
                                : Arrays.stream(allowed.split(",")).map(String::trim)
                                .filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
                        map.put(code, next);
                    }
                    return map;
                });
    }

    /* ====================== 司机偏好与厂仓关系 ====================== */

    /**
     * 司机常跑路线（司机行为习惯）：driverId -> 偏好路线 ID 集合。
     * 来源 driver_route 表；匹配算法命中常跑路线时给该车辆加分。
     */
    public java.util.Map<Integer, Set<Integer>> driverFavoriteRoutes() {
        return jdbc.query("SELECT driver_id, route_id FROM driver_route WHERE is_favorite = 1",
                rs -> {
                    java.util.Map<Integer, Set<Integer>> map = new java.util.HashMap<>();
                    while (rs.next()) {
                        map.computeIfAbsent(rs.getInt("driver_id"), k -> new LinkedHashSet<>())
                                .add(rs.getInt("route_id"));
                    }
                    return map;
                });
    }

    /**
     * 厂仓关系列表（采购/生产/销售），自动生成货源订单的依据。
     * JOIN 出工厂和仓库各自关联的 POI 坐标 ID，决定订单起止点。
     */
    public List<FactoryRelation> findFactoryRelations() {
        String sql = "SELECT fw.factory_id, fw.warehouse_id, fw.relation_type, "
                + "f.poi_id AS factory_poi_id, w.poi_id AS warehouse_poi_id "
                + "FROM factory_warehouse fw "
                + "JOIN factory f ON fw.factory_id = f.factory_id "
                + "JOIN warehouse w ON fw.warehouse_id = w.warehouse_id";
        return jdbc.query(sql, (rs, n) -> new FactoryRelation(
                rs.getInt("factory_id"),
                rs.getInt("warehouse_id"),
                rs.getString("relation_type"),
                rs.getInt("factory_poi_id"),
                rs.getInt("warehouse_poi_id")));
    }

    /** 厂仓关系内部对象 */
    public record FactoryRelation(int factoryId, int warehouseId, String relationType,
                                  int factoryPoiId, int warehousePoiId) {

        /**
         * 货物流向的起点 POI：采购(PROCURE) 是"仓库 -> 工厂"（原料流入工厂），
         * 生产(PRODUCE)/销售(SALE) 是"工厂 -> 仓库"。
         * 判断收敛在此处，人工建单与仿真自动建单共用，避免两处逻辑不一致。
         */
        public int originPoiId() {
            return "PROCURE".equals(relationType) ? warehousePoiId : factoryPoiId;
        }

        /** 货物流向的终点 POI，规则见 {@link #originPoiId()} */
        public int destPoiId() {
            return "PROCURE".equals(relationType) ? factoryPoiId : warehousePoiId;
        }
    }

    /* ====================== 主数据存在性校验（管理类写接口用） ====================== */

    private boolean exists(String table, String keyCol, int id) {
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + keyCol + " = ?", Long.class, id);
        return cnt != null && cnt > 0;
    }

    /** 工厂是否存在 */
    public boolean existsFactory(int factoryId) { return exists("factory", "factory_id", factoryId); }

    /** 仓库是否存在 */
    public boolean existsWarehouse(int warehouseId) { return exists("warehouse", "warehouse_id", warehouseId); }

    /** 货物是否存在 */
    public boolean existsCargo(int cargoId) { return exists("cargo", "cargo_id", cargoId); }

    /** 司机是否存在 */
    public boolean existsDriver(int driverId) { return exists("driver", "driver_id", driverId); }

    /**
     * 厂仓关系下拉数据（新增需求表单用）：带出工厂/仓库名称，前端选一条关系即确定起终点。
     */
    public List<FwOption> findFwOptions() {
        String sql = "SELECT fw.factory_id, f.factory_name, fw.warehouse_id, w.warehouse_name, fw.relation_type "
                + "FROM factory_warehouse fw "
                + "JOIN factory f ON fw.factory_id = f.factory_id "
                + "JOIN warehouse w ON fw.warehouse_id = w.warehouse_id "
                + "ORDER BY fw.fw_id";
        return jdbc.query(sql, (rs, n) -> new FwOption(
                rs.getInt("factory_id"), rs.getString("factory_name"),
                rs.getInt("warehouse_id"), rs.getString("warehouse_name"),
                rs.getString("relation_type")));
    }

    /** 厂仓关系下拉选项 */
    public record FwOption(int factoryId, String factoryName, int warehouseId,
                           String warehouseName, String relationType) {
    }

    /** 在职司机下拉数据（新增车辆表单用） */
    public List<DriverRow> findDrivers() {
        String sql = "SELECT driver_id, name, license_type FROM driver WHERE status = 1 ORDER BY driver_id";
        return jdbc.query(sql, (rs, n) -> new DriverRow(
                rs.getInt("driver_id"), rs.getString("name"), rs.getString("license_type")));
    }

    /** 司机下拉选项 */
    public record DriverRow(int driverId, String name, String licenseType) {
    }
}
