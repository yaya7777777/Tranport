<template>
<div>
 <div class="section-head"><div><h2>车辆管理</h2><p>新增车辆 · 设置位置/状态（按规则表校验） · 失效（软删除）</p></div>
  <div class="head-actions">
   <button class="btn" @click="toggleCreate">新增车辆</button>
   <button class="btn" @click="load">刷新</button>
  </div>
 </div>
 <div v-if="error" class="error">{{error}}</div>
 <div v-if="message" class="success">{{message}}</div>

 <!-- 新增车辆表单 -->
 <div v-if="showCreate" class="panel form-panel">
  <div class="panel-title">新增车辆（初始空闲 · 默认 200L 油量）</div>
  <div class="form-grid">
   <div class="form-item"><label>车牌号</label><input v-model="vform.plateNumber" placeholder="如 川A60001"></div>
   <div class="form-item"><label>车型</label>
    <select v-model.number="vform.typeId">
     <option v-for="t in opts.types" :key="t.typeId" :value="t.typeId">{{t.typeName}}（{{t.maxLoad}}kg/{{t.maxVolume}}m³）</option>
    </select></div>
   <div class="form-item"><label>司机</label>
    <select v-model.number="vform.driverId">
     <option :value="null">暂不分配</option>
     <option v-for="d in opts.drivers" :key="d.driverId" :value="d.driverId">{{d.name}}（{{d.licenseType}}）</option>
    </select></div>
   <div class="form-item"><label>初始位置</label>
    <select v-model.number="vform.poiId">
     <option :value="null">未定位</option>
     <option v-for="p in opts.pois" :key="p.poiId" :value="p.poiId">{{p.poiName}}</option>
    </select></div>
   <div class="form-item"><label>购置日期</label><input v-model="vform.purchaseDate" type="date"></div>
  </div>
  <div class="form-actions">
   <button class="btn btn-go" :disabled="saving" @click="createVehicle">提交</button>
   <button class="btn" @click="showCreate=false">取消</button>
  </div>
 </div>

 <!-- 设置位置 / 设置状态面板（对选中车辆操作） -->
 <div v-if="actionVehicle" class="panel form-panel">
  <div class="panel-title">
    {{actionType==='location'?'设置位置':'设置状态'}} — {{actionVehicle.plateNumber}}
    <button class="btn-mini fr" @click="closeAction">收起</button>
  </div>
  <div v-if="actionType==='location'" class="form-grid">
   <div class="form-item"><label>目标 POI 站点（同时写入一条 GPS 轨迹）</label>
    <select v-model.number="locPoiId">
     <option v-for="p in opts.pois" :key="p.poiId" :value="p.poiId">{{p.poiName}}（{{p.categoryName}}）</option>
    </select></div>
  </div>
  <div v-else class="form-grid">
   <div class="form-item"><label>目标状态（按 vehicle_status 转换规则表校验）</label>
    <select v-model="statusCode">
     <option v-for="(label,code) in statusMap" :key="code" :value="code">{{code}}（{{label}}）</option>
    </select></div>
   <div class="form-item"><label>当前状态</label>
    <input :value="actionVehicle.currentStatus" disabled></div>
  </div>
  <div class="form-actions">
   <button class="btn btn-go" :disabled="saving" @click="submitAction">确认设置</button>
  </div>
 </div>

 <div class="panel table-panel"><table><thead><tr>
  <th>ID</th><th>车牌号</th><th>车型</th><th>司机</th><th>当前位置</th><th>状态</th>
  <th>剩余油量</th><th>可运行里程</th><th>操作</th>
 </tr></thead>
 <tbody><tr v-for="v in vehicles" :key="v.vehicleId">
  <td>{{v.vehicleId}}</td>
  <td class="strong">{{v.plateNumber}}</td>
  <td>{{v.vehicleType}}</td>
  <td>{{v.driverName||'未分配'}}</td>
  <td>{{v.currentLocation||'-'}}</td>
  <td><span :class="['tag',tagClass(v.currentStatus)]">{{statusText(v.currentStatus)}}</span></td>
  <td>{{v.remainingFuel??'-'}} L</td>
  <td>{{v.estimatedRange??'-'}} km</td>
  <td class="op-cell">
   <button class="btn-mini" @click="openAction(v,'location')">位置</button>
   <button class="btn-mini" @click="openAction(v,'status')">状态</button>
   <button class="btn-mini btn-mini-danger" @click="disableVehicle(v)">失效</button>
  </td>
 </tr></tbody></table></div>
</div>
</template>
<script setup>
// 车辆管理页：台账 + 新增车辆 + 设置位置/状态（新接口）+ 软删除失效
import {ref,reactive,onMounted} from 'vue'
import http from '../api/http'

const vehicles = ref([])
const error = ref('')
const message = ref('')
const showCreate = ref(false)
const saving = ref(false)
const opts = ref({types: [], drivers: [], pois: []})
const actionVehicle = ref(null)   // 当前操作的目标车辆
const actionType = ref('')        // location | status
const locPoiId = ref(null)
const statusCode = ref('IDLE')

const statusMap = {IDLE:'空闲',LOADING:'装载',UNLOADING:'卸货',TRANSPORT:'运输',REFUEL:'加油',MAINTAIN:'保养'}

async function load() {
  error.value = ''
  try {
    vehicles.value = (await http.get('/vehicles')).data
  } catch (e) {
    error.value = '车辆数据加载失败，请检查后端（8888）和 MySQL。'
  }
}

/** 展开新增车辆表单并拉取下拉数据（车型/司机/POI） */
async function toggleCreate() {
  showCreate.value = !showCreate.value
  closeAction()
  if (showCreate.value && opts.value.types.length === 0) {
    await loadOptions()
  }
}

async function loadOptions() {
  try {
    // axios 响应 .data = ApiResult 包装，表单数据在其 .data 字段
    opts.value = (await http.get('/vehicles/options')).data.data
    if (opts.value.types.length > 0) vform.typeId = opts.value.types[0].typeId
    if (opts.value.pois.length > 0) locPoiId.value = opts.value.pois[0].poiId
  } catch (e) {
    error.value = '下拉数据加载失败，请检查后端服务。'
  }
}

const vform = reactive({plateNumber: '', typeId: null, driverId: null, poiId: null, purchaseDate: today()})

function today() {
  return new Date().toISOString().slice(0, 10)
}

/** 提交新增车辆：POST /api/vehicles */
async function createVehicle() {
  if (!vform.plateNumber.trim()) {
    error.value = '车牌号不能为空。'
    return
  }
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const res = (await http.post('/vehicles', {
      plateNumber: vform.plateNumber.trim(), typeId: vform.typeId,
      driverId: vform.driverId, poiId: vform.poiId, purchaseDate: vform.purchaseDate
    })).data
    if (res.success) {
      message.value = res.message
      showCreate.value = false
      vform.plateNumber = ''
      await load()
    } else {
      error.value = res.message || '新增失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '新增请求失败，请检查后端服务。'
  } finally {
    saving.value = false
  }
}

/** 打开设置位置/状态面板 */
function openAction(v, type) {
  actionVehicle.value = v
  actionType.value = type
  showCreate.value = false
  statusCode.value = v.currentStatus || 'IDLE'
  if (opts.value.types.length === 0) loadOptions()
  window.scrollTo({top: 0, behavior: 'smooth'})
}

function closeAction() {
  actionVehicle.value = null
  actionType.value = ''
}

/** 提交位置/状态设置 */
async function submitAction() {
  const vid = actionVehicle.value.vehicleId
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const res = actionType.value === 'location'
      ? (await http.put(`/vehicles/${vid}/location`, {poiId: locPoiId.value})).data
      : (await http.put(`/vehicles/${vid}/status`, {statusCode: statusCode.value})).data
    if (res.success) {
      message.value = res.message
      closeAction()
      await load()
    } else {
      error.value = res.message || '设置失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '设置请求失败，请检查后端服务。'
  } finally {
    saving.value = false
  }
}

/** 失效车辆（软删除），有未完成任务时后端会拒绝 */
async function disableVehicle(v) {
  if (!confirm(`确认停用车辆 ${v.plateNumber} 吗？（软删除，可追溯）`)) return
  error.value = ''
  message.value = ''
  try {
    const res = (await http.post(`/vehicles/${v.vehicleId}/disable`)).data
    if (res.success) {
      message.value = res.message
      await load()
    } else {
      error.value = res.message || '失效失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '失效请求失败。'
  }
}

// 车辆六状态中文映射
function statusText(s){return statusMap[s]||s}
// 各状态标签配色
function tagClass(s){return {IDLE:'green',TRANSPORT:'blue',LOADING:'orange',UNLOADING:'purple',REFUEL:'amber',MAINTAIN:'red'}[s]||'gray'}
onMounted(load)
</script>
<style scoped>
.op-cell{display:flex;gap:6px}
</style>
