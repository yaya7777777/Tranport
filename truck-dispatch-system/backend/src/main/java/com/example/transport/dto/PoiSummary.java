package com.example.transport.dto;

/**
 * POI 站点摘要对象（对应 poi 表 JOIN poi_category 后的查询结果）
 *
 * @param poiId        站点 ID
 * @param poiName      站点名称
 * @param categoryId   分类 ID
 * @param categoryName 分类名称（工厂/仓库/加油站/收费站/停车场/物流中心）
 * @param longitude    经度
 * @param latitude     纬度
 * @param address      详细地址
 * @param capacity     吞吐/仓储能力（吨）
 */
public record PoiSummary(Integer poiId, String poiName, Integer categoryId, String categoryName,
                         Double longitude, Double latitude, String address, Double capacity) {
}
