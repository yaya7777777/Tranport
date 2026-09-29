<template>
<div>
 <div class="section-head">
  <div><h2>仿真沙盘</h2><p>单车/多车按状态转换规则循环运行：找货 → 装货 → 选线运输 → 卸货，含交通异常与加油/保养</p></div>
  <div class="head-actions">
   <button class="btn btn-go" :disabled="running" @click="startSim">▶ 开始仿真</button>
   <button class="btn" :disabled="!running" @click="stopSim">⏸ 停止</button>
   <button class="btn btn-reset" @click="resetSim">↺ 复位</button>
  </div>
 </div>
 <div v-if="error" class="error">{{error}}</div>

 <!-- 运行状态条：节拍/完成单/待派单/异常 + 六状态实时分布 -->
 <div class="sim-bar panel">
  <div class="sim-state">
   <span :class="['dot',running?'dot-run':'dot-stop']"></span>
   <b>{{running?'仿真运行中':'仿真未运行'}}</b>
   <small class="muted">节拍 {{status.tick||0}}（每拍 5 仿真分钟）</small>
  </div>
  <div class="sim-metrics">
   <span>已完成订单 <b>{{status.processedOrders||0}}</b></span>
   <span>待派订单 <b>{{status.pendingOrders||0}}</b></span>
   <span>活跃异常 <b :class="{c_red:(status.activeAnomalies||0)>0}">{{status.activeAnomalies||0}}</b></span>
  </div>
  <div class="sim-counts">
   <span v-for="c in statusBadges" :key="c.code" :class="['mini-tag','tag-'+c.cls]">
     {{c.label}} {{status.statusCounts&&status.statusCounts[c.code]||0}}
   </span>
  </div>
 </div>

 <div class="sim-layout">
  <!-- Leaflet 真实地图主区（高德底图，支持缩放与拖动加载瓦片） -->
  <div class="panel map-panel">
   <div id="sim-map" class="sim-map"></div>

   <!-- 图例 -->
   <div class="legend">
    <div class="legend-title">POI 图层</div>
    <label v-for="c in categories" :key="c.id" class="legend-item">
     <input type="checkbox" :value="c.id" v-model="activeLayers">
     <i :style="{background:c.color}"></i>{{c.name}} ×{{poiCounts[c.id]||0}}
    </label>
    <div class="legend-title" style="margin-top:8px">车辆状态</div>
    <span v-for="s in statusLegend" :key="s.code" class="legend-item">
     <i :style="{background:s.color}"></i>{{s.label}}
    </span>
   </div>
  </div>

  <!-- 右侧：车辆详情 + 状态变更日志 -->
  <div class="sim-side">
   <div class="panel" v-if="selectedVehicle">
    <div class="panel-title">车辆详情
     <button class="btn-mini fr" @click="selectedVehicle=null">关闭</button>
    </div>
    <div class="detail-list">
     <div><span>车牌</span><b>{{selectedVehicle.plateNumber}}</b></div>
     <div><span>车型/司机</span><b>{{selectedVehicle.vehicleType}} / {{selectedVehicle.driverName||'未分配'}}</b></div>
     <div><span>状态</span><b :style="{color:statusColor(selectedVehicle.status)}">{{statusText(selectedVehicle.status)}}</b></div>
     <div><span>速度 / 油量</span><b>{{(selectedVehicle.speed||0).toFixed(0)}} km/h · {{(selectedVehicle.remainingFuel||0).toFixed(0)}} L</b></div>
     <div v-if="selectedVehicle.orderNo"><span>当前订单</span><b>{{selectedVehicle.orderNo}}</b></div>
     <div v-if="selectedVehicle.originName"><span>起 → 终</span><b>{{selectedVehicle.originName}} → {{selectedVehicle.destName}}</b></div>
     <div v-if="selectedVehicle.progress!=null"><span>运输进度</span>
      <b><i class="progress"><i :style="{width:(selectedVehicle.progress*100)+'%'}"></i></i>
      {{(selectedVehicle.progress*100).toFixed(0)}}%</b></div>
    </div>
   </div>

   <div class="panel log-panel">
    <div class="panel-title">状态变更日志（状态转换规则表）</div>
    <div class="log-list">
     <div v-for="log in logs" :key="log.logId" class="log-item">
      <span class="log-time">{{(log.changeTime||'').substring(11)}}</span>
      <span class="strong">{{log.plateNumber}}</span>
      <span :class="['tag','tag-'+statusCls(log.fromStatus)]">{{statusText(log.fromStatus)||'初始'}}</span>
      <span>→</span>
      <span :class="['tag','tag-'+statusCls(log.toStatus)]">{{statusText(log.toStatus)}}</span>
      <small class="muted">{{log.poiName||''}} {{log.remark||''}}</small>
     </div>
     <div v-if="logs.length===0" class="muted" style="padding:10px">暂无状态变更，启动仿真后产生。</div>
    </div>
   </div>
  </div>
 </div>
</div>
</template>
<script setup>
// 仿真沙盘页：每 1.5s 轮询快照，基于 Leaflet + 高德底图渲染真实地图，
// 叠加 POI 站点、车辆（按状态着色/航向旋转/平滑移动）、运输路线与交通异常。
import {ref,reactive,computed,watch,onMounted,onUnmounted} from 'vue'
import http from '../api/http'

const pois = ref([])        // 全量 POI（静态，只加载一次）
const vehicles = ref([])    // 快照中的车辆位置
const anomalies = ref([])   // 活跃交通异常
const logs = ref([])        // 最近状态变更日志
const status = reactive({}) // 仿真状态摘要
const running = computed(() => !!status.running)
const error = ref('')
const selectedVehicle = ref(null)
const selectedPoi = ref(null)

// POI 六个分类的颜色与图层开关（收费站当前无数据，不入图例）
const categories = [
  {id:1,name:'工厂',color:'#dc2626'}, {id:2,name:'仓库',color:'#2563eb'},
  {id:6,name:'物流中心',color:'#059669'}, {id:3,name:'加油站',color:'#f59e0b'},
  {id:7,name:'修车厂',color:'#7c3aed'}, {id:5,name:'停车场',color:'#64748b'}
]
// 各分类圆点大小：工厂量大用小圆点降低视觉密度，物流中心少而重要更醒目
const CAT_RADIUS = {1:3.5, 2:5, 3:5, 5:5, 6:7, 7:5}
const activeLayers = ref(categories.map(c => c.id))
const visiblePois = computed(() => pois.value.filter(p => activeLayers.value.includes(p.categoryId)))
// 图例中展示各分类数量
const poiCounts = computed(() => {
  const m = {}
  pois.value.forEach(p => { m[p.categoryId] = (m[p.categoryId] || 0) + 1 })
  return m
})

const STATUS_MAP = {
  IDLE:{label:'空闲',color:'#94a3b8',cls:'gray'},
  LOADING:{label:'装货',color:'#f59e0b',cls:'amber'},
  TRANSPORT:{label:'运输',color:'#2563eb',cls:'blue'},
  UNLOADING:{label:'卸货',color:'#7c3aed',cls:'purple'},
  REFUEL:{label:'加油',color:'#0891b2',cls:'cyan'},
  MAINTAIN:{label:'保养',color:'#dc2626',cls:'red'}
}
const statusText = s => STATUS_MAP[s] ? STATUS_MAP[s].label : s
const statusColor = s => STATUS_MAP[s] ? STATUS_MAP[s].color : '#94a3b8'
const statusCls = s => STATUS_MAP[s] ? STATUS_MAP[s].cls : 'gray'
const statusLegend = Object.values(STATUS_MAP).map(v => ({code:Object.keys(STATUS_MAP).find(k=>STATUS_MAP[k]===v),...v}))
const statusBadges = Object.entries(STATUS_MAP).map(([code,v]) => ({code,label:v.label,cls:v.cls}))
const catColor = cid => (categories.find(c => c.id === cid) || {}).color || '#94a3b8'
const anomalyText = t => ({CONGESTION:'拥堵',ACCIDENT:'事故',CONSTRUCTION:'施工',WEATHER:'恶劣天气'})[t]||t

// 行驶中的车辆才画路线虚线
const movingVehicles = computed(() =>
  vehicles.value.filter(v => v.longitude != null &&
    (v.status === 'LOADING' || v.status === 'TRANSPORT') && destOf(v)))

// POI 名称 -> 坐标索引（快照里只有起终点名称，用它在 POI 列表中反查坐标画线）
const poiIndex = computed(() => {
  const m = {}
  pois.value.forEach(p => { m[p.poiName] = p })
  return m
})
/** 装货阶段开往起点、运输阶段开往终点 */
function destOf(v) {
  const name = v.status === 'LOADING' ? v.originName : v.destName
  const p = name ? poiIndex.value[name] : null
  return p ? {lng:p.longitude, lat:p.latitude} : null
}

// ---------- 坐标纠偏：高德底图为 GCJ-02，后端经纬度为 WGS-84 ----------
function transformLat(x, y) {
  let r = -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*Math.sqrt(Math.abs(x))
  r += (20*Math.sin(6*x*Math.PI) + 20*Math.sin(2*x*Math.PI)) * 2/3
  r += (20*Math.sin(y*Math.PI) + 40*Math.sin(y/3*Math.PI)) * 2/3
  r += (160*Math.sin(y/12*Math.PI) + 320*Math.sin(y*Math.PI/30)) * 2/3
  return r
}
function transformLng(x, y) {
  let r = 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*Math.sqrt(Math.abs(x))
  r += (20*Math.sin(6*x*Math.PI) + 20*Math.sin(2*x*Math.PI)) * 2/3
  r += (20*Math.sin(x*Math.PI) + 40*Math.sin(x/3*Math.PI)) * 2/3
  r += (150*Math.sin(x/12*Math.PI) + 300*Math.sin(x/30*Math.PI)) * 2/3
  return r
}
/** WGS-84 -> GCJ-02（返回 [lng, lat]，境外点原样返回） */
function wgs84ToGcj02(lng, lat) {
  if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) return [lng, lat]
  const dLat = transformLat(lng-105, lat-35), dLng = transformLng(lng-105, lat-35)
  const radLat = lat/180*Math.PI
  let magic = Math.sin(radLat); magic = 1 - 0.006693421622965943*magic*magic
  const sm = Math.sqrt(magic)
  return [lng + (dLng*180)/(6378245/sm*Math.cos(radLat)*Math.PI),
          lat + (dLat*180)/((6378245*(1-0.006693421622965943))/(magic*sm)*Math.PI)]
}

// ---------- Leaflet 地图状态 ----------
let map = null                     // Leaflet 地图实例
const vehicleMarkers = new Map()   // vehicleId -> marker
const routeLines = new Map()       // vehicleId -> polyline
const poiMarkers = new Map()       // poiId -> circleMarker
const anomalyMarkers = new Map()   // anomalyId -> marker
let firstFit = true                // 首次拿到数据后自动适配一次视野，之后不跟随

/** 初始化地图：高德路网底图 + 卫星图切换，支持缩放/拖动加载瓦片 */
function initMap() {
  map = L.map('sim-map', {zoomControl:true, attributionControl:false, preferCanvas:true})
    .setView([31.23, 121.47], 10)
  window.__simMap = map   // 调试：暴露地图实例，便于控制台排查视野问题
  const gaode = L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
    {subdomains:['1','2','3','4'], maxZoom:18})
  const satellite = L.tileLayer('https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}',
    {subdomains:['1','2','3','4'], maxZoom:18})
  gaode.addTo(map)
  L.control.layers({'高德地图':gaode, '高德卫星':satellite}, null, {position:'topright'}).addTo(map)
}

/** 绘制/更新 POI 站点（按图层开关过滤，复用 circleMarker） */
function drawPois() {
  if (!map) return
  const show = new Set(activeLayers.value)
  pois.value.forEach(p => {
    const [lng, lat] = wgs84ToGcj02(p.longitude, p.latitude)
    let m = poiMarkers.get(p.poiId)
    if (!show.has(p.categoryId)) {
      if (m) { map.removeLayer(m); poiMarkers.delete(p.poiId) }
      return
    }
    if (!m) {
      m = L.circleMarker([lat, lng], {radius:CAT_RADIUS[p.categoryId]||5, color:'#fff', weight:1.2,
        fillColor:catColor(p.categoryId), fillOpacity:.85})
        .bindTooltip(`${p.poiName}（${p.categoryName}）`, {direction:'top', offset:[0,-6]})
        .on('click', () => { selectedPoi.value = p })
      m.addTo(map); poiMarkers.set(p.poiId, m)
    }
  })
}

watch(activeLayers, drawPois)

/** 车辆 marker 的内部结构：三角形（按航向旋转+状态色）+ 车牌标签 */
const vehHtml = v => `<div class="veh-pin" style="--c:${statusColor(v.status)}">`
  + `<div class="veh-tri" style="transform:rotate(${v.heading||0}deg)"></div>`
  + `<span class="veh-plate">${v.plateNumber||''}</span></div>`

/** 绘制/更新车辆 marker 与运输路线（复用 marker，CSS 过渡实现平滑移动） */
function drawVehicles() {
  if (!map) return
  const alive = new Set()
  vehicles.value.forEach(v => {
    if (v.longitude == null) return
    alive.add(v.vehicleId)
    const [lng, lat] = wgs84ToGcj02(v.longitude, v.latitude)
    let mk = vehicleMarkers.get(v.vehicleId)
    if (!mk) {
      mk = L.marker([lat, lng], {icon: L.divIcon({className:'', html:'', iconSize:[34,34], iconAnchor:[17,17]})}).addTo(map)
      mk.on('click', () => { selectedVehicle.value = vehicles.value.find(x => x.vehicleId === v.vehicleId) || v })
      vehicleMarkers.set(v.vehicleId, mk)
    }
    mk.setLatLng([lat, lng])                     // 位置变化由过渡动画平滑衔接
    const el = mk.getElement()
    if (el) el.innerHTML = vehHtml(v)            // 刷新状态色/航向/车牌
    mk.bindTooltip(`${v.plateNumber} · ${statusText(v.status)}${v.orderNo ? ' · ' + v.orderNo : ''}`)
    // 运输路线虚线：当前位置 → 目的地
    const d = destOf(v)
    let line = routeLines.get(v.vehicleId)
    if (d) {
      const [dlng, dlat] = wgs84ToGcj02(d.lng, d.lat)
      if (!line) {
        line = L.polyline([], {dashArray:'7 8', weight:2, opacity:.6}).addTo(map)
        routeLines.set(v.vehicleId, line)
      }
      line.setLatLngs([[lat,lng],[dlat,dlng]]); line.setStyle({color:statusColor(v.status)})
    } else if (line) { map.removeLayer(line); routeLines.delete(v.vehicleId) }
  })
  vehicleMarkers.forEach((mk, id) => { if (!alive.has(id)) { map.removeLayer(mk); vehicleMarkers.delete(id) } })
  routeLines.forEach((line, id) => { if (!alive.has(id)) { map.removeLayer(line); routeLines.delete(id) } })
}

/** 绘制/更新交通异常（红色脉冲动画 marker） */
function drawAnomalies() {
  if (!map) return
  const alive = new Set()
  anomalies.value.forEach(a => {
    alive.add(a.anomalyId)
    const [lng, lat] = wgs84ToGcj02(a.longitude, a.latitude)
    if (!anomalyMarkers.has(a.anomalyId)) {
      const mk = L.marker([lat, lng], {icon: L.divIcon({className:'anomaly-icon',
        html:'<i></i><b>⚠</b>', iconSize:[14,14], iconAnchor:[7,7]})}).addTo(map)
      mk.bindTooltip(anomalyText(a.anomalyType), {direction:'right', offset:[10,0],
        permanent:true, className:'anomaly-tip'})
      anomalyMarkers.set(a.anomalyId, mk)
    }
  })
  anomalyMarkers.forEach((mk, id) => { if (!alive.has(id)) { map.removeLayer(mk); anomalyMarkers.delete(id) } })
}

/** 首次拿到 POI/车辆后自动适配视野（只执行一次，之后由用户自由缩放拖动） */
function fitOnce() {
  if (!firstFit || !map) return
  const pts = []
  pois.value.forEach(p => { const [x,y] = wgs84ToGcj02(p.longitude, p.latitude); pts.push([y, x]) })
  vehicles.value.forEach(v => { if (v.longitude != null) {
    const [x,y] = wgs84ToGcj02(v.longitude, v.latitude); pts.push([y, x]) } })
  if (pts.length) { map.fitBounds(L.latLngBounds(pts).pad(0.15)); firstFit = false }
}

/** 拉取一次沙盘快照与日志（轮询回调） */
async function tick() {
  try {
    const snap = (await http.get('/simulation/snapshot')).data
    vehicles.value = snap.vehicles || []
    anomalies.value = snap.anomalies || []
    Object.assign(status, snap.status)
    logs.value = (await http.get('/simulation/logs', {params:{limit:20}})).data
    drawVehicles(); drawAnomalies(); fitOnce()
  } catch (e) {
    error.value = '无法连接后端（8888），请确认 Spring Boot 已启动。'
  }
}

async function startSim() {
  error.value = ''
  try {
    const res = (await http.post('/simulation/start', {vehicleCount:12, autoOrders:true})).data
    if (!res.success) error.value = res.message
    await tick()
  } catch (e) { error.value = '启动仿真失败。' }
}
async function stopSim() {
  await http.post('/simulation/stop'); await tick()
}
async function resetSim() {
  selectedVehicle.value = null
  await http.post('/simulation/reset'); await tick()
}

let timer = null
onMounted(async () => {
  initMap()                                   // 创建 Leaflet 地图（高德底图）
  try {
    pois.value = (await http.get('/pois', {params:{limit:2000}})).data
    drawPois()                                // POI 相对静态，进入页面加载一次即可
  } catch (e) { /* 忽略，沙盘仍可显示车辆 */ }
  await tick()
  timer = setInterval(tick, 1500) // 每 1.5 秒刷新一次快照
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.sim-map{width:100%;height:640px;border-radius:10px;border:1px solid #e2e8f0;background:#eef4fb}
.map-panel .legend{z-index:1000}
/* 车辆 marker 位置变化用过渡平滑衔接，实现“车在动”的效果 */
.sim-map :deep(.leaflet-marker-icon){transition:transform 1.3s linear}
/* 缩放动画期间沿用 Leaflet 自身的过渡曲线，避免拖慢瓦片级缩放 */
.sim-map :deep(.leaflet-zoom-anim .leaflet-zoom-animated){transition:transform .25s cubic-bezier(0,0,.25,1)!important}
.sim-map :deep(.veh-pin){position:relative;width:34px;height:34px}
.sim-map :deep(.veh-tri){position:absolute;left:9px;top:5px;width:0;height:0;
 border-left:8px solid transparent;border-right:8px solid transparent;border-bottom:20px solid var(--c);
 filter:drop-shadow(0 1px 1px rgba(0,0,0,.35))}
.sim-map :deep(.veh-plate){position:absolute;left:50%;top:26px;transform:translateX(-50%);white-space:nowrap;
 font-size:11px;line-height:16px;padding:0 4px;background:rgba(255,255,255,.92);
 border:1px solid #cbd5e1;border-radius:4px;color:#0f172a;pointer-events:none}
.sim-map :deep(.anomaly-icon){position:relative}
.sim-map :deep(.anomaly-icon b){position:absolute;left:2px;top:-6px;font-size:14px;color:#dc2626;
 z-index:2;text-shadow:0 0 3px #fff}
.sim-map :deep(.anomaly-icon i){position:absolute;left:1px;top:1px;width:12px;height:12px;
 border:2px solid #dc2626;border-radius:50%;animation:anom 1.6s ease-out infinite}
@keyframes anom{0%{transform:scale(.4);opacity:.9}100%{transform:scale(4);opacity:0}}
.sim-map :deep(.anomaly-tip){background:#fee2e2;border:1px solid #fca5a5;color:#991b1b;font-weight:600}
.sim-map :deep(.leaflet-control-layers){font-size:12px}
</style>
