-- ============================================================
-- 货车路径规划接入补丁（幂等，可重复执行）
-- 1) route 表新增 polyline_src 列，标记路线坐标来源（truck=货车规划 / car=驾车规划）
-- 2) 存量 polyline 均由驾车规划(v3)生成，统一标记为 car；
--    货车接口权限开通后，后端会按需把这些 car 路线升级为货车路线并回写标记。
-- 执行：mysql -u root transport < database/patch_truck_route.sql
-- ============================================================
USE transport;

ALTER TABLE route
    ADD COLUMN IF NOT EXISTS polyline_src VARCHAR(8) NULL COMMENT 'polyline 来源(truck=高德货车规划/car=驾车规划)' AFTER polyline;

UPDATE route SET polyline_src = 'car' WHERE polyline IS NOT NULL AND polyline_src IS NULL;
