# 运输车辆调度优化与仿真系统

> 对应课题《进阶式挑战性综合项目 I —— 运输车辆调度优化与仿真》。
> 技术栈：Vue 3 + Vite + Axios ── HTTP/JSON ── Spring Boot 3 + Spring JDBC ── MySQL/MariaDB。

## 一、任务书三大核心模块落地情况

| 任务书要求 | 本系统实现 |
| --- | --- |
| ① 北斗定位：经 ARM（RS485/Modbus）读取北斗模块，以 HTTP+JSON 上报经纬度 | `tools/modbus_collector.py` 双模式：`mock` 模拟 12 辆车在成都范围巡航；`real` 经 pymodbus 读保持寄存器（经纬度 int32/1e7、速度航向 /10），POST 到 `/api/gps/report`，后端 `GpsController/GpsService` 落库 `gps_data` |
| ② 车型-货物匹配筛选函数；爬虫更新 POI | `MatchService` 按匹配规则（优选/可装）+载重容积+距离指数衰减+常跑路线+续航综合评分，返回候选车辆与评分理由；`tools/poi_crawler.py` 支持高德 REST API（`amap`）与离线确定性生成（`offline`，固定随机种子，6 类 POI，可生成 500/1000 条） |
| ③ GIS 仿真：厂仓关系、单车/多车按状态转换规则表 + 司机偏好，模拟 6 状态循环 | `SimulationEngine` 固定节拍推进（默认真实 2 秒=仿真 5 分钟），严格按 `vehicle_status.allowed_next` 规则表在 **空闲 IDLE / 装载 LOADING / 运输 TRANSPORT / 卸货 UNLOADING / 加油 REFUEL / 保养 MAINTAIN** 6 状态间流转；经纬度线性插值、按百公里油耗扣油、拥堵/事故/施工/天气 4 类交通异常限速、厂仓关系自动补给货源，支持 12 车无限循环；前端 SVG 沙盘实时可视化 |

### 评级指标对照

| 等级 | 指标 | 现状 |
| --- | --- | --- |
| 中 | POI≥500、≥3 类、≥4 状态 | POI 可离线生成 500 条、6 类、6 状态 ✅ |
| 良 | POI≥1000、≥5 类、≥6 状态、ER 图≥8 表 | 已导入 1036 条（36 演示+1000 生成）、6 类、6 状态；`schema_v2.sql` 含 20 张表 3 个视图 ✅ |
| 优 | 实机 Modbus + 10 车循环 + 可视化 | `real` 模式代码就绪（接上 RS485 北斗模块即可）；12 车循环已实测；SVG 沙盘含车辆/POI/路线/异常动画 ✅ |

## 二、目录结构

详见 [PROJECT_TREE.txt](PROJECT_TREE.txt)。分层：`controller → service → repository(JdbcTemplate) → dto(Java record) → config`。

## 三、环境要求

- JDK 17 及以上（本机用 Zulu JDK 25 编译 target 17）
- Maven 3.9+（本机无系统 Maven，使用便携版 `C:\Users\Administrator\maven-portable\apache-maven-3.9.9\bin\mvn.cmd`，下文命令按自己实际路径替换即可）
- Node.js 18+（推荐 20.19+；本机 v20.18.2 仅有 Vite 版本警告，可正常运行）
- MySQL 5.7+ / MariaDB 10.4+（本机为 **XAMPP 自带 MariaDB 10.4.8，root 密码为空**，端口 3306）
- Python 3.8+（仅运行两个工具脚本时需要，实机模式额外 `pip install pymodbus requests`）

## 四、启动步骤（按顺序）

### 1. 初始化数据库

用命令行或 Navicat 依次执行（`schema_v2.sql` 会 DROP 并重建 `transport` 库，已有数据请先备份）：

```bat
C:\xampp\mysql\bin\mysql.exe -uroot < database\schema_v2.sql
C:\xampp\mysql\bin\mysql.exe -uroot transport < database\demo_data.sql
```

执行后自带演示数据：12 辆车、12 名司机、5 类货物、5 种车型、36 个 POI（6 类）、13 条匹配规则、6 条厂仓关系、10 条常跑路线、6 状态转换规则、3 张演示订单。

**扩展 POI 到 1000 条（评级"良"，可选）：**

```bat
python tools\poi_crawler.py --mode offline --total 1000
Get-Content database\poi_generated.sql -Raw -Encoding UTF8 | C:\xampp\mysql\bin\mysql.exe -uroot --default-character-set=utf8mb4 transport
```

生成脚本幂等（先按 `contact_person='OFFLINE_GEN'` 清理历史批次再插入），可重复执行。

### 2. 启动后端（端口 8888）

检查 `backend/src/main/resources/application.properties`：

```properties
server.port=8888
spring.datasource.url=jdbc:mysql://localhost:3306/transport?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
spring.datasource.username=root
spring.datasource.password=          # XAMPP 默认为空；独立 MySQL 改成你的密码
```

启动：

```bat
cd backend
mvn spring-boot:run
:: 本机便携 Maven：
:: C:\Users\Administrator\maven-portable\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

看到 `Started TransportApplication` 后验证：<http://localhost:8888/api/dashboard/health>

### 3. 启动前端（端口 5173）

```bat
cd frontend
npm install
npm run dev
```

浏览器打开 <http://localhost:5173>，共 6 个页面：调度看板、车辆管理、订单调度（含智能匹配派车）、**仿真沙盘**、基础数据、设备定位。

## 五、Python 工具用法

### 北斗/Modbus 采集器 `tools/modbus_collector.py`

```bat
:: 模拟单车（默认川A10001，每 3 秒上报一次，后端必须先启动）
python tools/modbus_collector.py --mode mock --vehicle-code 川A10001 --interval 2

:: 同时模拟全部 12 辆演示车
python tools/modbus_collector.py --mode mock --all

:: 实机模式：RS485 接好北斗模块后（pip install pymodbus）
python tools/modbus_collector.py --mode real --port COM3 --baudrate 9600 --slave 1 --vehicle-code 川A10001
```

上报报文：`{"vehicleCode","longitude","latitude","speed","heading","timestamp"}`。
实机数据 `gps_data.sim_id` 为空（前端显示"北斗实机"绿标），仿真轨迹 `sim_id` 非空（显示"仿真"蓝标）。

### POI 爬虫 `tools/poi_crawler.py`

```bat
python tools/poi_crawler.py --mode offline --total 500     :: 中等级别
python tools/poi_crawler.py --mode offline --total 1000    :: 良好级别（默认输出 database/poi_generated.sql）
python tools/poi_crawler.py --mode amap --key 你的高德Key   :: 真实高德 POI（分页/去重/范围校验）
```

## 六、核心接口清单（前缀 http://localhost:8888）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/dashboard/health`、`/api/dashboard/stats` | 健康检查、11 项看板统计 |
| GET | `/api/vehicles` | 车辆多表 JOIN 列表 |
| GET | `/api/orders` | 订单联合查询 |
| GET | `/api/pois`、`/api/poi/categories` | POI 分页/搜索、6 分类计数 |
| GET | `/api/master/vehicle-types`、`/cargo-categories`、`/match/matrix`、`/match/types` | 基础数据与匹配矩阵 |
| GET | `/api/match/vehicles?orderId=` | **车型-货物匹配评分**（候选车+评分+理由） |
| POST | `/api/dispatch/assign` | 人工/自动统一派车（body：`{"orderId":1,"vehicleId":4}`），事务写 dispatch+订单+回写路线 |
| POST | `/api/gps/report` | 北斗模块 HTTP+JSON 上报 |
| GET | `/api/gps/latest`、`/api/gps/history?vehicleId=` | 最新定位、单车轨迹 |
| POST | `/api/simulation/start`、`/stop`、`/reset` | 仿真生命周期（start body：`{"vehicleCount":12,"autoOrders":true}`） |
| GET | `/api/simulation/status`、`/snapshot`、`/logs` | 状态计数、沙盘快照（车辆+异常）、状态变更时间线 |

统一返回包装 `ApiResult{success,message,data}`；全局 CORS 在 `config/CorsConfig.java` 配置，无需在控制器重复 `@CrossOrigin`。

## 七、仿真引擎说明

- **节拍**：默认 2 秒/拍=5 仿真分钟（`transport.sim.tick-ms`、`transport.sim.minutes-per-tick` 可配）。
- **6 状态规则表**（`vehicle_status.allowed_next`）：IDLE→LOADING/REFUEL/MAINTAIN；LOADING→TRANSPORT/IDLE；TRANSPORT→UNLOADING/IDLE；UNLOADING→IDLE/LOADING；REFUEL→IDLE/LOADING；MAINTAIN→IDLE。任何跳转先查规则表，非法跳转被拦截并写告警日志。
- **司机偏好**：空闲车决策优先级＝油量不足去最近加油站（<20L）＞ 2% 概率例行保养 ＞ 找货抢单；抢单时"常跑路线"（`driver_route`）加 15 分。
- **运动学**：每拍里程=车型平均速度×异常限速系数×5/60；油=里程×百公里油耗/100；位置沿起终点线性插值并算航向角。
- **无限循环**：`OrderGeneratorService` 每 6 拍检查待处理订单，按 `factory_warehouse` 厂仓关系自动补单（采购仓→厂，否则厂→仓）。
- **异常恢复**：交通异常 2-4 拍后自动解除；停止/复位会解除全部未决异常；复位会取消在途调度单并重开订单，杜绝"幽灵任务"；停止后再启动可接续 DISPATCHED/IN_TRANSIT 任务。
- **GIS 降级**：未配高德 Key（`transport.amap.key` 为空）时用 Haversine 直线距离×1.3 弯路系数、50km/h 估算耗时，并把新路线回写 `route` 表。

## 八、常见问题排查

1. **后端启动报数据库连接失败/Access denied**：确认 XAMPP MySQL 已启动；XAMPP 的 root 密码为空（`password=` 后什么都不写），独立 MySQL 写自己的密码。
2. **端口冲突（8888/5173/3306）**：`netstat -ano | findstr :8888` 找占用进程；或改 `application.properties` 的 `server.port` 与 `frontend/src/api/http.js` 的 baseURL。
3. **PowerShell 调 POST 接口 JSON 解析失败**：命令行引号会被吞，把 JSON 写入文件再 `curl.exe --data-binary "@文件.json"`。
4. **控制台 JSON 中文显示乱码**：PowerShell GBK 显示问题，数据库与接口均为 UTF-8，前端显示正常，不影响数据。
5. **跨域错误**：已用全局 `CorsConfig` 放行全部来源；如自定义了 Controller 上的 `@CrossOrigin` 请删除以免重复。
6. **实机模式提示无 pymodbus**：`pip install pymodbus`，并确认串口号（设备管理器查看 COM 号）、波特率与从站地址。
7. **HikariPool 偶发 connection closed WARN**：机器休眠/系统时间跳变导致，连接池会自动重建连接，不影响运行。
