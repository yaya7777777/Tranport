-- ============================================================
-- 幂等补丁：route 表增加 polyline 列
-- 用途：保存高德驾车路径规划返回的真实道路坐标串，
--       前端仿真沙盘据此绘制沿真实道路的运输路线（替代起终点直线）。
-- 坐标格式：GCJ-02，"lng,lat;lng,lat;..."，与 poi 表坐标系一致（同源于高德），
--           前端（高德底图）可直接绘制，无需纠偏。
-- 可重复执行； MariaDB 支持 ADD COLUMN IF NOT EXISTS。
-- ============================================================
USE transport;

ALTER TABLE route
    ADD COLUMN IF NOT EXISTS polyline MEDIUMTEXT NULL
    COMMENT '真实道路坐标串(GCJ-02, lng,lat;lng,lat;... 由高德驾车路径规划返回)';
