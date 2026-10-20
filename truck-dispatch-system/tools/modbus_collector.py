# -*- coding: utf-8 -*-
"""
北斗定位 Modbus 采集器（任务书第 1 项）
=========================================
业务链路：
    北斗/GPS 模块 --RS485(Modbus-RTU)--> ARM 开发板(树莓派/Linux) --HTTP+JSON--> 云端 Spring Boot 接口
                                                                     POST /api/gps/report

本脚本运行在 ARM 上位机（也可在 PC 上做仿真验证），提供两种模式：

1. 模拟模式（默认，无需任何硬件与第三方库）：
       python modbus_collector.py --mode mock --vehicle-code 川A10001
       python modbus_collector.py --mode mock --all                 # 12 辆车全部模拟
   程序让车辆沿成都市区坐标直线巡航，到边界折返，循环上报经纬度。

2. 实机模式（需要 RS485 转 USB 和 pymodbus 库）：
       pip install pymodbus
       python modbus_collector.py --mode real --port COM3 --slave 1 --vehicle-code 川A10001
       # Linux 树莓派：--port /dev/ttyUSB0
   按通用北斗模块寄存器表读取经纬度（不同厂家寄存器地址可能不同，可通过命令行参数调整）：
       寄存器 0-1 : 经度 int32，实际值 = 原始值 / 1e7
       寄存器 2-3 : 纬度 int32，实际值 = 原始值 / 1e7
       寄存器 4   : 速度 uint16，单位 0.1 km/h
       寄存器 5   : 航向角 uint16，单位 0.1 度

通用参数：
       --server   云端地址，默认 http://localhost:8888
       --interval 采集间隔秒，默认 3
"""

import argparse
import json
import math
import random
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime

# 演示车辆车牌（与 database/demo_data.sql 中的 12 辆车一一对应）
DEMO_PLATES = [f"川A1{i:04d}" for i in range(1, 13)]

# 仿真中心点（成都高新区）与 30 公里巡航范围
CENTER_LNG, CENTER_LAT = 104.0668, 30.5728
# 30km 对应的经纬度跨度：1 纬度≈111km，1 经度≈111×cos(lat)km
RADIUS_KM = 30.0
LAT_DELTA = RADIUS_KM / 111.0                        # ≈0.270 度
LNG_DELTA = RADIUS_KM / (111.0 * math.cos(math.radians(CENTER_LAT)))  # ≈0.314 度
LNG_MIN, LNG_MAX = CENTER_LNG - LNG_DELTA, CENTER_LNG + LNG_DELTA
LAT_MIN, LAT_MAX = CENTER_LAT - LAT_DELTA, CENTER_LAT + LAT_DELTA


def post_gps(server, payload):
    """将一条定位报文以 HTTP POST JSON 发送到云端 /api/gps/report。"""
    url = server.rstrip("/") + "/api/gps/report"
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data,
                                 headers={"Content-Type": "application/json;charset=utf-8"})
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            body = resp.read().decode("utf-8")
            print(f"[{payload['vehicleCode']}] 上报成功 {payload['longitude']:.6f},"
                  f"{payload['latitude']:.6f} -> {body}")
            return True
    except urllib.error.URLError as e:
        # 网络不通或后端未启动时只打印错误，不退出，下一拍继续重试
        print(f"[ERROR] 上报失败：{e}（请确认后端已启动：{url}）")
        return False


class MockDevice:
    """模拟北斗模块：让一个点在成都市区范围内按随机航向移动，到达边界时反弹折返。"""

    def __init__(self, plate):
        self.plate = plate
        # 初始位置随机，保证多辆车不重叠
        self.lng = random.uniform(LNG_MIN, LNG_MAX)
        self.lat = random.uniform(LAT_MIN, LAT_MAX)
        self.heading = random.uniform(0, 360)
        self.speed = random.uniform(20, 60)  # km/h

    def read(self):
        """走一“拍”：按速度和航向推进坐标，返回与实机模式相同结构的定位字典。"""
        # 每 10 拍左右轻微转向，模拟真实道路行驶
        if random.random() < 0.1:
            self.heading = (self.heading + random.uniform(-60, 60)) % 360
        self.speed = max(0.0, self.speed + random.uniform(-5, 5))

        # 由速度估算经纬度增量：1 纬度约 111 km，经度跨度随纬度收缩
        distance_km = self.speed * ARGS.interval / 3600.0
        d_lat = distance_km / 111.0 * math.cos(math.radians(self.heading))
        d_lng = distance_km / (111.0 * math.cos(math.radians(self.lat))) \
            * math.sin(math.radians(self.heading))
        self.lat += d_lat
        self.lng += d_lng

        # 边界反弹
        if not LAT_MIN <= self.lat <= LAT_MAX:
            self.heading = (180 - self.heading) % 360
            self.lat = min(max(self.lat, LAT_MIN), LAT_MAX)
        if not LNG_MIN <= self.lng <= LNG_MAX:
            self.heading = (360 - self.heading) % 360
            self.lng = min(max(self.lng, LNG_MIN), LNG_MAX)

        return {
            "vehicleCode": self.plate,
            "longitude": round(self.lng, 7),
            "latitude": round(self.lat, 7),
            "speed": round(self.speed, 1),
            "heading": round(self.heading, 1),
            "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        }


def decode_int32(high, low):
    """将两个 16 位无符号寄存器拼成一个有符号 int32（大端：高字在前）。"""
    value = (high << 16) | low
    if value >= 0x80000000:
        value -= 0x100000000
    return value


def read_real_device(client, slave, reg_base):
    """
    通过 Modbus-RTU 读取实机北斗模块寄存器并解码。

    :param client:  pymodbus 串口客户端（已 connect）
    :param slave:   从站地址
    :param reg_base: 寄存器起始地址（默认 0，厂家不同可用 --reg-base 调整）
    :return: 定位字典（vehicleCode 由调用方补充）
    """
    # 连续读取 6 个保持寄存器：经度2 + 纬度2 + 速度1 + 航向1
    rr = client.read_holding_registers(address=reg_base, count=6, slave=slave)
    if rr.isError():
        raise RuntimeError(f"Modbus 读取失败：{rr}")
    regs = rr.registers

    longitude = decode_int32(regs[0], regs[1]) / 1e7
    latitude = decode_int32(regs[2], regs[3]) / 1e7
    speed = regs[4] / 10.0
    heading = regs[5] / 10.0

    # 简单有效性校验：经纬度为 0 通常表示模块尚未定位
    if longitude == 0 or latitude == 0:
        raise RuntimeError("北斗模块尚未定位（经纬度为 0），请检查天线与露天信号")

    return {
        "longitude": round(longitude, 7),
        "latitude": round(latitude, 7),
        "speed": round(speed, 1),
        "heading": round(heading, 1),
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }


def run_mock():
    """模拟模式主循环：为每辆（或指定）演示车创建一个虚拟模块并持续上报。"""
    plates = DEMO_PLATES if ARGS.all else [ARGS.vehicle_code]
    devices = [MockDevice(plate) for plate in plates]
    print(f"模拟模式启动：{len(devices)} 辆车，间隔 {ARGS.interval}s，云端 {ARGS.server}")
    while True:
        for device in devices:
            post_gps(ARGS.server, device.read())
        time.sleep(ARGS.interval)


def run_real():
    """实机模式主循环：连接 RS485 北斗模块，周期读取并上报，Ctrl+C 退出。"""
    try:
        # 延迟导入：模拟模式不安装 pymodbus 也能运行
        from pymodbus.client import ModbusSerialClient
    except ImportError:
        print("实机模式需要 pymodbus，请先执行：pip install pymodbus")
        sys.exit(1)

    client = ModbusSerialClient(
        port=ARGS.port,
        baudrate=ARGS.baudrate,
        bytesize=8,
        parity="N",
        stopbits=1,
        timeout=2,
    )
    if not client.connect():
        print(f"无法打开串口 {ARGS.port}（检查设备连接与串口占用）")
        sys.exit(1)

    print(f"实机模式启动：串口 {ARGS.port}，从站 {ARGS.slave}，车辆 {ARGS.vehicle_code}")
    try:
        while True:
            try:
                payload = read_real_device(client, ARGS.slave, ARGS.reg_base)
                payload["vehicleCode"] = ARGS.vehicle_code
                post_gps(ARGS.server, payload)
            except Exception as e:
                # 单次读取失败（信号未锁定/总线抖动）不致命，打印后继续
                print(f"[WARN] {e}")
            time.sleep(ARGS.interval)
    finally:
        client.close()


def parse_args():
    """解析命令行参数。"""
    parser = argparse.ArgumentParser(description="北斗定位 Modbus 采集器（模拟 / 实机双模式）")
    parser.add_argument("--mode", choices=["mock", "real"], default="mock", help="mock=模拟模块，real=实机读取")
    parser.add_argument("--server", default="http://localhost:8888", help="云端服务地址")
    parser.add_argument("--interval", type=float, default=3, help="采集间隔（秒）")
    parser.add_argument("--vehicle-code", default="川A10001", help="实机模式/单车模拟时绑定的车牌号")
    parser.add_argument("--all", action="store_true", help="模拟模式下同时模拟全部 12 辆演示车")
    # 实机参数
    parser.add_argument("--port", default="COM3", help="RS485 串口号（Windows: COM3，树莓派: /dev/ttyUSB0）")
    parser.add_argument("--baudrate", type=int, default=9600, help="串口波特率，常用 9600")
    parser.add_argument("--slave", type=int, default=1, help="Modbus 从站地址")
    parser.add_argument("--reg-base", type=int, default=0, help="经纬度寄存器起始地址")
    return parser.parse_args()


if __name__ == "__main__":
    ARGS = parse_args()
    try:
        run_real() if ARGS.mode == "real" else run_mock()
    except KeyboardInterrupt:
        print("\n采集已停止")
