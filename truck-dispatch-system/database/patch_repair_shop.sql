-- ============================================================
-- 存量库补丁：补充"修车厂"分类与 6 个修车厂 POI（幂等，可重复执行）
-- 用途：仿真中保养（MAINTAIN）状态的车辆需停靠在修车厂；
--       已导入旧版 demo_data.sql（36 POI，无修车厂）的库执行本补丁即可，
--       无需重跑 demo_data.sql（重跑会清空业务数据）。
-- ============================================================
USE transport;

-- 1) 补充修车厂分类（已存在则跳过）
INSERT INTO poi_category (category_name, description)
SELECT '修车厂', '车辆维修保养场' WHERE NOT EXISTS (SELECT 1 FROM poi_category WHERE category_name = '修车厂');

-- 2) 补充 6 个修车厂 POI（库中已有任一修车厂则跳过，避免重复）
INSERT INTO poi (poi_name,category_id,longitude,latitude,address,capacity,contact_person,contact_phone)
SELECT x.poi_name, pc.category_id, x.longitude, x.latitude, x.address, x.capacity, NULL, NULL
FROM (
    SELECT '高新修车厂' AS poi_name, 104.0500000 AS longitude, 30.5400000 AS latitude, '成都市高新区' AS address, 30 AS capacity
    UNION ALL SELECT '双流修车厂',   103.9350000, 30.5400000, '成都市双流区',   25
    UNION ALL SELECT '龙泉驿修车厂', 104.2650000, 30.5650000, '成都市龙泉驿区', 35
    UNION ALL SELECT '温江修车厂',   103.8300000, 30.6800000, '成都市温江区',   20
    UNION ALL SELECT '新都修车厂',   104.1450000, 30.8100000, '成都市新都区',   25
    UNION ALL SELECT '青白江修车厂', 104.2400000, 30.8900000, '成都市青白江区', 30
) x JOIN poi_category pc ON pc.category_name = '修车厂'
WHERE NOT EXISTS (
    SELECT 1 FROM poi p JOIN poi_category c ON p.category_id = c.category_id
    WHERE c.category_name = '修车厂'
);

SELECT (SELECT COUNT(*) FROM poi p JOIN poi_category c ON p.category_id = c.category_id
        WHERE c.category_name = '修车厂') AS repair_shop_count;
