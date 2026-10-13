# -*- coding: utf-8 -*-
"""从新高德 POI 重建 factory/warehouse/factory_warehouse 主数据，
并把车辆、司机分散到四川全省（不再堆积成都）。

用法：python rebuild_sichuan_master.py
输出：rebuild_sichuan_master.sql（在 database/ 目录）
"""
import math
import os
import random
import subprocess
import time
import urllib.request
import urllib.parse

DB_HOST = "127.0.0.1"
DB_PORT = 3306
DB_USER = "root"
DB_PASS = "123456"
DB_NAME = "transport"
OUT_SQL = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "database", "rebuild_sichuan_master.sql")

AMAP_KEY = "b3b6dcf6c1c7e590b664064feea5f56c"

# 四川 21 市州（用于司机地址分散）
CITIES = ["成都市", "绵阳市", "德阳市", "广元市", "遂宁市", "南充市", "巴中市",
          "达州市", "广安市", "资阳市", "眉山市", "乐山市", "雅安市", "自贡市",
          "内江市", "泸州市", "宜宾市", "攀枝花市", "阿坝藏族羌族自治州",
          "甘孜藏族自治州", "凉山彝族自治州"]
DISTRICT_SUFFIX = ["市中区", "主城区", "高新区", "经开区", "工业园区", "新区"]

# 工厂类型 → 可生产货物分类 ID（与后端 OrderGeneratorService 保持一致）
FACTORY_CARGO_MAP = {
    "食品厂": {1}, "建材厂": {2}, "电子厂": {3},
    "加工厂": {4, 5}, "制造厂": {2, 4, 5}, "组装厂": {3, 5},
}
# 仓库类型 → 可存放货物分类 ID
WAREHOUSE_CARGO_MAP = {
    "冷库": {1}, "原料仓": {2, 4}, "成品仓": {3, 5}, "电商仓": {3, 5},
    "中转仓": {1, 2, 3, 4, 5}, "综合仓": {1, 2, 3, 4, 5},
}

# 四川各市州车牌代码（川 A-Z）
PLATE_PREFIX = {
    "成都": "川A", "绵阳": "川B", "自贡": "川C", "攀枝花": "川D",
    "泸州": "川E", "德阳": "川F", "广元": "川H", "遂宁": "川J",
    "内江": "川K", "乐山": "川L", "资阳": "川M", "宜宾": "川Q",
    "南充": "川R", "达州": "川S", "雅安": "川T", "阿坝": "川U",
    "甘孜": "川V", "凉山": "川W", "广安": "川X", "巴中": "川Y",
    "眉山": "川Z",
}


def plate_prefix_for_address(address):
    """根据 POI 地址判断所在市州，返回对应车牌前缀（如 '川A'）。"""
    for city, prefix in PLATE_PREFIX.items():
        if city in (address or ""):
            return prefix
    return "川A"  # 兜底成都


def amap_regeo_city(lng, lat):
    """调用高德逆地理编码接口，返回坐标所在市州名（如 '泸州市'）。"""
    url = ("https://restapi.amap.com/v3/geocode/regeo?key=" + AMAP_KEY
           + "&location=" + f"{lng:.6f},{lat:.6f}"
           + "&extensions=base&radius=1000")
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            import json
            data = json.loads(resp.read().decode("utf-8"))
        if data.get("status") == "1":
            addr = data["regeocode"].get("addressComponent", {})
            city = addr.get("city") or addr.get("province", "")
            # 直辖市/自治州时 city 可能为空，取 province
            return city
    except Exception as e:
        print(f"  逆地理编码失败 ({lng},{lat}): {e}")
    return ""


def plate_prefix_for_coord(lng, lat, address):
    """优先用高德逆地理编码获取市州，失败则回退地址匹配。"""
    city = amap_regeo_city(lng, lat)
    if city:
        for c, prefix in PLATE_PREFIX.items():
            if c in city:
                return prefix
    return plate_prefix_for_address(address)

rng = random.Random(20261013)


def haversine(lng1, lat1, lng2, lat2):
    R = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dlat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlng / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def q(sql):
    """执行查询 SQL，返回行列表。"""
    cmd = ["docker", "exec", "transport-mysql", "mysql", "-N", "-B",
           "-h127.0.0.1", f"-P{DB_PORT}", f"-u{DB_USER}", f"-p{DB_PASS}", DB_NAME, "-e", sql]
    out = subprocess.check_output(cmd, text=True, encoding="utf-8")
    rows = []
    for line in out.strip().splitlines():
        rows.append(line.split("\t"))
    return rows


def esc(s):
    if s is None:
        return ""
    return str(s).replace("\\", "\\\\").replace("'", "''")


def main():
    # 1. 提取新高德工厂 POI（cid=1）和仓库 POI（cid=2）
    factory_pois = q(
        "SELECT poi_id, poi_name, address, longitude, latitude FROM poi "
        "WHERE contact_person='AMAP_CRAWLER' AND category_id=1 ORDER BY poi_id")
    warehouse_pois = q(
        "SELECT poi_id, poi_name, address, longitude, latitude FROM poi "
        "WHERE contact_person='AMAP_CRAWLER' AND category_id=2 ORDER BY poi_id")
    print(f"高德工厂 POI: {len(factory_pois)} 个，仓库 POI: {len(warehouse_pois)} 个")

    # 工厂/仓库类型列表 + 按名称推断类型的函数（须在构建 wh_with_xy 之前定义）
    fac_types = ["制造厂", "加工厂", "组装厂", "食品厂", "电子厂", "建材厂"]
    wh_types = ["原料仓", "成品仓", "中转仓", "冷库", "综合仓", "电商仓"]

    def infer_factory_type(name):
        """根据工厂名称关键词推断工厂类型，避免'钢管厂标成电子厂'这类错误。"""
        n = name or ""
        if any(k in n for k in ["食品", "肉", "肠", "蔬菜", "水果", "餐饮", "面包",
                                 "蛋糕", "鲜", "奶", "茶", "酒", "饮料", "调味", "粮油",
                                 "烤", "零食", "面", "烘焙", "熟食", "卤", "厨"]):
            return "食品厂"
        if any(k in n for k in ["建材", "钢", "管", "铁", "门窗", "门业", "玻璃",
                                 "水泥", "瓷砖", "石板", "石膏", "涂料", "防水", "保温",
                                 "木材", "木业", "陶瓷", "卫浴", "厨卫", "全屋", "装饰"]):
            return "建材厂"
        if any(k in n for k in ["电子", "电", "通信", "科技", "智能", "芯片",
                                 "半导体", "光电", "仪器", "助听", "器", "眼镜"]):
            return "电子厂"
        if any(k in n for k in ["组装", "装配", "集成"]):
            return "组装厂"
        if any(k in n for k in ["制造", "机械", "装备", "重工", "机床", "汽车",
                                 "弹簧", "五金", "铸", "床垫", "家具", "沙发"]):
            return "制造厂"
        if any(k in n for k in ["加工", "羊绒", "羊毛", "服装", "衣", "印刷",
                                 "纺织", "印染"]):
            return "加工厂"
        return rng.choice(fac_types)

    def infer_warehouse_type(name):
        """根据仓库名称关键词推断仓库类型。"""
        n = name or ""
        if any(k in n for k in ["冷", "冻", "鲜", "冰"]):
            return "冷库"
        if any(k in n for k in ["原料", "材料"]):
            return "原料仓"
        if "成品" in n:
            return "成品仓"
        if any(k in n for k in ["电商", "快递", "配送"]):
            return "电商仓"
        if any(k in n for k in ["中转", "物流", "货运"]):
            return "中转仓"
        return rng.choice(wh_types)

    # 预计算每个仓库的类型（按名称推断），并随 wh_with_xy 一起传递
    wh_with_xy = []
    for w in warehouse_pois:
        pid, name, addr, lng, lat = int(w[0]), w[1], w[2], float(w[3]), float(w[4])
        wtype = infer_warehouse_type(name)
        wh_with_xy.append((pid, name, addr, lng, lat, wtype))
    # poi_id -> warehouse_id（warehouse 按 poi_id 升序插入，warehouse_id = 索引+1）
    poi_to_whid = {w[0]: i + 1 for i, w in enumerate(wh_with_xy)}

    # 2. 为每个工厂生成 3 条厂仓关系（近/中/远各一，保证跨市分散）
    contact_names = ["王厂长", "李经理", "张主任", "刘主管", "陈经理", "赵厂长"]

    fac_inserts = []
    wh_inserts = []
    rel_inserts = []

    fac_id = 1
    for fp in factory_pois:
        pid, name, addr, lng, lat = int(fp[0]), fp[1], fp[2], float(fp[3]), float(fp[4])
        fac_no = f"F{fac_id:04d}"
        ftype = infer_factory_type(name)
        scope = "采购,生产,销售"
        contact = rng.choice(contact_names)
        phone = f"1{rng.randint(30, 99)}{rng.randint(10000000, 99999999)}"
        fac_inserts.append(
            f"({fac_id},'{esc(fac_no)}','{esc(name)}','{ftype}','{scope}',{pid},'{contact}','{phone}')")

        # 计算到每个仓库的距离，分近/中/远三桶各选一个
        # 优先选择与工厂类型货物兼容的仓库（如食品厂配冷库，电子厂配成品仓）
        fac_cats = FACTORY_CARGO_MAP.get(ftype, set(range(1, 6)))
        dists = []
        for w in wh_with_xy:
            d = haversine(lng, lat, w[3], w[4])
            # w[5] 是 warehouse_type（wh_with_xy 需要带类型）
            wh_cats = WAREHOUSE_CARGO_MAP.get(w[5], set(range(1, 6)))
            compatible = 0 if (fac_cats & wh_cats) else 1  # 0=兼容优先
            dists.append((d, compatible, w[0]))
        # 先按兼容排序（兼容的在前），同兼容内按距离排序
        dists.sort(key=lambda x: (x[1], x[0]))
        n = len(dists)
        # 近：兼容仓库中最近的若干；中：中间；远：最远的兼容仓库
        compat = [x for x in dists if x[1] == 0]
        if len(compat) >= 3:
            near = compat[0][2]
            mid = compat[len(compat) // 2][2]
            far = compat[-1][2]
        else:
            # 兼容仓库不足 3 个，回退到距离分桶
            near = dists[max(0, rng.randint(0, n // 5))][2]
            mid = dists[n // 2 + rng.randint(-n // 10, n // 10)][2]
            far = dists[min(n - 1, n - rng.randint(1, max(2, n // 5)))][2]
        # 三条关系：PROCURE(采购 仓->厂) / PRODUCE(生产 厂->仓) / SALE(销售 厂->仓)
        rel_inserts.append((fac_id, poi_to_whid[near], "PROCURE", "原料采购直供"))
        rel_inserts.append((fac_id, poi_to_whid[mid], "PRODUCE", "成品就近入仓"))
        rel_inserts.append((fac_id, poi_to_whid[far], "SALE", "成品跨市外运"))
        fac_id += 1

    wh_id = 1
    for w in wh_with_xy:
        pid, name, addr, wtype = w[0], w[1], w[2], w[5]
        wh_no = f"WH{wh_id:04d}"
        cap = round(rng.uniform(150, 900), 1)
        contact = rng.choice(contact_names)
        phone = f"1{rng.randint(30, 99)}{rng.randint(10000000, 99999999)}"
        wh_inserts.append(
            f"({wh_id},'{esc(wh_no)}','{esc(name)}','{wtype}',{cap},{pid},'{contact}','{phone}')")
        wh_id += 1

    print(f"工厂实体: {len(fac_inserts)}，仓库实体: {len(wh_inserts)}，厂仓关系: {len(rel_inserts)}")

    # 3. 车辆分散到全省：12 辆车 -> 12 个不同市州的加油站/停车场/物流中心 POI
    # 取 AMAP_CRAWLER 中 cid in (3,5,6) 的 POI，按经纬度找最近的市州
    vehicle_pois = q(
        "SELECT poi_id, longitude, latitude, address FROM poi "
        "WHERE contact_person='AMAP_CRAWLER' AND category_id IN (3,5,6)")
    # 简单按纬度分桶选 12 个分散的点
    vpts = sorted([(int(p[0]), float(p[1]), float(p[2]), p[3]) for p in vehicle_pois],
                  key=lambda x: (x[2], x[1]))
    step = len(vpts) // 12
    chosen_vehicle_pois = [vpts[i * step + rng.randint(0, step - 1)] for i in range(12)]
    print(f"选取 {len(chosen_vehicle_pois)} 个全省分散的车辆停靠 POI")

    # 根据车辆停靠 POI 所在市州生成对应车牌代码（高德逆地理编码）
    vehicle_plates = []
    for i, p in enumerate(chosen_vehicle_pois, 1):
        prefix = plate_prefix_for_coord(p[1], p[2], p[3])
        plate = f"{prefix}{10000 + i:05d}"
        vehicle_plates.append(plate)
        print(f"  车辆 {i}: {plate}  停靠 {p[3][:30]}")
        time.sleep(0.1)  # 避免高德 QPS 限流

    # 4. 司机地址分散到 21 市州
    driver_addr_updates = []
    for i in range(1, 13):
        city = CITIES[(i - 1) % len(CITIES)]
        suffix = rng.choice(DISTRICT_SUFFIX)
        addr = f"四川省{city}{suffix}"
        driver_addr_updates.append(f"UPDATE driver SET address='{esc(addr)}' WHERE driver_id={i};")

    # 5. 写 SQL
    lines = [
        "-- ============================================================",
        "-- 重建全省 factory/warehouse/factory_warehouse 主数据 + 车辆司机全省分散",
        f"-- 工厂 {len(fac_inserts)} / 仓库 {len(wh_inserts)} / 厂仓关系 {len(rel_inserts)}",
        "-- 12 车辆停靠点 + 12 司机地址分散到四川全省各市州",
        "-- ============================================================",
        "SET NAMES utf8mb4;",
        "",
        "-- 清空旧厂仓主数据与关系（外键倒序）",
        "DELETE FROM factory_warehouse;",
        "DELETE FROM factory;",
        "DELETE FROM warehouse;",
        "ALTER TABLE factory AUTO_INCREMENT = 1;",
        "ALTER TABLE warehouse AUTO_INCREMENT = 1;",
        "ALTER TABLE factory_warehouse AUTO_INCREMENT = 1;",
        "",
        f"-- 工厂实体（{len(fac_inserts)} 条，显式主键）",
        "INSERT INTO factory (factory_id,factory_no,factory_name,factory_type,business_scope,poi_id,contact_person,contact_phone) VALUES",
        ",\n".join(fac_inserts) + ";",
        "",
        f"-- 仓库实体（{len(wh_inserts)} 条，显式主键）",
        "INSERT INTO warehouse (warehouse_id,warehouse_no,warehouse_name,warehouse_type,capacity,poi_id,contact_person,contact_phone) VALUES",
        ",\n".join(wh_inserts) + ";",
        "",
        f"-- 厂仓关系（{len(rel_inserts)} 条：每工厂 近/中/远 三仓，PROCURE/PRODUCE/SALE）",
        "INSERT INTO factory_warehouse (factory_id,warehouse_id,relation_type,description) VALUES",
        ",\n".join(f"({fid},{wid},'{rtype}','{desc}')" for fid, wid, rtype, desc in rel_inserts) + ";",
        "",
        "-- 车辆停靠点分散到全省（12 辆车 -> 12 个不同市州的加油/停车/物流 POI）",
    ]
    for i, p in enumerate(chosen_vehicle_pois, 1):
        plate = vehicle_plates[i - 1]
        lines.append(
            f"UPDATE vehicle SET plate_number='{plate}', current_poi_id={p[0]}, "
            f"current_status='IDLE', remaining_fuel=200, estimated_range=500 "
            f"WHERE vehicle_id={i};")
    lines += ["", "-- 司机地址分散到四川 21 市州"] + driver_addr_updates
    lines += ["", "-- 清理旧运行时数据（外键倒序：异常->司机路线->调度->订单->路线->日志->GPS）",
              "DELETE FROM traffic_anomaly;",
              "DELETE FROM driver_route;",
              "DELETE FROM dispatch;",
              "DELETE FROM cargo_order;",
              "DELETE FROM route;",
              "DELETE FROM vehicle_status_log;",
              "DELETE FROM gps_data;"]

    with open(OUT_SQL, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\nSQL 已生成: {OUT_SQL}")
    print("导入后车辆与司机将分散到四川全省，订单在全省厂仓间生成。")


if __name__ == "__main__":
    main()
