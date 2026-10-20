package com.example.transport.dto;

import java.util.List;

/**
 * 新增需求表单的下拉数据：厂仓关系（工厂→仓库配对）与可选货物
 *
 * @param relations 厂仓关系列表（每项含工厂/仓库名称与 ID）
 * @param cargos    货物列表
 */
public record OrderOptions(List<FwRelation> relations, List<CargoOption> cargos) {

    /** 一条厂仓关系（新增需求时起点=工厂、终点=仓库） */
    public record FwRelation(int factoryId, String factoryName,
                             int warehouseId, String warehouseName,
                             String relationType) {
    }
}
