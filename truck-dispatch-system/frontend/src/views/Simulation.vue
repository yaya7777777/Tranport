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
  <!-- SVG 沙盘主区 -->
  <div class="panel map-panel">
   <svg :viewBox="`0 0 ${MAP_W} ${proj.h}`" preserveAspectRatio="xMidYMid meet">
    <!-- 异常影响范围（红色脉冲圆） -->
    <g v-for="a in anomalies" :key="'a'+a.anomalyId">
     <circle :cx="xy(a.longitude,a.latitude).x" :cy="xy(a.longitude,a.latitude).y"
             fill="none" stroke="#dc2626" stroke-width="1.5">
      <animate attributeName="r" values="6;20" dur="1.6s" repeatCount="indefinite"/>
      <animate attributeName="opacity" values="0.7;0" dur="1.6s" repeatCount="indefinite"/>
     </circle>
     <circle :cx="xy(a.longitude,a.latitude).x" :cy="xy(a.longitude,a.latitude).y" r="6"
             fill="#dc2626" stroke="#fff" stroke-width="1.5"/>
     <text :x="xy(a.longitude,a.latitude).x+9" :y="xy(a.longitude,a.latitude).y+4"
           class="anomaly-text">⚠ {{anomalyText(a.anomalyType)}}</text>
    </g>

    <!-- POI 站点（按分类着色，可通过图层开关过滤） -->
    <g v-for="p in visiblePois" :key="'p'+p.poiId">
     <circle :cx="xy(p.longitude,p.latitude).x" :cy="xy(p.longitude,p.latitude).y"
             :r="pois.length<=100?4.5:3" :fill="catColor(p.categoryId)"
             :stroke="selectedPoi&&selectedPoi.poiId===p.poiId?'#111':'#fff'" stroke-width="1">
      <title>{{p.poiName}}（{{p.categoryName}}）{{p.address||''}}</title>
     </circle>
     <text v-if="pois.length<=100" :x="xy(p.longitude,p.latitude).x+6"
           :y="xy(p.longitude,p.latitude).y+3" class="poi-label">{{p.poiName}}</text>
    </g>

    <!-- 在途车辆的运输路线虚线：当前位置 → 目的地（名称在 POI 中解析坐标） -->
    <g v-for="v in movingVehicles" :key="'r'+v.vehicleId">
     <line :x1="xy(v.longitude,v.latitude).x" :y1="xy(v.longitude,v.latitude).y"
           :x2="xy(destOf(v).lng,destOf(v).lat).x" :y2="xy(destOf(v).lng,destOf(v).lat).y"
           :stroke="statusColor(v.status)" stroke-width="1.6" stroke-dasharray="6 5" opacity="0.55"/>
    </g>

    <!-- 车辆：三角形按航向角旋转，颜色随状态，点击查看详情 -->
    <g v-for="v in vehicles" :key="'v'+v.vehicleId" class="vehicle-g"
       @click="selectedVehicle=v">
     <path d="M0,-9 L7,8 L0,4 L-7,8 Z"
           :transform="`translate(${xy(v.longitude,v.latitude).x},${xy(v.longitude,v.latitude).y}) rotate(${v.heading||0})`"
           :fill="statusColor(v.status)" stroke="#111827" stroke-width="0.8">
      <title>{{v.plateNumber}} {{statusText(v.status)}}{{v.orderNo?' 订单:'+v.orderNo:''}}</title>
     </path>
     <text :x="xy(v.longitude,v.latitude).x" :y="xy(v.longitude,v.latitude).y+20"
           class="vehicle-label" text-anchor="middle">{{v.plateNumber}}</text>
    </g>
   </svg>

   <!-- 图例 -->
   <div class="legend">
    <div class="legend-title">POI 图层</div>
    <label v-for="c in categories" :key="c.id" class="legend-item">
     <input type="checkbox" :value="c.id" v-model="activeLayers">
     <i :style="{background:c.color}"></i>{{c.name}}
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
// 仿真沙盘页：每 1.5s 轮询快照，把经纬度做等距圆柱投影映射到 SVG 坐标，
// 叠加 POI 站点、车辆（按状态着色/按航向旋转）、运输路线与交通异常。
import {ref,reactive,computed,onMounted,onUnmounted} from 'vue'
import http from '../api/http'

const MAP_W = 1000          // SVG 画布逻辑宽度（高度随经纬度范围自适应）
const pois = ref([])        // 全量 POI（静态，只加载一次）
const vehicles = ref([])    // 快照中的车辆位置
const anomalies = ref([])   // 活跃交通异常
const logs = ref([])        // 最近状态变更日志
const status = reactive({}) // 仿真状态摘要
const running = computed(() => !!status.running)
const error = ref('')
const selectedVehicle = ref(null)
const selectedPoi = ref(null)

// POI 六个分类的颜色与图层开关
const categories = [
  {id:1,name:'工厂',color:'#dc2626'}, {id:2,name:'仓库',color:'#2563eb'},
  {id:3,name:'加油站',color:'#f59e0b'}, {id:4,name:'收费站',color:'#7c3aed'},
  {id:5,name:'停车场',color:'#64748b'}, {id:6,name:'物流中心',color:'#059669'}
]
const activeLayers = ref(categories.map(c => c.id))
const visiblePois = computed(() => pois.value.filter(p => activeLayers.value.includes(p.categoryId)))

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

// 投影范围：综合 POI、车辆、异常的经纬度取并集，留 8% 边距
const bounds = computed(() => {
  const pts = []
  visiblePois.value.forEach(p => pts.push([p.longitude,p.latitude]))
  vehicles.value.forEach(v => v.longitude != null && pts.push([v.longitude,v.latitude]))
  anomalies.value.forEach(a => pts.push([a.longitude,a.latitude]))
  if (pts.length === 0) return null
  const lngs = pts.map(p => p[0]), lats = pts.map(p => p[1])
  let minLng = Math.min(...lngs), maxLng = Math.max(...lngs)
  let minLat = Math.min(...lats), maxLat = Math.max(...lats)
  // 经纬度跨度为 0（只有一个点）时给一个默认范围，避免除零
  if (maxLng === minLng) { minLng -= 0.05; maxLng += 0.05 }
  if (maxLat === minLat) { minLat -= 0.05; maxLat += 0.05 }
  const padLng = (maxLng-minLng)*0.08, padLat = (maxLat-minLat)*0.08
  return {minLng:minLng-padLng, maxLng:maxLng+padLng, minLat:minLat-padLat, maxLat:maxLat+padLat}
})

// 等距圆柱投影：纬度方向乘 cos(中心纬度) 保持距离比例，最终得到统一缩放比例 k
const proj = computed(() => {
  const b = bounds.value
  if (!b) return {h:MAP_W*0.7, k:1, minLng:0, maxLat:0, cosLat:1}
  const cosLat = Math.cos((b.minLat+b.maxLat)/2*Math.PI/180)
  const kx = MAP_W/((b.maxLng-b.minLng)*cosLat)
  const ky = MAP_W/((b.maxLat-b.minLat))       // 高度上限也用 MAP_W 约束
  const k = Math.min(kx, ky)
  const h = (b.maxLat-b.minLat)*k
  return {h:Math.max(420,h), k, minLng:b.minLng, maxLat:b.maxLat, cosLat}
})
/** 经纬度 -> SVG 像素坐标（y 轴翻转，纬度越高越靠上） */
function xy(lng, lat) {
  return {
    x: (lng-proj.value.minLng)*proj.value.cosLat*proj.value.k,
    y: (proj.value.maxLat-lat)*proj.value.k
  }
}

/** 拉取一次沙盘快照与日志（轮询回调） */
async function tick() {
  try {
    const snap = (await http.get('/simulation/snapshot')).data
    vehicles.value = snap.vehicles || []
    anomalies.value = snap.anomalies || []
    Object.assign(status, snap.status)
    logs.value = (await http.get('/simulation/logs', {params:{limit:20}})).data
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
  // POI 相对静态，进入页面加载一次即可
  try { pois.value = (await http.get('/pois', {params:{limit:2000}})).data } catch (e) { /* 忽略，沙盘仍可显示车辆 */ }
  await tick()
  timer = setInterval(tick, 1500) // 每 1.5 秒刷新一次快照
})
onUnmounted(() => clearInterval(timer))
</script>
