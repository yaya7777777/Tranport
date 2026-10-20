package com.example.transport.dto;

import java.util.List;

/**
 * 货物分类 × 车型 匹配矩阵（"基础数据"页用表格直观展示匹配规则）
 *
 * @param rows 矩阵的每一行：一个货物分类对应所有车型的可装/优选标记
 */
public record MatchMatrix(List<Row> rows) {

    /**
     * 矩阵单元格：某车型对某货物分类的适配情况
     *
     * @param typeId      车型 ID
     * @param typeName    车型名称
     * @param compatible  是否可装（cargo_vehicle_type_match 中存在记录）
     * @param preferred   是否优选（is_preferred=1）
     */
    public record Cell(Integer typeId, String typeName, boolean compatible, boolean preferred) {
    }

    /**
     * 矩阵行：货物分类 + 该分类下各车型的适配单元格
     *
     * @param categoryId   货物分类 ID
     * @param categoryName 货物分类名称
     * @param cells        各车型对应的单元格
     */
    public record Row(Integer categoryId, String categoryName, List<Cell> cells) {
    }
}
