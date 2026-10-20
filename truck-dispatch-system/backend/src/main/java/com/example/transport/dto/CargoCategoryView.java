package com.example.transport.dto;

/**
 * 货物分类视图对象（对应 cargo_category 表：货物与适用车型匹配的一侧）
 *
 * @param categoryId   货物分类 ID
 * @param categoryName 分类名称（生鲜食品/建材/电子产品/化工品/日用品）
 * @param storageReq   存储要求（冷链/防潮/防火等）
 */
public record CargoCategoryView(Integer categoryId, String categoryName, String storageReq) {
}
