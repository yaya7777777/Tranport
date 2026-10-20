package com.example.transport.dto;

/**
 * 运输路线信息（对应 route 表；缺失时由 GIS 服务现场计算并回写）
 *
 * @param routeId       路线 ID（新计算的路线也会拿到自增 ID）
 * @param originPoiId   起点 POI ID
 * @param destPoiId     终点 POI ID
 * @param routeName     路线名称
 * @param distance      距离（km）
 * @param estimatedTime 预计行驶时间（分钟）
 * @param roadType      道路类型（高速/国道/城市道路）
 * @param difficulty    难度等级（1 简单 / 2 一般 / 3 困难）
 * @param newlyCreated  是否为本次调用新计算并落库的路线
 */
public record RouteInfo(Integer routeId, Integer originPoiId, Integer destPoiId, String routeName,
                        Double distance, Integer estimatedTime, String roadType, Integer difficulty,
                        boolean newlyCreated) {
}
