USE transport;

INSERT INTO driver (name,phone,wechat,email,address,license_no,license_type,hire_date,status,preference_desc) VALUES
('张伟','13800000001','driver_zhang','zhang@example.com','成都市武侯区','D510100001','A2','2024-03-01',1,'偏好短途'),
('李娜','13800000002','driver_li','li@example.com','成都市锦江区','D510100002','B2','2023-07-15',1,'偏好城市配送'),
('王强','13800000003','driver_wang','wang@example.com','成都市青羊区','D510100003','A2','2022-11-20',1,'偏好长途');

INSERT INTO poi (poi_name,category_id,longitude,latitude,address,capacity,contact_person,contact_phone) VALUES
('成都一号工厂',1,104.0668010,30.5728150,'成都市高新区',500,'陈经理','13900000001'),
('成都中心仓',2,104.0833000,30.6586000,'成都市成华区',300,'刘经理','13900000002'),
('西部物流中心',6,103.9960000,30.6900000,'成都市金牛区',800,'赵经理','13900000003'),
('高新加油站',3,104.0450000,30.5300000,'成都市高新区',100,NULL,NULL);

INSERT INTO factory (factory_no,factory_name,factory_type,business_scope,poi_id,contact_person,contact_phone)
VALUES ('F001','成都一号工厂','制造厂','采购/生产/销售',1,'陈经理','13900000001');

INSERT INTO warehouse (warehouse_no,warehouse_name,warehouse_type,capacity,poi_id,contact_person,contact_phone)
VALUES ('W001','成都中心仓','成品仓',300,2,'刘经理','13900000002');

INSERT INTO vehicle (plate_number,type_id,driver_id,current_poi_id,current_status,remaining_fuel,estimated_range,total_mileage,purchase_date,status) VALUES
('川A10001',1,1,1,'IDLE',65.00,520.0,18500.0,'2022-06-01',1),
('川A10002',2,2,2,'TRANSPORT',110.00,610.0,42600.0,'2021-09-15',1),
('川A10003',3,3,3,'LOADING',180.00,720.0,68500.0,'2020-04-20',1),
('川A10004',4,NULL,2,'MAINTAIN',90.00,360.0,30100.0,'2023-01-10',1),
('川A10005',5,NULL,3,'IDLE',150.00,535.0,51400.0,'2021-12-08',1);

INSERT INTO cargo (cargo_name,category_id,weight,volume,unit_price,special_req) VALUES
('新鲜蔬菜',1,800,4.5,6.80,'冷链运输'),
('钢材配件',2,3200,10.0,4200,'防潮'),
('精密电子设备',3,1200,8.0,6800,'防震防潮'),
('工业涂料',4,2000,7.0,1800,'防火防爆'),
('日用品',5,1500,9.0,3200,'常温干燥');

INSERT INTO route (origin_poi_id,destination_poi_id,route_name,distance,estimated_time,road_type,difficulty_level) VALUES
(1,2,'工厂-中心仓',18.50,35,'城市道路',1),
(1,3,'工厂-物流中心',32.00,50,'高速',1),
(2,3,'中心仓-物流中心',15.20,30,'城市道路',1);

INSERT INTO cargo_order (order_no,cargo_id,quantity,origin_poi_id,destination_poi_id,route_id,order_time,required_delivery_time,priority,status,remark) VALUES
('ORD20260915001',1,800,1,2,1,'2026-09-15 08:00:00','2026-09-15 11:00:00',1,'PENDING','冷链订单'),
('ORD20260915002',2,3200,1,3,2,'2026-09-15 08:30:00','2026-09-15 13:00:00',2,'ASSIGNED','钢材运输'),
('ORD20260915003',3,1200,2,3,3,'2026-09-15 09:00:00','2026-09-15 14:00:00',2,'TRANSPORTING','精密设备');

INSERT INTO dispatch (order_id,vehicle_id,driver_id,route_id,dispatch_time,estimated_arrival,status) VALUES
(2,2,2,2,'2026-09-15 08:45:00','2026-09-15 09:35:00','IN_TRANSIT'),
(3,1,1,3,'2026-09-15 09:15:00','2026-09-15 09:45:00','IN_TRANSIT');

INSERT INTO gps_data (vehicle_id,longitude,latitude,speed,heading,timestamp) VALUES
(1,104.0668010,30.5728150,0,90,'2026-09-15 09:20:00'),
(2,104.0550000,30.6000000,48.5,95,'2026-09-15 09:20:00'),
(3,104.0833000,30.6586000,0,180,'2026-09-15 09:20:00');
