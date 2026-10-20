package com.example.transport.dto;

/**
 * 车型视图对象（对应 vehicle_type 表：车型与装载能力的基础数据）
 *
 * @param typeId           车型 ID
 * @param typeName         车型名称
 * @param maxLoad          最大载重（kg）
 * @param maxVolume        最大容积（m³）
 * @param fuelType         燃油类型（柴油/汽油/电动）
 * @param avgSpeed         平均速度（km/h，仿真节拍中按此速度推进位置）
 * @param fuelConsumption  百公里油耗（L/100km）
 * @param canCarry         能装载的货物描述
 * @param cannotCarry      不能装载的货物描述
 */
public record VehicleTypeView(Integer typeId, String typeName, Double maxLoad, Double maxVolume,
                              String fuelType, Double avgSpeed, Double fuelConsumption,
                              String canCarry, String cannotCarry) {
}
