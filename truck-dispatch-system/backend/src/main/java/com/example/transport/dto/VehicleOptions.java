package com.example.transport.dto;

import java.util.List;

/**
 * 新增车辆/设置位置表单的下拉数据：车型、司机、POI 站点
 *
 * @param types   车型列表
 * @param drivers 司机列表（可分配为空）
 * @param pois    POI 站点列表（初始位置/设置位置用）
 */
public record VehicleOptions(List<VehicleTypeView> types, List<DriverOption> drivers, List<PoiSummary> pois) {

    /** 司机下拉选项 */
    public record DriverOption(int driverId, String name, String licenseType) {
    }
}
