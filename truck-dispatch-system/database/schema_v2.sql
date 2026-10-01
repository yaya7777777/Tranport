-- ============================================================
-- 运输车辆调度优化与仿真 - MySQL 完整建表脚本 (V2完善版)
-- 数据库名: transport
-- 表数量: 20张 (满足评分 "良" 要求 8+ 表, 远超标准)
-- 字符集: utf8mb4
-- 基于手写笔记完善: 工厂/仓库独立建表, 车辆增加油量里程, 司机增加微信邮箱地址
-- ============================================================

DROP DATABASE IF EXISTS transport;
CREATE DATABASE transport DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE transport;

-- ============================================================
-- 第一部分: 字典表 (4张)
-- ============================================================

-- 1. 车辆类型表
CREATE TABLE vehicle_type (
    type_id            INT AUTO_INCREMENT PRIMARY KEY COMMENT '车型ID',
    type_name          VARCHAR(50)   NOT NULL COMMENT '车型名称(轻型货车/重型货车/冷藏车等)',
    max_load           DECIMAL(10,2) NOT NULL COMMENT '最大载重(kg)',
    max_volume          DECIMAL(10,2) NOT NULL COMMENT '最大容积(m³)',
    fuel_type           VARCHAR(20)   NOT NULL COMMENT '燃油类型(柴油/汽油/电动)',
    avg_speed           DECIMAL(6,1)  COMMENT '平均速度(km/h)',
    fuel_consumption    DECIMAL(6,2)  COMMENT '油耗(L/100km)',
    can_carry           VARCHAR(300)  COMMENT '能装什么(如:生鲜/建材/电子产品)',
    cannot_carry        VARCHAR(300)  COMMENT '不能装什么(如:易燃易爆/超大件)',
    description         VARCHAR(200)  COMMENT '描述'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆类型表(含能装/不能装规则)';

-- 2. 货物分类表
CREATE TABLE cargo_category (
    category_id    INT AUTO_INCREMENT PRIMARY KEY COMMENT '货物分类ID',
    category_name  VARCHAR(50)  NOT NULL COMMENT '分类名称(生鲜/建材/电子产品/化工/日用品)',
    storage_req    VARCHAR(200) COMMENT '存储要求(冷链0-4℃/防潮/防震等)',
    description    VARCHAR(200) COMMENT '描述'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='货物分类表';

-- 3. POI分类表
CREATE TABLE poi_category (
    category_id    INT AUTO_INCREMENT PRIMARY KEY COMMENT 'POI分类ID',
    category_name  VARCHAR(50)  NOT NULL COMMENT '分类名称(工厂/仓库/加油站/收费站/停车场/物流中心)',
    description    VARCHAR(200) COMMENT '描述'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='POI站点分类表';

-- 4. 车辆状态定义表 (状态转换规则)
CREATE TABLE vehicle_status (
    status_id      INT AUTO_INCREMENT PRIMARY KEY COMMENT '状态ID',
    status_name    VARCHAR(30) NOT NULL COMMENT '状态名称(空闲/装载/卸货/运输/加油/保养)',
    status_code    VARCHAR(20) NOT NULL UNIQUE COMMENT '状态编码(IDLE/LOADING/UNLOADING/TRANSPORT/REFUEL/MAINTAIN)',
    description    VARCHAR(200) COMMENT '状态描述',
    allowed_next   VARCHAR(200) COMMENT '允许转换的下一状态(逗号分隔编码, 如: LOADING,REFUELING)',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆状态定义与转换规则表(6种状态)';

-- ============================================================
-- 第二部分: 主数据表 (7张)
-- ============================================================

-- 5. 司机表 (含微信/邮箱/地址)
CREATE TABLE driver (
    driver_id        INT AUTO_INCREMENT PRIMARY KEY COMMENT '司机ID',
    name             VARCHAR(50)  NOT NULL COMMENT '姓名',
    phone            VARCHAR(20)  NOT NULL COMMENT '电话',
    wechat           VARCHAR(50)  COMMENT '微信号',
    email            VARCHAR(80)  COMMENT '电子信箱',
    address          VARCHAR(200) COMMENT '住址',
    license_no       VARCHAR(30)  NOT NULL COMMENT '驾驶证号',
    license_type     VARCHAR(10)  NOT NULL COMMENT '准驾车型(A1/A2/B1/B2/C1)',
    hire_date        DATE         NOT NULL COMMENT '入职日期',
    status           TINYINT      DEFAULT 1 COMMENT '在职状态(1在职/0离职)',
    preference_desc  VARCHAR(300) COMMENT '偏好描述(偏好短途/夜班/特定路线)',
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_driver_phone (phone),
    INDEX idx_driver_license (license_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='司机信息表(含微信/邮箱/地址)';

-- 6. POI站点表
CREATE TABLE poi (
    poi_id         INT AUTO_INCREMENT PRIMARY KEY COMMENT 'POI站点ID',
    poi_name       VARCHAR(100) NOT NULL COMMENT '站点名称',
    category_id    INT          NOT NULL COMMENT 'POI分类ID',
    longitude      DECIMAL(11,7) NOT NULL COMMENT '经度(如:104.0668010)',
    latitude       DECIMAL(11,7) NOT NULL COMMENT '纬度(如:30.5728150)',
    address        VARCHAR(200) COMMENT '详细地址',
    capacity       DECIMAL(10,2) COMMENT '吞吐/仓储能力(吨)',
    contact_person  VARCHAR(50)  COMMENT '联系人',
    contact_phone  VARCHAR(20)   COMMENT '联系电话',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_poi_category (category_id),
    INDEX idx_poi_location (longitude, latitude),
    CONSTRAINT fk_poi_category FOREIGN KEY (category_id) REFERENCES poi_category(category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='POI站点信息表(含经纬度)';

-- 7. 工厂表 (独立实体, 关联POI位置)
CREATE TABLE factory (
    factory_id      INT AUTO_INCREMENT PRIMARY KEY COMMENT '工厂ID',
    factory_no      VARCHAR(30)  NOT NULL UNIQUE COMMENT '工厂编号',
    factory_name    VARCHAR(100) NOT NULL COMMENT '工厂名称',
    factory_type    VARCHAR(50)  NOT NULL COMMENT '工厂类型(制造厂/加工厂/组装厂等)',
    business_scope  VARCHAR(300) COMMENT '经营范围(采购/生产/销售)',
    poi_id          INT          NOT NULL COMMENT '关联POI位置ID',
    contact_person  VARCHAR(50)  COMMENT '联系人',
    contact_phone   VARCHAR(20)  COMMENT '联系电话',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_factory_no (factory_no),
    INDEX idx_factory_type (factory_type),
    CONSTRAINT fk_factory_poi FOREIGN KEY (poi_id) REFERENCES poi(poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工厂信息表(独立实体)';

-- 8. 仓库表 (独立实体, 关联POI位置)
CREATE TABLE warehouse (
    warehouse_id    INT AUTO_INCREMENT PRIMARY KEY COMMENT '仓库ID',
    warehouse_no    VARCHAR(30)  NOT NULL UNIQUE COMMENT '仓库编号',
    warehouse_name  VARCHAR(100) NOT NULL COMMENT '仓库名称',
    warehouse_type  VARCHAR(50)  NOT NULL COMMENT '仓库类型(原料仓/成品仓/中转仓/冷库等)',
    capacity        DECIMAL(10,2) COMMENT '仓储能力(吨)',
    poi_id          INT          NOT NULL COMMENT '关联POI位置ID',
    contact_person  VARCHAR(50)  COMMENT '联系人',
    contact_phone   VARCHAR(20)  COMMENT '联系电话',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_warehouse_no (warehouse_no),
    INDEX idx_warehouse_type (warehouse_type),
    CONSTRAINT fk_warehouse_poi FOREIGN KEY (poi_id) REFERENCES poi(poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库信息表(独立实体)';

-- 9. 车辆表 (含剩余油量/可运行里程)
CREATE TABLE vehicle (
    vehicle_id       INT AUTO_INCREMENT PRIMARY KEY COMMENT '车辆ID',
    plate_number     VARCHAR(20)  NOT NULL UNIQUE COMMENT '车牌号',
    type_id          INT          NOT NULL COMMENT '车型ID',
    driver_id        INT          COMMENT '当前绑定司机ID(空=未分配)',
    current_poi_id   INT          COMMENT '当前所在POI位置ID',
    current_status   VARCHAR(20)  DEFAULT 'IDLE' COMMENT '当前状态编码',
    remaining_fuel   DECIMAL(6,2) COMMENT '剩余油量(L)',
    estimated_range  DECIMAL(8,1) COMMENT '可运行公里数(km, 根据油量估算)',
    total_mileage    DECIMAL(12,1) DEFAULT 0 COMMENT '总里程(km)',
    purchase_date    DATE         NOT NULL COMMENT '购置日期',
    status           TINYINT      DEFAULT 1 COMMENT '启用状态(1可用/0停用)',
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_vehicle_plate (plate_number),
    INDEX idx_vehicle_type (type_id),
    INDEX idx_vehicle_status (current_status),
    CONSTRAINT fk_vehicle_type    FOREIGN KEY (type_id)   REFERENCES vehicle_type(type_id),
    CONSTRAINT fk_vehicle_driver  FOREIGN KEY (driver_id) REFERENCES driver(driver_id)
    -- current_poi_id 外键在 poi 表创建后用 ALTER TABLE 添加
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆信息表(含剩余油量/可运行里程)';

-- 补充 vehicle 表的 poi 外键
ALTER TABLE vehicle ADD CONSTRAINT fk_vehicle_poi FOREIGN KEY (current_poi_id) REFERENCES poi(poi_id);

-- 10. 货物表
CREATE TABLE cargo (
    cargo_id       INT AUTO_INCREMENT PRIMARY KEY COMMENT '货物ID',
    cargo_name     VARCHAR(100) NOT NULL COMMENT '货物名称',
    category_id    INT          NOT NULL COMMENT '货物分类ID',
    weight         DECIMAL(10,2) NOT NULL COMMENT '重量(kg)',
    volume         DECIMAL(10,2) NOT NULL COMMENT '体积(m³)',
    unit_price     DECIMAL(10,2) COMMENT '单价(元)',
    special_req    VARCHAR(200) COMMENT '特殊要求(易碎/不可倒置/防火等)',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_cargo_category (category_id),
    CONSTRAINT fk_cargo_category FOREIGN KEY (category_id) REFERENCES cargo_category(category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='货物信息表';

-- 11. 路线表
CREATE TABLE route (
    route_id            INT AUTO_INCREMENT PRIMARY KEY COMMENT '路线ID',
    origin_poi_id       INT NOT NULL COMMENT '起点POI ID',
    destination_poi_id  INT NOT NULL COMMENT '终点POI ID',
    route_name          VARCHAR(200) COMMENT '路线名称',
    distance            DECIMAL(8,2) NOT NULL COMMENT '距离(km)',
    estimated_time      INT NOT NULL COMMENT '预计行驶时间(分钟)',
    road_type           VARCHAR(30) COMMENT '道路类型(高速/国道/省道/城市道路)',
    difficulty_level    TINYINT DEFAULT 1 COMMENT '难度等级(1简单/2一般/3困难)',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_route_origin (origin_poi_id),
    INDEX idx_route_dest (destination_poi_id),
    CONSTRAINT fk_route_origin FOREIGN KEY (origin_poi_id)      REFERENCES poi(poi_id),
    CONSTRAINT fk_route_dest   FOREIGN KEY (destination_poi_id) REFERENCES poi(poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运输路线表';

-- ============================================================
-- 第三部分: 关联表 (3张)
-- ============================================================

-- 12. 货物-车型匹配表 (多对多)
CREATE TABLE cargo_vehicle_type_match (
    match_id           INT AUTO_INCREMENT PRIMARY KEY COMMENT '匹配ID',
    cargo_category_id  INT NOT NULL COMMENT '货物分类ID',
    vehicle_type_id    INT NOT NULL COMMENT '车型ID',
    is_preferred       TINYINT DEFAULT 0 COMMENT '是否优选车型(1是/0否)',
    created_at         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_cargo_vehicle (cargo_category_id, vehicle_type_id),
    CONSTRAINT fk_match_cargo   FOREIGN KEY (cargo_category_id) REFERENCES cargo_category(category_id),
    CONSTRAINT fk_match_vehicle FOREIGN KEY (vehicle_type_id)   REFERENCES vehicle_type(type_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='货物分类-车型匹配表(多对多)';

-- 13. 司机常跑路线表 (多对多)
CREATE TABLE driver_route (
    dr_id          INT AUTO_INCREMENT PRIMARY KEY COMMENT '关联ID',
    driver_id      INT NOT NULL COMMENT '司机ID',
    route_id       INT NOT NULL COMMENT '路线ID',
    frequency      INT DEFAULT 1 COMMENT '跑此路线频率(次/月)',
    last_run_date  DATE COMMENT '最近一次跑此路线日期',
    is_favorite    TINYINT DEFAULT 0 COMMENT '是否偏好路线(1是/0否)',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_driver_route (driver_id, route_id),
    CONSTRAINT fk_dr_driver FOREIGN KEY (driver_id) REFERENCES driver(driver_id),
    CONSTRAINT fk_dr_route  FOREIGN KEY (route_id)  REFERENCES route(route_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='司机常跑路线表(多对多)';

-- 14. 厂仓关系表 (采购/生产/销售)
CREATE TABLE factory_warehouse (
    fw_id           INT AUTO_INCREMENT PRIMARY KEY COMMENT '厂仓关系ID',
    factory_id      INT NOT NULL COMMENT '工厂ID',
    warehouse_id    INT NOT NULL COMMENT '仓库ID',
    relation_type   VARCHAR(10) NOT NULL COMMENT '关系类型(PROCURE采购/PRODUCE生产/SALE销售)',
    description     VARCHAR(200) COMMENT '关系描述',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_fw_factory (factory_id),
    INDEX idx_fw_warehouse (warehouse_id),
    CONSTRAINT fk_fw_factory   FOREIGN KEY (factory_id)   REFERENCES factory(factory_id),
    CONSTRAINT fk_fw_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='厂仓关系表(采购/生产/销售)';

-- ============================================================
-- 第四部分: 业务/交易表 (2张)
-- ============================================================

-- 15. 运输订单表 (任务: 提交/终点/任务/行政时间)
CREATE TABLE cargo_order (
    order_id               INT AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID',
    order_no               VARCHAR(50) NOT NULL UNIQUE COMMENT '订单编号',
    cargo_id               INT NOT NULL COMMENT '货物ID',
    quantity               DECIMAL(10,2) NOT NULL COMMENT '数量(件/吨)',
    origin_poi_id          INT NOT NULL COMMENT '发货POI ID(起点)',
    destination_poi_id     INT NOT NULL COMMENT '收货POI ID(终点)',
    route_id               INT COMMENT '预定路线ID',
    order_time             DATETIME NOT NULL COMMENT '下单时间(提交时间)',
    required_delivery_time DATETIME COMMENT '要求送达时间(行政时间)',
    actual_delivery_time   DATETIME COMMENT '实际送达时间',
    priority               TINYINT DEFAULT 2 COMMENT '优先级(1高/2中/3低)',
    status                 VARCHAR(20) DEFAULT 'PENDING' COMMENT '订单状态(PENDING/ASSIGNED/TRANSPORTING/DELIVERED/CANCELLED)',
    remark                 VARCHAR(200) COMMENT '备注(任务说明)',
    created_at             DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_order_no (order_no),
    INDEX idx_order_status (status),
    INDEX idx_order_cargo (cargo_id),
    INDEX idx_order_origin (origin_poi_id),
    INDEX idx_order_dest (destination_poi_id),
    CONSTRAINT fk_order_cargo   FOREIGN KEY (cargo_id)           REFERENCES cargo(cargo_id),
    CONSTRAINT fk_order_origin  FOREIGN KEY (origin_poi_id)      REFERENCES poi(poi_id),
    CONSTRAINT fk_order_dest    FOREIGN KEY (destination_poi_id) REFERENCES poi(poi_id),
    CONSTRAINT fk_order_route   FOREIGN KEY (route_id)           REFERENCES route(route_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运输订单表(任务:提交/终点/行政时间)';

-- 16. 调度记录表
CREATE TABLE dispatch (
    dispatch_id        INT AUTO_INCREMENT PRIMARY KEY COMMENT '调度ID',
    order_id           INT NOT NULL COMMENT '关联订单ID',
    vehicle_id         INT NOT NULL COMMENT '分配车辆ID',
    driver_id          INT NOT NULL COMMENT '分配司机ID',
    route_id           INT COMMENT '实际执行路线ID',
    dispatch_time      DATETIME NOT NULL COMMENT '调度时间',
    estimated_arrival  DATETIME COMMENT '预计到达时间',
    actual_arrival     DATETIME COMMENT '实际到达时间',
    status             VARCHAR(20) DEFAULT 'DISPATCHED' COMMENT '调度状态(DISPATCHED/IN_TRANSIT/COMPLETED/CANCELLED)',
    created_at         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- 一车多单兜底：仅"未完成"的调度记录才填该列，已完成/已取消为 NULL。
    -- NULL 不参与唯一性判断，因此唯一索引只约束"同一辆车不能同时有两条未完成任务"。
    -- 应用层已有忙碌校验，此列为并发场景下的数据库级最后防线。
    active_vehicle_id  INT GENERATED ALWAYS AS
                       (CASE WHEN status IN ('DISPATCHED','IN_TRANSIT') THEN vehicle_id ELSE NULL END) VIRTUAL
                       COMMENT '未完成调度占用的车辆(仅用于唯一约束)',
    INDEX idx_dispatch_order (order_id),
    INDEX idx_dispatch_vehicle (vehicle_id),
    INDEX idx_dispatch_driver (driver_id),
    UNIQUE KEY uk_dispatch_active_vehicle (active_vehicle_id),
    CONSTRAINT fk_dispatch_order   FOREIGN KEY (order_id)   REFERENCES cargo_order(order_id),
    CONSTRAINT fk_dispatch_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(vehicle_id),
    CONSTRAINT fk_dispatch_driver  FOREIGN KEY (driver_id)  REFERENCES driver(driver_id),
    CONSTRAINT fk_dispatch_route   FOREIGN KEY (route_id)  REFERENCES route(route_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆调度记录表';

-- ============================================================
-- 第五部分: 仿真与监控表 (4张)
-- ============================================================

-- 17. 车辆状态变更日志表
CREATE TABLE vehicle_status_log (
    log_id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
    vehicle_id      INT NOT NULL COMMENT '车辆ID',
    from_status_id  INT COMMENT '原状态ID',
    to_status_id    INT NOT NULL COMMENT '新状态ID',
    poi_id          INT COMMENT '状态变更所在POI ID',
    change_time     DATETIME NOT NULL COMMENT '状态变更时间',
    remark          VARCHAR(200) COMMENT '备注',
    INDEX idx_log_vehicle (vehicle_id),
    INDEX idx_log_time (change_time),
    CONSTRAINT fk_log_vehicle FOREIGN KEY (vehicle_id)     REFERENCES vehicle(vehicle_id),
    CONSTRAINT fk_log_from    FOREIGN KEY (from_status_id) REFERENCES vehicle_status(status_id),
    CONSTRAINT fk_log_to      FOREIGN KEY (to_status_id)   REFERENCES vehicle_status(status_id),
    CONSTRAINT fk_log_poi     FOREIGN KEY (poi_id)         REFERENCES poi(poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆状态变更日志表';

-- 18. 仿真记录表
CREATE TABLE simulation_record (
    sim_id          INT AUTO_INCREMENT PRIMARY KEY COMMENT '仿真ID',
    sim_name        VARCHAR(100) NOT NULL COMMENT '仿真名称',
    sim_type        VARCHAR(30)  COMMENT '仿真类型(SINGLE单车/MULTI多车/FULL全环境)',
    start_time      DATETIME NOT NULL COMMENT '仿真开始时间',
    end_time        DATETIME COMMENT '仿真结束时间',
    vehicle_count   INT DEFAULT 0 COMMENT '参与车辆数',
    order_count     INT DEFAULT 0 COMMENT '处理订单数',
    status          VARCHAR(20) DEFAULT 'RUNNING' COMMENT '仿真状态(RUNNING/COMPLETED/STOPPED)',
    config_json     JSON COMMENT '仿真配置参数(时间步长/异常概率等)',
    description     VARCHAR(300) COMMENT '仿真描述',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仿真运行记录表';

-- 19. 交通异常记录表
CREATE TABLE traffic_anomaly (
    anomaly_id      INT AUTO_INCREMENT PRIMARY KEY COMMENT '异常ID',
    route_id        INT COMMENT '关联路线ID',
    poi_id          INT COMMENT '关联POI ID(异常发生位置)',
    anomaly_type    VARCHAR(30) NOT NULL COMMENT '异常类型(CONGESTION拥堵/ACCIDENT事故/CONSTRUCTION施工/WEATHER天气)',
    start_time      DATETIME NOT NULL COMMENT '异常开始时间',
    end_time        DATETIME COMMENT '异常结束时间(空=持续中)',
    severity        TINYINT DEFAULT 2 COMMENT '严重程度(1轻微/2中等/3严重)',
    description     VARCHAR(200) COMMENT '异常描述',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_anomaly_route (route_id),
    INDEX idx_anomaly_type (anomaly_type),
    CONSTRAINT fk_anomaly_route FOREIGN KEY (route_id) REFERENCES route(route_id),
    CONSTRAINT fk_anomaly_poi   FOREIGN KEY (poi_id)  REFERENCES poi(poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交通异常记录表';

-- 20. GPS定位数据表 (北斗模块上报)
CREATE TABLE gps_data (
    gps_id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'GPS数据ID',
    vehicle_id      INT NOT NULL COMMENT '车辆ID',
    longitude       DECIMAL(11,7) NOT NULL COMMENT '经度',
    latitude        DECIMAL(11,7) NOT NULL COMMENT '纬度',
    speed           DECIMAL(6,2) COMMENT '速度(km/h)',
    heading         DECIMAL(5,2) COMMENT '方向角(0-360度)',
    sim_id          INT COMMENT '所属仿真ID(空=真实数据)',
    timestamp       DATETIME NOT NULL COMMENT '定位时间戳',
    INDEX idx_gps_vehicle (vehicle_id),
    INDEX idx_gps_time (timestamp),
    INDEX idx_gps_sim (sim_id),
    CONSTRAINT fk_gps_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(vehicle_id),
    CONSTRAINT fk_gps_sim    FOREIGN KEY (sim_id)     REFERENCES simulation_record(sim_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GPS定位数据表(北斗模块上报)';

-- ============================================================
-- 第六部分: 种子数据
-- ============================================================

-- 车辆类型 (含能装/不能装规则)
INSERT INTO vehicle_type (type_name, max_load, max_volume, fuel_type, avg_speed, fuel_consumption, can_carry, cannot_carry, description) VALUES
('轻型货车', 2000.00, 12.00, '柴油', 70.0, 12.50, '生鲜,日用品,电子产品,建材(小件)', '易燃易爆,超大件,重型建材', '城市配送短途'),
('中型货车', 5000.00, 25.00, '柴油', 65.0, 18.00, '建材,电子产品,日用品,化工(普通)', '易燃易爆,生鲜(无冷链)', '中短途运输'),
('重型货车', 30000.00, 80.00, '柴油', 60.0, 30.00, '建材(重型),化工,大宗货物', '生鲜,精密电子产品', '长途大宗运输'),
('冷藏车', 8000.00, 20.00, '柴油', 55.0, 25.00, '生鲜,冷冻食品,医药', '建材,化工,易燃品', '冷链运输专用'),
('集装箱车', 25000.00, 67.50, '柴油', 62.0, 28.00, '电子产品,日用品,建材(标准化)', '生鲜(无冷链),散装化工', '标准集装箱运输');

-- 货物分类
INSERT INTO cargo_category (category_name, storage_req, description) VALUES
('生鲜食品', '冷链0-4℃', '蔬菜水果肉类等'),
('建材', '防潮防雨', '水泥钢材砖块等'),
('电子产品', '防震防潮', '精密仪器家电等'),
('化工品', '防火防爆', '涂料化学原料等'),
('日用品', '常温干燥', '百货快消品等');

-- POI分类
INSERT INTO poi_category (category_name, description) VALUES
('工厂', '生产制造基地'),
('仓库', '货物存储中转站'),
('加油站', '车辆燃油补给站'),
('收费站', '高速公路收费站点'),
('停车场', '车辆停靠休息区'),
('物流中心', '货物集散配送中心');

-- 车辆状态 (6种 + 转换规则)
INSERT INTO vehicle_status (status_name, status_code, description, allowed_next) VALUES
('空闲', 'IDLE', '车辆空闲待命', 'LOADING,REFUEL,MAINTAIN'),
('装载', 'LOADING', '正在装载货物', 'TRANSPORT,IDLE'),
('卸货', 'UNLOADING', '正在卸载货物', 'IDLE,LOADING'),
('运输', 'TRANSPORT', '正在运输途中', 'UNLOADING,IDLE'),
('加油', 'REFUEL', '正在补充燃油', 'IDLE,LOADING'),
('保养', 'MAINTAIN', '正在维修保养', 'IDLE');

-- ============================================================
-- 第七部分: 查询视图 (可选, 方便业务查询)
-- ============================================================

-- 车辆完整信息视图
CREATE OR REPLACE VIEW v_vehicle_detail AS
SELECT
    v.vehicle_id, v.plate_number, vt.type_name AS vehicle_type,
    vt.max_load, v.remaining_fuel, v.estimated_range,
    d.name AS driver_name, d.phone AS driver_phone,
    p.poi_name AS current_location, v.current_status,
    v.total_mileage, v.status AS vehicle_active
FROM vehicle v
LEFT JOIN vehicle_type vt ON v.type_id = vt.type_id
LEFT JOIN driver d       ON v.driver_id = d.driver_id
LEFT JOIN poi p          ON v.current_poi_id = p.poi_id;

-- 订单调度详情视图
CREATE OR REPLACE VIEW v_order_dispatch AS
SELECT
    co.order_id, co.order_no, c.cargo_name,
    cc.category_name AS cargo_type, co.quantity,
    p1.poi_name AS origin, p2.poi_name AS destination,
    r.distance, r.estimated_time,
    d.name AS driver_name, v.plate_number,
    vt.type_name AS vehicle_type,
    co.status AS order_status, dis.status AS dispatch_status,
    dis.dispatch_time, dis.actual_arrival
FROM cargo_order co
JOIN cargo c            ON co.cargo_id = c.cargo_id
JOIN cargo_category cc  ON c.category_id = cc.category_id
JOIN poi p1             ON co.origin_poi_id = p1.poi_id
JOIN poi p2             ON co.destination_poi_id = p2.poi_id
LEFT JOIN route r       ON co.route_id = r.route_id
LEFT JOIN dispatch dis ON co.order_id = dis.order_id
LEFT JOIN driver d      ON dis.driver_id = d.driver_id
LEFT JOIN vehicle v     ON dis.vehicle_id = v.vehicle_id
LEFT JOIN vehicle_type vt ON v.type_id = vt.type_id;

-- 厂仓关系详情视图
CREATE OR REPLACE VIEW v_factory_warehouse AS
SELECT
    fw.fw_id, f.factory_no, f.factory_name, f.factory_type,
    w.warehouse_no, w.warehouse_name, w.warehouse_type,
    fw.relation_type, fw.description,
    pf.poi_name AS factory_location,
    pw.poi_name AS warehouse_location
FROM factory_warehouse fw
JOIN factory f  ON fw.factory_id = f.factory_id
JOIN warehouse w ON fw.warehouse_id = w.warehouse_id
JOIN poi pf      ON f.poi_id = pf.poi_id
JOIN poi pw      ON w.poi_id = pw.poi_id;

-- ============================================================
SELECT CONCAT('数据库 transport V2 创建完成, 共 20 张表 + 3 个视图') AS message;
