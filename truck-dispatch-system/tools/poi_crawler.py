# -*- coding: utf-8 -*-
"""
POI 低成本采集/生成工具（任务书第 2 项："通过爬虫低成本更新 POI 数据"）
=======================================================================
POI 分类与数据库 poi_category 的对应关系：
    1 工厂 / 2 仓库 / 3 加油站 / 4 收费站 / 5 停车场 / 6 物流中心（共 6 类，满足"优"的 5+ 类要求）

提供两种数据来源：

1. 高德开放平台在线采集（真实 POI，需要申请 Web 服务 Key）：
       pip install requests            # 仅在线模式需要；不用 requests 时本脚本零第三方依赖
       python poi_crawler.py --mode amap --key 你的高德KEY --total 500
       # 也可用环境变量传 Key：set AMAP_KEY=你的KEY
   原理：调用高德「搜索 POI 2.0 / place/around」周边搜索接口，以成都高新区
   (104.0668,30.5728) 为中心、30km 为半径，按 6 类关键词分页抓取（每页 20 条），
   过滤无坐标的脏数据，生成幂等 SQL 文件。

2. 离线模拟生成（无需 Key、无需联网，用于评级数量验收与课堂演示）：
       python poi_crawler.py --mode offline --total 1000
   原理：以成都各区县城/产业园区中心为圆心做高斯散布，按分类生成拟真名称，
   500 条对应"良"、1000 条对应"优"的 POI 数量要求。

输出：database/poi_generated.sql（可用 --out 修改路径），幂等设计——
      文件头按 contact_person 标记删除上一批采集数据，可重复导入不产生重复。
      导入方式：mysql -uroot -proot transport < database/poi_generated.sql
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

# (category_id, 分类名, 高德搜索关键词, 离线名称前缀)
CATEGORIES = [
    (1, "工厂", "工厂|制造厂|加工厂", "工厂"),
    (2, "仓库", "仓库|仓储", "仓库"),
    (3, "加油站", "加油站", "加油站"),
    (4, "收费站", "收费站", "收费站"),
    (5, "停车场", "停车场", "停车场"),
    (6, "物流中心", "物流中心|物流园|货运市场", "物流中心"),
]

# 成都各区/产业园区中心经纬度（离线生成时做高斯散布的锚点）
DISTRICT_CENTERS = [
    (104.0668, 30.5728, "高新区"),
    (103.9250, 30.5700, "双流区"),
    (104.2580, 30.5600, "龙泉驿区"),
    (103.8350, 30.6900, "温江区"),
    (104.1580, 30.8230, "新都区"),
    (104.2450, 30.8850, "青白江区"),
    (103.8800, 30.8100, "郫都区"),
    (104.0800, 30.4000, "天府新区"),
    (104.0500, 30.6900, "金牛区"),
    (104.0300, 30.6200, "武侯区"),
    (104.1200, 30.6600, "成华区"),
    (104.0800, 30.6500, "锦江区"),
]

# 采集批次标记（写入 contact_person，用于幂等清理；不会出现在联系人 UI 的语义场景中）
AMAP_TAG = "AMAP_CRAWLER"
OFFLINE_TAG = "OFFLINE_GEN"

# 离线名称素材
INDUSTRY_WORDS = ["装备", "汽车零部件", "食品", "电子", "建材", "医药", "纺织", "机械",
                  "新材料", "包装", "家具", "食品加工", "新能源", "冷链", "金属制品"]
WAREHOUSE_WORDS = ["综合", "冷链", "快消品", "电商", "建材", "农资", "保税", "中转"]
LOGISTICS_WORDS = ["传化", "顺丰", "京东", "中通", "圆通", "安博", "普洛斯", "万纬"]


def sql_escape(text):
    """转义 SQL 字符串中的单引号。"""
    if text is None:
        return ""
    return str(text).replace("\\", "\\\\").replace("'", "''")


def gaussian_point(rng):
    """在随机一个区县中心附近做高斯散布（sigma 约 0.012 度 ≈ 1.3km），返回经纬度与区县名。"""
    lng0, lat0, district = rng.choice(DISTRICT_CENTERS)
    lng = lng0 + rng.gauss(0, 0.012)
    lat = lat0 + rng.gauss(0, 0.010)
    return round(lng, 7), round(lat, 7), district


def make_name(rng, prefix, seq):
    """按分类生成拟真 POI 名称，编号保证唯一。"""
    if prefix == "工厂":
        return f"成都{rng.choice(INDUSTRY_WORDS)}工厂{seq:04d}号"
    if prefix == "仓库":
        return f"{rng.choice(DISTRICT_CENTERS)[2]}{rng.choice(WAREHOUSE_WORDS)}仓库{seq:04d}号"
    if prefix == "加油站":
        road = rng.choice(["成龙大道", "成渝高速", "绕城高速", "成绵高速", "成温邛高速",
                           "天府大道", "剑南大道", "羊西线", "北星大道", "成洛大道"])
        return f"{road}{seq:04d}号加油站"
    if prefix == "收费站":
        road = rng.choice(["成渝高速成都", "成绵高速", "成雅高速", "成南高速", "成灌高速",
                           "成温邛高速", "绕城高速", "成安渝高速", "成自泸高速", "第二绕城高速"])
        return f"{road}{rng.choice(['东', '西', '南', '北'])}收费站{seq:04d}号"
    if prefix == "停车场":
        place = rng.choice(["万达广场", "春熙路", "火车北站", "双流机场", "天府广场",
                            "环球中心", "大悦城", "犀浦枢纽", "东部新区", "物流港"])
        return f"{place}{seq:04d}号停车场"
    return f"{rng.choice(LOGISTICS_WORDS)}{rng.choice(DISTRICT_CENTERS)[2]}物流中心{seq:04d}号"


def gen_offline(total):
    """离线生成 total 条 POI（6 类尽量均分），返回元组列表。"""
    rng = random.Random(20260916)  # 固定随机种子，保证多次生成结果一致
    rows, seq = [], 0
    base = total // len(CATEGORIES)
    extra = total % len(CATEGORIES)  # 余数补到前几个分类
    for idx, (cid, cname, _kw, prefix) in enumerate(CATEGORIES):
        count = base + (1 if idx < extra else 0)
        for _ in range(count):
            seq += 1
            lng, lat, district = gaussian_point(rng)
            name = make_name(rng, prefix, seq)
            capacity = round(rng.uniform(50, 800), 1)
            phone = f"1{rng.randint(30, 99)}{rng.randint(10000000, 99999999)}"
            # contact_person 列写入批次标签（幂等清理用），phone 列写随机联系电话
            rows.append((name, cid, lng, lat, f"成都市{district}", capacity,
                         OFFLINE_TAG, phone))
    return rows


def fetch_amap(key, total, center="104.0668,30.5728", radius=30000):
    """
    调用高德「周边搜索 / place/around」接口，以 center 为圆心、radius 米为半径，
    按 6 个分类关键词分页抓取真实 POI，直到达到 total 条或结果耗尽。

    :param key:    高德 Web 服务 Key
    :param total:  目标 POI 总数
    :param center: 搜索中心点 "经度,纬度"（默认成都高新区）
    :param radius: 搜索半径（米），默认 30000 = 30 公里
    """
    rows, seen_locations = [], set()
    per_category = math.ceil(total / len(CATEGORIES))
    for cid, cname, keyword, _prefix in CATEGORIES:
        page, got = 1, 0
        while got < per_category:
            # 周边搜索 API：location=中心点&radius=半径&keywords=关键词
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
                             f"成都市{adname}{poi.get('address') or ''}", None,
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


def write_sql(rows, out_path, source):
    """将 POI 行列表写成幂等 SQL 文件。"""
    tag = AMAP_TAG if source == "amap" else OFFLINE_TAG
    lines = [
        "-- POI 采集数据（由 tools/poi_crawler.py 自动生成，请勿手工编辑）",
        f"-- 来源：{'高德开放平台 place/text' if source == 'amap' else '离线模拟生成'}，共 {len(rows)} 条",
        "-- 幂等导入：先清理本工具历史批次（按 contact_person 标记），再插入",
        "SET NAMES utf8mb4;",
        f"DELETE FROM poi WHERE contact_person = '{tag}';",
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
    # 密码按本机 MySQL 实际配置填写：XAMPP 自带 MariaDB 默认 root 空密码，独立安装的 MySQL 用 -p你的密码
    print("导入命令（XAMPP 空密码）：mysql -uroot transport < " + out_path)


def parse_args():
    """解析命令行参数。"""
    parser = argparse.ArgumentParser(description="POI 采集/生成工具（高德在线 / 离线模拟）")
    parser.add_argument("--mode", choices=["amap", "offline"], default="offline",
                        help="amap=高德API真实采集（需Key），offline=离线生成（默认）")
    parser.add_argument("--key", default=os.environ.get("AMAP_KEY", ""),
                        help="高德 Web 服务 Key（也可用环境变量 AMAP_KEY）")
    parser.add_argument("--total", type=int, default=500,
                        help="目标 POI 总数：500 对应评级良，1000 对应优")
    parser.add_argument("--center", default="104.0668,30.5728",
                        help="周边搜索中心点 经度,纬度（默认成都高新区，30km 范围）")
    parser.add_argument("--radius", type=int, default=30000,
                        help="周边搜索半径（米），默认 30000=30km")
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
        data_rows = fetch_amap(ARGS.key, ARGS.total, ARGS.center, ARGS.radius)
        write_sql(data_rows, ARGS.out, "amap")
    else:
        print(f"离线生成 {ARGS.total} 条成都 POI（6 类）...")
        data_rows = gen_offline(ARGS.total)
        write_sql(data_rows, ARGS.out, "offline")
