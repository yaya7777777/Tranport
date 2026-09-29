# -*- coding: utf-8 -*-
"""
POI 低成本采集/生成工具（任务书第 2 项："通过爬虫低成本更新 POI 数据"）
=======================================================================
POI 分类与数据库 poi_category 的对应关系：
    1 工厂 / 2 仓库 / 3 加油站 / 5 停车场 / 6 物流中心 / 7 修车厂

提供两种数据来源：

1. 高德开放平台在线采集（真实 POI，需要申请 Web 服务 Key）：
       pip install requests            # 仅在线模式需要；不用 requests 时本脚本零第三方依赖
       python poi_crawler.py --mode amap --key 你的高德KEY --total 500
       # 也可用环境变量传 Key：set AMAP_KEY=你的KEY
   原理：调用高德「搜索 POI 2.0 / place/around」周边搜索接口，按 6 类关键词分页抓取。

2. 离线模拟生成（无需 Key、无需联网，默认模式）：
       python poi_crawler.py --mode offline
   全四川均匀散布，固定数量目标（共 1310 条）：
       工厂 1000 / 仓库 200 / 加油站 20 / 修车厂 30 / 停车场 50 / 物流中心 10
   原理：以四川 21 市州及主要区县为锚点（盆地密、川西高原稀），做高斯散布；
   同时按 POI 重建 factory / warehouse / factory_warehouse 厂仓关系主数据，
   保证仿真订单可在全省任意工厂-仓库之间生成。

输出：database/poi_generated.sql（可用 --out 修改路径），幂等设计——
      先按外键依赖倒序清理 poi 关联业务数据，再插入新主数据，可重复导入。
      导入方式：mysql -uroot -p123456 transport < database/poi_generated.sql
"""

import argparse
import json
import math
import os
import random
import sys
import time
import urllib.parse
import urllib.request

# (category_id, 分类名, 高德搜索关键词)
CATEGORIES = [
    (1, "工厂", "工厂|制造厂|加工厂"),
    (2, "仓库", "仓库|仓储"),
    (3, "加油站", "加油站"),
    (5, "停车场", "停车场"),
    (6, "物流中心", "物流中心|物流园|货运市场"),
    (7, "修车厂", "汽修|修车厂|汽车维修"),
]

# ---------------------------------------------------------------------------
# 离线生成：全四川锚点（经度, 纬度, 地名, 市州, 权重, 是否川西高原）
# 盆地城市工业集中→权重高、散布小；川西高原地广人稀→权重低、散布大
# ---------------------------------------------------------------------------
SICHUAN_ANCHORS = [
    # 东部盆地区（成都平原+川南+川东北）
    (104.0668, 30.5728, "成都",   "成都市",   6, False),
    (104.6796, 31.4676, "绵阳",   "绵阳市",   3, False),
    (104.3979, 31.1270, "德阳",   "德阳市",   3, False),
    (105.8417, 32.4336, "广元",   "广元市",   2, False),
    (105.5713, 30.5133, "遂宁",   "遂宁市",   2, False),
    (106.0830, 30.7953, "南充",   "南充市",   3, False),
    (106.7450, 31.8690, "巴中",   "巴中市",   2, False),
    (107.4680, 31.2090, "达州",   "达州市",   3, False),
    (108.0300, 32.0800, "万源",   "达州市",   1, False),
    (106.6333, 30.4564, "广安",   "广安市",   2, False),
    (104.6419, 30.1227, "资阳",   "资阳市",   2, False),
    (103.8314, 30.0490, "眉山",   "眉山市",   2, False),
    (103.7656, 29.5520, "乐山",   "乐山市",   2, False),
    (103.0421, 29.9772, "雅安",   "雅安市",   1, False),
    (104.7787, 29.3393, "自贡",   "自贡市",   2, False),
    (105.0662, 29.5800, "内江",   "内江市",   2, False),
    (105.4425, 28.8717, "泸州",   "泸州市",   2, False),
    (104.6308, 28.7513, "宜宾",   "宜宾市",   3, False),
    (101.7188, 26.5823, "攀枝花", "攀枝花市", 1, True),
    # 川西高原 / 山地区（甘孜、阿坝、凉山）
    (102.2214, 31.8998, "马尔康", "阿坝州",   1, True),
    (103.6000, 32.6500, "松潘",   "阿坝州",   1, True),
    (102.9600, 33.5800, "若尔盖", "阿坝州",   1, True),
    (104.2400, 33.2600, "九寨沟", "阿坝州",   1, True),
    (101.9639, 30.0506, "康定",   "甘孜州",   1, True),
    (101.0100, 30.0300, "雅江",   "甘孜州",   1, True),
    (100.6800, 31.3900, "炉霍",   "甘孜州",   1, True),
    (99.9900,  31.6200, "甘孜县", "甘孜州",   1, True),
    (99.1100,  30.0000, "巴塘",   "甘孜州",   1, True),
    (98.1000,  32.9800, "石渠",   "甘孜州",   1, True),
    (102.2647, 27.8816, "西昌",   "凉山州",   2, True),
    (102.2500, 26.6600, "会理",   "凉山州",   1, True),
    (102.8400, 28.0100, "昭觉",   "凉山州",   1, True),
]

# 各分类固定数量目标（共 1310 条，全部落在四川境内）
OFFLINE_TARGETS = [
    (1, "工厂",   1000),
    (2, "仓库",   200),
    (3, "加油站", 20),
    (7, "修车厂", 30),
    (5, "停车场", 50),
    (6, "物流中心", 10),
]

# 四川经纬度边界（越界坐标会被拉回边界内）
SC_LNG_MIN, SC_LNG_MAX = 97.2, 108.7
SC_LAT_MIN, SC_LAT_MAX = 25.9, 34.5
SIGMA_BASIN = (0.16, 0.13)   # 盆地锚点散布（约 15km）
SIGMA_WEST  = (0.45, 0.38)   # 川西锚点散布（约 45km，覆盖辽阔高原）

# 采集批次标记（写入 contact_person，用于幂等清理与来源追溯）
AMAP_TAG = "AMAP_CRAWLER"
OFFLINE_TAG = "SICHUAN_GEN"

# 离线名称素材
INDUSTRY_WORDS = ["装备", "汽车零部件", "食品", "电子", "建材", "医药", "纺织", "机械",
                  "新材料", "包装", "家具", "食品加工", "新能源", "冷链", "金属制品"]
WAREHOUSE_WORDS = ["综合", "冷链", "快消品", "电商", "建材", "农资", "保税", "中转"]
LOGISTICS_WORDS = ["传化", "顺丰", "京东", "中通", "圆通", "安博", "普洛斯", "万纬"]
REPAIR_WORDS = ["宏达", "顺发", "捷安", "利丰", "众信", "华辉", "通达", "迅捷", "安顺", "驰诚"]
PARK_PLACES = ["枢纽站", "物流园", "客运中心", "产业园", "工业园", "经开区", "高新区", "火车站", "机场", "批发市场"]
ROAD_NAMES = ["G5京昆高速", "G42沪蓉高速", "G93成渝环线", "G75兰海高速", "G4217蓉昌高速",
              "G318国道", "G317国道", "G108国道", "G248国道", "G245国道"]
CONTACT_NAMES = ["王厂长", "李厂长", "张厂长", "刘经理", "陈经理", "赵经理", "周经理", "吴经理"]
FACTORY_TYPES = ["制造厂", "加工厂", "组装厂"]
WAREHOUSE_TYPES = ["原料仓", "成品仓", "中转仓", "冷库", "综合仓"]


def sql_escape(text):
    """转义 SQL 字符串中的单引号。"""
    if text is None:
        return ""
    return str(text).replace("\\", "\\\\").replace("'", "''")


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def scatter_point(rng, anchor):
    """在锚点附近做高斯散布并限制在四川边界内，返回 (lng, lat)。"""
    lng0, lat0, _name, _pref, _w, is_west = anchor
    sdx, sdy = SIGMA_WEST if is_west else SIGMA_BASIN
    lng = clamp(lng0 + rng.gauss(0, sdx), SC_LNG_MIN, SC_LNG_MAX)
    lat = clamp(lat0 + rng.gauss(0, sdy), SC_LAT_MIN, SC_LAT_MAX)
    return round(lng, 7), round(lat, 7)


def make_name(rng, cid, city, seq):
    """按分类生成拟真 POI 名称（带城市前缀，全省唯一编号）。"""
    if cid == 1:
        return f"{city}{rng.choice(INDUSTRY_WORDS)}工厂{seq:04d}号"
    if cid == 2:
        return f"{city}{rng.choice(WAREHOUSE_WORDS)}仓库{seq:04d}号"
    if cid == 3:
        return f"{rng.choice(ROAD_NAMES)}{city}加油站{seq:03d}号"
    if cid == 7:
        return f"{city}{rng.choice(REPAIR_WORDS)}汽修厂{seq:03d}号"
    if cid == 5:
        return f"{city}{rng.choice(PARK_PLACES)}停车场{seq:03d}号"
    return f"{rng.choice(LOGISTICS_WORDS)}{city}物流中心{seq:02d}号"


def allocate(rng, total):
    """把 total 个点按锚点权重轮转散布到全省（返回与锚点顺序无关的均匀覆盖）。"""
    slots = []
    for a in SICHUAN_ANCHORS:
        slots += [a] * a[4]
    pts = []
    for i in range(total):
        a = slots[i % len(slots)]
        lng, lat = scatter_point(rng, a)
        pts.append((a, lng, lat))
    return pts


def gen_offline():
    """离线生成全四川 POI（固定数量目标），返回 POI 行 + 厂仓主数据行。

    POI 行:  (poi_id, name, cid, lng, lat, address, capacity, tag, phone)
    返回 dict: {"pois": [...], "factories": [...], "warehouses": [...], "relations": [...]}
    """
    rng = random.Random(20260929)  # 固定随机种子，保证多次生成结果一致
    pois, factories, warehouses = [], [], []
    seq_total = 0
    next_poi_id = 1
    fac_seq = wh_seq = 0

    for cid, cname, count in OFFLINE_TARGETS:
        pts = allocate(rng, count)
        for anchor, lng, lat in pts:
            seq_total += 1
            name_, pref = anchor[2], anchor[3]
            poi_id = next_poi_id
            next_poi_id += 1
            name = make_name(rng, cid, name_, seq_total)
            # 市辖区锚点（地名与市州同名）地址不重复拼接，如 四川省成都市
            address = f"四川省{pref}" if pref.startswith(name_) else f"四川省{pref}{name_}"
            if cid == 1:
                capacity = round(rng.uniform(100, 950), 1)
                phone = f"1{rng.randint(30, 99)}{rng.randint(10000000, 99999999)}"
            elif cid == 2:
                capacity = round(rng.uniform(150, 900), 1)
                phone = f"1{rng.randint(30, 99)}{rng.randint(10000000, 99999999)}"
            elif cid == 3:
                capacity = round(rng.uniform(60, 120), 1)
                phone = None
            elif cid == 6:
                capacity = round(rng.uniform(800, 2000), 1)
                phone = None
            else:
                capacity = None
                phone = None
            pois.append((poi_id, name, cid, lng, lat, address, capacity,
                         OFFLINE_TAG, phone))
            # 工厂/仓库 POI 同步生成实体记录（factory/warehouse 表依赖 poi_id）
            if cid == 1:
                fac_seq += 1
                factories.append((f"F{fac_seq:04d}", name,
                                  rng.choice(FACTORY_TYPES), "采购,生产,销售",
                                  poi_id, rng.choice(CONTACT_NAMES),
                                  f"139{rng.randint(10000000, 99999999)}"))
            elif cid == 2:
                wh_seq += 1
                warehouses.append((f"WH{wh_seq:04d}", name,
                                   rng.choice(WAREHOUSE_TYPES), capacity,
                                   poi_id, rng.choice(CONTACT_NAMES),
                                   f"138{rng.randint(10000000, 99999999)}"))

    # 厂仓关系：每个工厂连最近的 2 个仓库（PROCURE 原料采购 / PRODUCE 成品入仓），
    # 再补 1 条 SALE（第 3 近仓库），保证订单生成器有充足的采购/生产/销售关系。
    # factory_id/warehouse_id 按生成顺序即 1..N，与 SQL 显式主键一一对应。
    relations = []
    wh_poi_ids = [w[4] for w in warehouses]
    wh_xy = {pid: next((p[3], p[4]) for p in pois if p[0] == pid) for pid in wh_poi_ids}
    for f in factories:
        f_poi = f[4]
        fp = next((p[3], p[4]) for p in pois if p[0] == f_poi)
        near_ids = sorted(wh_poi_ids, key=lambda pid: (wh_xy[pid][0] - fp[0]) ** 2
                          + (wh_xy[pid][1] - fp[1]) ** 2)
        f_no = f[0]
        wid1 = wh_poi_ids.index(near_ids[0]) + 1
        wid2 = wh_poi_ids.index(near_ids[1]) + 1
        wid3 = wh_poi_ids.index(near_ids[2]) + 1
        relations.append((f_no, wid1, "PROCURE", "原料采购直供"))
        relations.append((f_no, wid2, "PRODUCE", "成品就近入仓"))
        relations.append((f_no, wid3, "SALE", "成品外运分销"))
    return {"pois": pois, "factories": factories,
            "warehouses": warehouses, "relations": relations}


def fetch_amap(key, total, center="104.0668,30.5728", radius=30000):
    """
    调用高德「周边搜索 / place/around」接口，按分类关键词分页抓取真实 POI。
    """
    rows, seen_locations = [], set()
    per_category = math.ceil(total / len(CATEGORIES))
    for cid, cname, keyword in CATEGORIES:
        page, got = 1, 0
        while got < per_category:
            params = {
                "keywords": keyword,
                "location": center,
                "radius": radius,
                "offset": 20,
                "page": page,
                "key": key,
                "extensions": "base",
                "output": "JSON",
            }
            url = "https://restapi.amap.com/v3/place/around?" + urllib.parse.urlencode(params)
            try:
                with urllib.request.urlopen(url, timeout=10) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
            except Exception as e:
                print(f"[WARN] {cname} 第{page}页请求失败：{e}，跳过本批")
                break
            if data.get("status") != "1":
                print(f"[ERROR] 高德返回错误：{data.get('info')}（检查 Key 类型须为 Web 服务）")
                break

            pois = data.get("pois") or []
            if not pois:
                break  # 该分类已抓完
            for poi in pois:
                location = poi.get("location")
                if not location or "," not in location:
                    continue  # 过滤无坐标数据
                if location in seen_locations:
                    continue  # 多关键词（|分隔）可能命中同一个 POI，按坐标去重
                seen_locations.add(location)
                lng_s, lat_s = location.split(",")
                lng, lat = float(lng_s), float(lat_s)
                adname = poi.get("adname") or "成都市"
                rows.append((poi.get("name", "未命名POI"), cid, round(lng, 7), round(lat, 7),
                             f"{poi.get('adname') or ''}{poi.get('address') or ''}", None,
                             AMAP_TAG, poi.get("id", "")))
                got += 1
                if got >= per_category:
                    break
            page += 1
            if page > 45:  # 高德单关键词约 900 条上限保护
                break
            time.sleep(0.15)  # 遵守 QPS 限制，避免被限流
        print(f"  {cname}：已采集 {got} 条")
    return rows[:total]


def write_amap_sql(rows, out_path):
    """高德在线采集模式：仅写 POI（按批次标记幂等清理）。"""
    lines = [
        "-- POI 采集数据（由 tools/poi_crawler.py 自动生成，请勿手工编辑）",
        f"-- 来源：高德开放平台 place/around，共 {len(rows)} 条",
        "-- 幂等导入：先清理本工具历史批次（按 contact_person 标记），再插入",
        "SET NAMES utf8mb4;",
        f"DELETE FROM poi WHERE contact_person = '{AMAP_TAG}';",
        "INSERT INTO poi (poi_name,category_id,longitude,latitude,address,capacity,contact_person,contact_phone) VALUES",
    ]
    value_lines = []
    for name, cid, lng, lat, addr, capacity, contact, phone in rows:
        cap_sql = "NULL" if capacity is None else f"{capacity}"
        value_lines.append(
            f"('{sql_escape(name)}',{cid},{lng},{lat},'{sql_escape(addr)}',{cap_sql},"
            f"'{sql_escape(contact)}','{sql_escape(phone)}')"
        )
    lines.append(",\n".join(value_lines) + ";")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"已生成 {len(rows)} 条 POI 的 SQL 文件：{out_path}")


def chunked(seq, size):
    """把列表按 size 分块（控制单条 INSERT 长度）。"""
    for i in range(0, len(seq), size):
        yield seq[i:i + size]


def write_sichuan_sql(data, out_path):
    """离线全四川模式：清理旧业务数据 + 重建 POI/工厂/仓库/厂仓关系主数据。

    POI 使用显式主键插入（工厂 1..1000，仓库 1001..1200，其余顺延），
    factory/warehouse/factory_warehouse 的外键引用因此完全确定，可重复导入。
    """
    pois, facs, whs, rels = data["pois"], data["factories"], data["warehouses"], data["relations"]
    lines = [
        "-- ============================================================",
        "-- 全四川 POI 主数据（由 tools/poi_crawler.py --mode offline 生成）",
        f"-- POI 共 {len(pois)} 条：工厂 1000 / 仓库 200 / 加油站 20 / 修车厂 30 / 停车场 50 / 物流中心 10",
        f"-- 同步重建主数据：工厂 {len(facs)} / 仓库 {len(whs)} / 厂仓关系 {len(rels)}",
        "-- 导入会清空 poi 关联业务数据（订单/调度/车辆/路线等），导入后重新开始仿真即可",
        "-- 幂等：重复导入前会先按外键依赖倒序清理旧数据",
        "-- 导入命令：mysql -uroot -p123456 transport < " + out_path,
        "-- ============================================================",
        "SET NAMES utf8mb4;",
        "",
        "-- 1. 修复 poi_category（补充 修车厂 分类，幂等）",
        ("INSERT INTO poi_category (category_name,description) "
         "SELECT '修车厂','车辆维修保养场' WHERE NOT EXISTS "
         "(SELECT 1 FROM (SELECT 1 FROM poi_category WHERE category_name='修车厂') t);"),
        "",
        "-- 2. 清空 poi 关联业务数据（外键依赖倒序）",
        "DELETE FROM gps_data;",
        "DELETE FROM vehicle_status_log;",
        "DELETE FROM traffic_anomaly;",
        "DELETE FROM dispatch;",
        "DELETE FROM cargo_order;",
        "DELETE FROM driver_route;",
        "DELETE FROM factory_warehouse;",
        "DELETE FROM route;",
        "DELETE FROM vehicle;",
        "DELETE FROM factory;",
        "DELETE FROM warehouse;",
        "DELETE FROM poi;",
        "DELETE FROM simulation_record;",
        "",
        f"-- 3. POI（{len(pois)} 条，显式主键保证 factory/warehouse 引用确定）",
        "INSERT INTO poi (poi_id,poi_name,category_id,longitude,latitude,address,capacity,contact_person,contact_phone) VALUES",
    ]
    poi_values = []
    for pid, name, cid, lng, lat, addr, cap, tag, phone in pois:
        cap_sql = "NULL" if cap is None else f"{cap}"
        ph_sql = "NULL" if phone is None else f"'{phone}'"
        poi_values.append(
            f"({pid},'{sql_escape(name)}',{cid},{lng},{lat},'{sql_escape(addr)}',{cap_sql},"
            f"'{tag}',{ph_sql})"
        )
    lines.append(",\n".join(poi_values) + ";")

    lines += ["", f"-- 4. 工厂实体（{len(facs)} 条，对应全部工厂 POI，显式主键）",
              "INSERT INTO factory (factory_id,factory_no,factory_name,factory_type,business_scope,poi_id,contact_person,contact_phone) VALUES"]
    fac_values = []
    for i, f in enumerate(facs):
        fac_values.append(
            f"({i + 1},'{f[0]}','{sql_escape(f[1])}','{f[2]}','{f[3]}',{f[4]},'{f[5]}','{f[6]}')"
        )
    lines.append(",\n".join(fac_values) + ";")

    lines += ["", f"-- 5. 仓库实体（{len(whs)} 条，对应全部仓库 POI，显式主键）",
              "INSERT INTO warehouse (warehouse_id,warehouse_no,warehouse_name,warehouse_type,capacity,poi_id,contact_person,contact_phone) VALUES"]
    wh_values = []
    for i, w in enumerate(whs):
        cap_sql = "NULL" if w[3] is None else f"{w[3]}"
        wh_values.append(
            f"({i + 1},'{w[0]}','{sql_escape(w[1])}','{w[2]}',{cap_sql},{w[4]},'{w[5]}','{w[6]}')"
        )
    lines.append(",\n".join(wh_values) + ";")

    lines += ["", f"-- 6. 厂仓关系（{len(rels)} 条：每工厂 2 近仓 PROCURE/PRODUCE + 第 3 近仓 SALE）",
              "INSERT INTO factory_warehouse (factory_id,warehouse_id,relation_type,description) VALUES"]
    rel_values = []
    for idx, (f_no, wid, rtype, desc) in enumerate(rels):
        fac_id = idx // 3 + 1  # 每工厂固定 3 条关系，factory_id 与第 4 步显式主键一致
        rel_values.append(f"({fac_id},{wid},'{rtype}','{desc}')")
    lines.append(",\n".join(rel_values) + ";")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"已生成全四川主数据 SQL：{out_path}")
    print(f"  POI {len(pois)} / 工厂 {len(facs)} / 仓库 {len(whs)} / 厂仓关系 {len(rels)}")
    print(f"导入命令：mysql -uroot -p123456 transport < {out_path}")


def parse_args():
    parser = argparse.ArgumentParser(description="POI 采集/生成工具（高德在线 / 全四川离线）")
    parser.add_argument("--mode", choices=["amap", "offline"], default="offline",
                        help="amap=高德API真实采集（需Key），offline=全四川离线生成（默认）")
    parser.add_argument("--key", default=os.environ.get("AMAP_KEY", ""),
                        help="高德 Web 服务 Key（也可用环境变量 AMAP_KEY）")
    parser.add_argument("--total", type=int, default=500,
                        help="amap 模式目标 POI 总数")
    parser.add_argument("--center", default="104.0668,30.5728",
                        help="amap 周边搜索中心点 经度,纬度")
    parser.add_argument("--radius", type=int, default=30000,
                        help="amap 周边搜索半径（米）")
    parser.add_argument("--out", default=os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "database", "poi_generated.sql"),
        help="输出 SQL 文件路径")
    return parser.parse_args()


if __name__ == "__main__":
    ARGS = parse_args()
    if ARGS.mode == "amap":
        if not ARGS.key:
            print("在线采集需要高德 Key：--key 或设置环境变量 AMAP_KEY（申请：https://lbs.amap.com）")
            sys.exit(1)
        print(f"开始高德周边采集，中心 {ARGS.center} 半径 {ARGS.radius}m，目标 {ARGS.total} 条 ...")
        rows = fetch_amap(ARGS.key, ARGS.total, ARGS.center, ARGS.radius)
        write_amap_sql(rows, ARGS.out)
    else:
        print("离线生成全四川 POI（工厂1000/仓库200/加油站20/修车厂30/停车场50/物流中心10）...")
        data = gen_offline()
        write_sichuan_sql(data, ARGS.out)
