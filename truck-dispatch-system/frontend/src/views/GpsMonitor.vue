<template>
<div>
 <div class="section-head">
  <div><h2>设备定位</h2><p>北斗模块经 Modbus 采集后 HTTP+JSON 上报（真实数据）与仿真数据混合展示</p></div>
  <button class="btn" @click="loadLatest">刷新</button>
 </div>
 <div v-if="error" class="error">{{error}}</div>

 <!-- 最新定位列表（默认 3 秒轮询） -->
 <div class="panel table-panel">
  <div class="panel-title" style="padding:18px 18px 0">
   最新定位
   <label class="fr muted" style="font-weight:400;font-size:12px">
    <input type="checkbox" v-model="autoRefresh"> 自动刷新(3s)
   </label>
  </div>
  <table><thead><tr>
   <th>车辆</th><th>经度</th><th>纬度</th><th>速度</th><th>航向</th><th>来源</th><th>定位时间</th><th>轨迹</th>
  </tr></thead><tbody>
   <tr v-for="g in latest" :key="g.gpsId">
    <td class="strong">{{g.plateNumber}}</td>
    <td>{{g.longitude}}</td><td>{{g.latitude}}</td>
    <td>{{g.speed??'-'}} km/h</td><td>{{g.heading??'-'}}°</td>
    <td><span :class="['tag',g.simulated?'blue':'green']">{{g.simulated?'仿真':'北斗实机'}}</span></td>
    <td>{{g.timestamp}}</td>
    <td><button class="btn-mini" @click="loadHistory(g.vehicleId)">查看轨迹</button></td>
  </tr>
  </tbody></table>
 </div>

 <!-- 单车历史轨迹：表格 + SVG 折线 -->
 <div v-if="history.length" class="panel" style="margin-top:20px">
  <div class="panel-title">历史轨迹 — 车辆 {{historyVehicle}}（{{history.length}} 个点）</div>
  <svg :viewBox="`0 0 ${W} ${H}`" class="track-svg">
   <polyline :points="trackPoints" fill="none" stroke="#2563eb" stroke-width="2"/>
   <circle v-for="(p,i) in history" :key="i" :cx="tx(p.longitude).x" :cy="tx(p.longitude).y" r="2.5"
           :fill="i===0?'#059669':(i===history.length-1?'#dc2626':'#2563eb')">
    <title>{{p.timestamp}} {{p.longitude}},{{p.latitude}}</title>
   </circle>
   <text :x="tx(history[0].longitude).x+6" :y="tx(history[0].longitude).y-6" class="poi-label" fill="#059669">起点</text>
   <text :x="tx(history[history.length-1].longitude).x+6" :y="tx(history[history.length-1].longitude).y+14"
         class="poi-label" fill="#dc2626">最新</text>
  </svg>
 </div>
</div>
</template>
<script setup>
// 设备定位页：/gps/latest 混合展示实机与仿真点；选中车辆后 /gps/history 画轨迹折线
import {ref,computed,watch,onMounted,onUnmounted} from 'vue'
import http from '../api/http'

const W = 900, H = 360
const latest = ref([])
const history = ref([])
const historyVehicle = ref('')
const error = ref('')
const autoRefresh = ref(true)
let timer = null

async function loadLatest() {
  try {
    latest.value = (await http.get('/gps/latest', {params:{limit:30}})).data
    error.value = ''
  } catch (e) {
    error.value = '定位数据加载失败，请确认后端（8888）已启动。'
  }
}

async function loadHistory(vehicleId) {
  const plate = latest.value.find(g => g.vehicleId === vehicleId)?.plateNumber || vehicleId
  historyVehicle.value = plate
  history.value = (await http.get('/gps/history', {params:{vehicleId, limit:80}})).data.reverse()
}

// 轨迹投影：与仿真沙盘相同的等距圆柱思路，固定画布 900x360，留边距
const trackBounds = computed(() => {
  if (!history.value.length) return null
  const lngs = history.value.map(p => p.longitude), lats = history.value.map(p => p.latitude)
  return {minLng:Math.min(...lngs), maxLng:Math.max(...lngs),
          minLat:Math.min(...lats), maxLat:Math.max(...lats)}
})
function tx(lng, lat) {
  const b = trackBounds.value
  if (!b) return {x:0,y:0}
  const cosLat = Math.cos((b.minLat+b.maxLat)/2*Math.PI/180)
  const dx = Math.max(0.001,(b.maxLng-b.minLng)*cosLat), dy = Math.max(0.001,b.maxLat-b.minLat)
  const k = Math.min((W-80)/dx, (H-70)/dy)
  return {x:40+(lng-b.minLng)*cosLat*k, y:H-35-(lat-b.minLat)*k}
}
// polyline 的 points 属性格式：x1,y1 x2,y2 ...
const trackPoints = computed(() =>
  history.value.map(p => { const q = tx(p.longitude,p.latitude); return `${q.x},${q.y}` }).join(' '))

// 勾选/取消自动刷新时动态维护定时器
watch(autoRefresh, on => {
  clearInterval(timer)
  if (on) timer = setInterval(loadLatest, 3000)
})
onMounted(async () => {
  await loadLatest()
  timer = setInterval(loadLatest, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>
