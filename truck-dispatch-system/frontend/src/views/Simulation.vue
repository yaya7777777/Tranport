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
     <div v-if="legRoute"><span>道路路线</span><b>高德{{legRoute.truck ? '货车' : '驾车'}}规划 {{legRoute.distanceKm.toFixed(1)}} km · 约 {{legRoute.etaMinutes}} 分钟</b></div>
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
// 叠加 POI 站点、车辆（按状态着色/沿真实道路行驶/平滑移动）与交通异常；
// 订单路线不再自动绘制，点击车辆才以蓝色显示该车当前订单的起点→终点路线（再点一次取消）。
// 路线数据来自后端 /api/gis/route（代理高德货车路径规划 v4，货车接口不可用时降级驾车 v3，
// 坐标 GCJ-02 与底图同系）。
import {ref,reactive,computed,watch,onMounted,onUnmounted,shallowRef} from 'vue'
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

// POI 七个分类的颜色与图层开关
const categories = [
  {id:1,name:'工厂',color:'#dc2626'}, {id:2,name:'仓库',color:'#2563eb'},
  {id:6,name:'物流中心',color:'#059669'}, {id:3,name:'加油站',color:'#f59e0b'},
  {id:7,name:'修车厂',color:'#7c3aed'}, {id:5,name:'停车场',color:'#64748b'},
  {id:4,name:'收费站',color:'#b45309'}
]
// 各分类圆点大小：工厂量大用小圆点降低视觉密度，物流中心少而重要更醒目
const CAT_RADIUS = {1:3.5, 2:5, 3:5, 4:5, 5:5, 6:7, 7:5}
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
/** 选中车辆当前行程的高德规划路线信息（里程/耗时），无则不显示该行 */
const legRoute = computed(() => selectedVehicle.value ? routeMeta[selectedVehicle.value.vehicleId] : null)

// POI 名称 -> 坐标索引（快照里只有起终点名称，用它在 POI 列表中反查坐标画线）
const poiIndex = computed(() => {
  const m = {}
  pois.value.forEach(p => { m[p.poiName] = p })
  return m
})
/**
 * 车辆当前应绘制的"行程"（由高德货车/驾车路径规划提供真实道路路线）：
 * - TRANSPORT：装货完成运往终点，画 起点→终点 全程道路路线，车辆按 progress 沿路定位；
 * - LOADING+已接单且未到位：接单后正前往发货地（接单即转装货态），画 当前位置→起点 道路路线。
 * 其余状态（到位停留装货/卸货/加油/保养/空闲无单）不需要路线。
 */
function legOf(v) {
  if (v.status === 'TRANSPORT' && v.originName && v.destName) {
    const o = poiIndex.value[v.originName], d = poiIndex.value[v.destName]
    if (o && d) return {phase: 'transport',
      from: {lng: o.longitude, lat: o.latitude}, to: {lng: d.longitude, lat: d.latitude},
      fromPoiId: o.poiId, toPoiId: d.poiId, key: 'T' + o.poiId + '-' + d.poiId}
  }
  if (v.status === 'LOADING' && v.orderNo && v.originName) {
    const o = poiIndex.value[v.originName]
    // 已到位停留装车时（当前位置≈起点）不再画取货路线
    if (o && distKm([v.longitude, v.latitude], [o.longitude, o.latitude]) >= 0.05) return {phase: 'pickup',
      from: {lng: v.longitude, lat: v.latitude}, to: {lng: o.longitude, lat: o.latitude},
      toPoiId: o.poiId, key: 'P' + v.vehicleId + '-' + v.orderNo}
  }
  return null
}

/** 车辆当前订单的完整路线（起点→终点，与行程阶段无关），用于点击选中后的蓝色展示 */
function orderLegOf(v) {
  if (!v.orderNo || !v.originName || !v.destName) return null
  const o = poiIndex.value[v.originName], d = poiIndex.value[v.destName]
  if (!o || !d) return null
  return {key: 'O' + v.orderNo,
    from: {lng: o.longitude, lat: o.latitude}, to: {lng: d.longitude, lat: d.latitude},
    fromPoiId: o.poiId, toPoiId: d.poiId}
}

/** 撤销选中的蓝色订单路线 */
function clearSelRoute() {
  selRoute.value = null
  if (selRouteLine) { map.removeLayer(selRouteLine); selRouteLine = null }
}

/** 点击车辆：打开详情面板，并切换该车订单路线的蓝色显示（再点同一辆则取消） */
function onVehicleClick(v) {
  // marker 创建时闭包捕获的是旧快照对象，必须先按 vehicleId 取最新数据
  const cur = vehicles.value.find(x => x.vehicleId === v.vehicleId) || v
  selectedVehicle.value = cur
  if (selRoute.value && selRoute.value.vehicleId === cur.vehicleId) { clearSelRoute(); return }
  const leg = orderLegOf(cur)
  if (!leg) { clearSelRoute(); return }   // 无在途订单 → 无路线可显示
  const rec = {vehicleId: cur.vehicleId, key: leg.key, route: null}
  selRoute.value = rec
  if (selRouteLine) { map.removeLayer(selRouteLine); selRouteLine = null }  // 切换车辆先清旧线
  fetchRoute(leg.from, leg.to, leg.fromPoiId, leg.toPoiId).then(r => {
    if (selRoute.value !== rec || !r) return   // 已取消/切换或规划失败
    rec.route = r
    selRouteLine = L.polyline(r.points.map(p => [p[1], p[0]]),
      {color: '#2563eb', weight: 4, opacity: .9}).addTo(map)   // 蓝色实线突出显示
  })
}

// ---------- 坐标系说明 ----------
// poi 表数据来自高德爬虫，坐标系为 GCJ-02，与高德底图瓦片、高德路径规划 API 同系，
// 直接用原始坐标绘制即可与道路精确对齐（此前误加的 WGS-84→GCJ-02 转换属二次纠偏，
// 会让全部点位整体偏移约 500 米，已移除）。

// ---------- 路线几何工具 ----------
const clamp01 = t => Math.max(0, Math.min(1, t))
/** 两点球面距离 km（a/b 为 [lng,lat]） */
function distKm(a, b) {
  const R = 6371, rad = Math.PI / 180
  const dLat = (b[1] - a[1]) * rad, dLng = (b[0] - a[0]) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}
/** a→b 方位角（0-360，正北 0 顺时针），a/b 为 [lng,lat] */
function bearingDeg(a, b) {
  const rad = Math.PI / 180
  const y = Math.sin((b[0] - a[0]) * rad) * Math.cos(b[1] * rad)
  const x = Math.cos(a[1] * rad) * Math.sin(b[1] * rad)
      - Math.sin(a[1] * rad) * Math.cos(b[1] * rad) * Math.cos((b[0] - a[0]) * rad)
  return (Math.atan2(y, x) / rad + 360) % 360
}
/** 沿折线按距离比例取 t∈[0,1] 处的点与航向（pts 为 [[lng,lat],...]） */
function alongPath(pts, t) {
  if (!pts || !pts.length) return null
  if (pts.length === 1) return {pt: pts[0], hdg: 0}
  if (t <= 0) return {pt: pts[0], hdg: bearingDeg(pts[0], pts[1])}
  if (t >= 1) { const n = pts.length; return {pt: pts[n - 1], hdg: bearingDeg(pts[n - 2], pts[n - 1])} }
  let total = 0
  const segs = []
  for (let i = 1; i < pts.length; i++) { const L = distKm(pts[i - 1], pts[i]); segs.push(L); total += L }
  let rem = total * t
  for (let i = 1; i < pts.length; i++) {
    if (rem <= segs[i - 1] || i === pts.length - 1) {
      const a = pts[i - 1], b = pts[i], f = segs[i - 1] > 0 ? rem / segs[i - 1] : 1
      return {pt: [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f], hdg: bearingDeg(a, b)}
    }
    rem -= segs[i - 1]
  }
  return {pt: pts[pts.length - 1], hdg: 0}
}

// ---------- Leaflet 地图状态 ----------
let map = null                     // Leaflet 地图实例
const vehicleMarkers = new Map()   // vehicleId -> marker
const poiMarkers = new Map()       // poiId -> circleMarker
const anomalyMarkers = new Map()   // anomalyId -> marker
let firstFit = true                // 首次拿到数据后自动适配一次视野，之后不跟随
// 选中车辆的订单路线（点击车辆显示蓝色，再点一次取消）：{vehicleId, key, route} + 单条蓝色 polyline
const selRoute = shallowRef(null)
let selRouteLine = null

// ---------- 高德真实道路路线请求（经后端 /api/gis/route 代理，避免 Key 暴露与跨域） ----------
const routeCache = new Map()       // cacheKey -> Promise<route|null>，成功结果常驻复用
let routeBlockedUntil = 0          // 规划失败后的全局冷却（无 Key/断网/超配额时避免每拍刷请求）
const vehLegs = new Map()          // vehicleId -> {key, route, fromPos, d0, triedAt}，跟随行程切换
const routeMeta = reactive({})     // vehicleId -> {distanceKm, etaMinutes}，供车辆详情面板展示

const ck4 = v => (Math.round(v * 10000) / 10000).toFixed(4)

/**
 * 请求两点间沿真实道路的驾车路线（GCJ-02），带缓存与失败冷却。
 * 成功结果按起终点缓存常驻复用；失败只做 30 秒冷却后重试，不污染缓存。
 */
function fetchRoute(from, to, fromPoiId, toPoiId) {
  const key = ck4(from.lng) + ',' + ck4(from.lat) + '->' + ck4(to.lng) + ',' + ck4(to.lat)
  const hit = routeCache.get(key)
  if (hit) return hit
  if (Date.now() < routeBlockedUntil) return Promise.resolve(null)
  const p = http.get('/gis/route', {timeout: 30000, params: {
    fromLng: from.lng, fromLat: from.lat, toLng: to.lng, toLat: to.lat,
    fromPoiId: fromPoiId || undefined, toPoiId: toPoiId || undefined
  }}).then(res => {
    const d = res.data
    return d && Array.isArray(d.points) && d.points.length >= 2 ? d : null
  }).catch(() => null)
  if (routeCache.size > 300) routeCache.clear()
  routeCache.set(key, p)
  p.then(r => { if (!r) { routeCache.delete(key); routeBlockedUntil = Date.now() + 30000 } })
  return p
}

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
    const lng = p.longitude, lat = p.latitude   // 库内为 GCJ-02，直接绘制
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
const vehHtml = (v, hdg) => `<div class="veh-pin" style="--c:${statusColor(v.status)}">`
  + `<div class="veh-tri" style="transform:rotate(${hdg||0}deg)"></div>`
  + `<span class="veh-plate">${v.plateNumber||''}</span></div>`

/**
 * 绘制/更新车辆 marker 与路线（复用 marker，CSS 过渡实现平滑移动）。
 * 有高德规划路线时：画沿真实道路的完整折线，车辆按进度沿路线定位、朝向取路段方向；
 * 路线未就绪/规划不可用时：不画线，车辆用后端插值位置。
 */
function drawVehicles() {
  if (!map) return
  const alive = new Set()
  vehicles.value.forEach(v => {
    if (v.longitude == null) return
    alive.add(v.vehicleId)
    let mk = vehicleMarkers.get(v.vehicleId)
    if (!mk) {
      mk = L.marker([v.latitude, v.longitude], {icon: L.divIcon({className:'', html:'', iconSize:[34,34], iconAnchor:[17,17]})}).addTo(map)
      mk.on('click', () => onVehicleClick(v))
      vehicleMarkers.set(v.vehicleId, mk)
    }

    // ---- 行程与真实道路路线（路线仅用于沿路定位，不再自动绘制；点击车辆才显示订单路线） ----
    const leg = legOf(v)
    const st = ensureLeg(v, leg)
    let drawPos = [v.longitude, v.latitude]   // 默认：后端插值位置（GCJ-02 原样）
    let drawHeading = v.heading || 0
    if (leg && st.route) {
      // 有真实道路路线：计算沿路位置与朝向
      const pts = st.route.points
      let t = 0
      if (leg.phase === 'transport') {
        t = clamp01(v.progress || 0)          // 运输进度由后端给出（0→1）
      } else {
        // 接单空驶：按“剩余直线距离 / 初始直线距离”推演沿路进度，到站时恰好为 1
        const dNow = distKm([v.longitude, v.latitude], [leg.to.lng, leg.to.lat])
        t = st.d0 > 0.05 ? clamp01(1 - dNow / st.d0) : 1
      }
      const at = alongPath(pts, t)
      if (at) { drawPos = at.pt; drawHeading = at.hdg }
      routeMeta[v.vehicleId] = {distanceKm: st.route.distanceKm, etaMinutes: st.route.etaMinutes, truck: !!st.route.truck}
    } else {
      // 路线未就绪/无行程：不画线，仅车辆按后端插值位置移动
      delete routeMeta[v.vehicleId]
    }

    mk.setLatLng([drawPos[1], drawPos[0]])       // 位置变化由过渡动画平滑衔接
    const el = mk.getElement()
    if (el) el.innerHTML = vehHtml(v, drawHeading)  // 刷新状态色/航向/车牌
    mk.bindTooltip(`${v.plateNumber} · ${statusText(v.status)}${v.orderNo ? ' · ' + v.orderNo : ''}`)
  })
  vehicleMarkers.forEach((mk, id) => {
    if (!alive.has(id)) { map.removeLayer(mk); vehicleMarkers.delete(id); vehLegs.delete(id); delete routeMeta[id] }
  })
  // 选中车辆的订单已切换/完成 → 自动撤销蓝色路线
  if (selRoute.value) {
    const sv = vehicles.value.find(x => x.vehicleId === selRoute.value.vehicleId)
    const leg = sv ? orderLegOf(sv) : null
    if (!leg || leg.key !== selRoute.value.key) clearSelRoute()
  }
}

/**
 * 取/建车辆的行程状态并请求路线：
 * 行程 key 变化（新订单/新运输段）时重置状态；路线异步加载，就绪后下一次轮询生效。
 * 接单空驶的路线起点固定取“接单时位置”，避免车辆移动导致每拍重新规划浪费配额。
 */
function ensureLeg(v, leg) {
  if (!leg) { vehLegs.delete(v.vehicleId); delete routeMeta[v.vehicleId]; return null }
  let st = vehLegs.get(v.vehicleId)
  if (!st || st.key !== leg.key) {
    st = {key: leg.key, route: null, triedAt: 0,
      fromPos: {lng: leg.from.lng, lat: leg.from.lat},
      d0: distKm([leg.from.lng, leg.from.lat], [leg.to.lng, leg.to.lat])}
    vehLegs.set(v.vehicleId, st)
  }
  if (!st.route && Date.now() - st.triedAt > 30000) {
    st.triedAt = Date.now()
    fetchRoute(st.fromPos, leg.to, leg.fromPoiId, leg.toPoiId).then(r => {
      if (vehLegs.get(v.vehicleId) === st && r) st.route = r   // 仅当行程未切换时生效
    })
  }
  return st
}

/** 绘制/更新交通异常（红色脉冲动画 marker） */
function drawAnomalies() {
  if (!map) return
  const alive = new Set()
  anomalies.value.forEach(a => {
    alive.add(a.anomalyId)
    const lng = a.longitude, lat = a.latitude     // GCJ-02 原样绘制
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
  pois.value.forEach(p => { pts.push([p.latitude, p.longitude]) })
  vehicles.value.forEach(v => { if (v.longitude != null) pts.push([v.latitude, v.longitude]) })
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
