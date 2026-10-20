package com.example.transport.dto;

/**
 * POI 分类及其数量统计（用于"基础数据"页的分类达标情况展示）
 *
 * @param categoryId   分类 ID
 * @param categoryName 分类名称
 * @param count        该分类下的 POI 数量
 */
public record CategoryCount(Integer categoryId, String categoryName, long count) {
}
