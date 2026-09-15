# 货车调度系统综合设计——最终预览版

## 架构
Vue 3 + Vite → Axios/HTTP → Spring Boot → Spring JDBC → MySQL

## 已完成的前后端联调功能
1. 运行总览：车辆总数、空闲、运输中、保养、待处理订单、执行中调度
2. 车辆管理：从 MySQL vehicle 多表 JOIN 查询并显示
3. 订单调度：cargo_order + cargo + POI + route + dispatch + driver + vehicle 联合查询
4. 后端健康检查接口
5. 数据库保留老师提供的 20 张表和 3 个视图
6. 提供 demo_data.sql，方便直接看到完整页面效果

## 启动
### 1. 数据库
在 Navicat 执行 database/schema_v2.sql，然后执行 database/demo_data.sql。
注意 schema_v2.sql 会删除并重建 transport 数据库，已有重要数据时先备份。

### 2. 后端
修改 backend/src/main/resources/application.properties 中的 MySQL 密码。
JDK 17 + Maven：
cd backend
mvn spring-boot:run

测试 http://localhost:8080/api/dashboard/health

### 3. 前端
Node.js 18+：
cd frontend
npm install
npm run dev

打开 http://localhost:5173

## 后续可扩展
车辆/司机/订单 CRUD、手动/自动调度、GPS/北斗上报、地图、交通异常、仿真、调度优化算法、统计图表。
