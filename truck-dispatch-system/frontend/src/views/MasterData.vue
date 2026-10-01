<template>
<div>
 <div class="section-head">
  <div><h2>基础数据</h2><p>车型装载能力、货物分类、货物-车型匹配规则、POI 站点分类统计</p></div>
  <button class="btn" @click="loadAll">刷新</button>
 </div>
 <div v-if="error" class="error">{{error}}</div>

 <!-- POI 分类统计：对照评级数量要求 500（良）/1000（优） -->
 <div class="panel"><div class="panel-title">POI 分类统计（6 类，评级要求 3+ 类）</div>
  <div class="cat-grid">
   <div v-for="c in categoryCounts" :key="c.categoryId" class="cat-card">
    <div class="cat-head"><i :style="{background:catColor(c.categoryId)}"></i>
     <b>{{c.categoryName}}</b><span>{{c.count}} 个</span></div>
    <div class="progress"><i :style="{width:Math.min(100,c.count/TARGET*100)+'%',background:catColor(c.categoryId)}"></i></div>
   </div>
  </div>
  <div class="muted">总 POI 数：<b>{{totalPoi}}</b> 个；进度条以 {{TARGET}} 个/类为满格参考。可用 tools/poi_crawler.py 扩充至 500/1000。</div>
 </div>

 <!-- 车型与装载能力 -->
 <div class="panel table-panel" style="margin-top:20px">
  <div class="panel-title" style="padding:18px 18px 0">车型与装载能力</div>
  <table><thead><tr>
   <th>车型</th><th>最大载重(kg)</th><th>最大容积(m³)</th><th>燃油</th>
   <th>平均速度(km/h)</th><th>百公里油耗(L)</th><th>可装货物</th><th>不可装</th>
  </tr></thead><tbody>
   <tr v-for="t in vehicleTypes" :key="t.typeId">
    <td class="strong">{{t.typeName}}</td><td>{{t.maxLoad}}</td><td>{{t.maxVolume}}</td>
    <td>{{t.fuelType}}</td><td>{{t.avgSpeed}}</td><td>{{t.fuelConsumption}}</td>
    <td>{{t.canCarry}}</td><td class="c-red">{{t.cannotCarry}}</td>
   </tr>
  </tbody></table>
 </div>

 <!-- 货物分类 × 车型 匹配矩阵 -->
 <div class="panel table-panel" style="margin-top:20px">
  <div class="panel-title" style="padding:18px 18px 0">货物-车型匹配矩阵（运输任务派发时的匹配依据）</div>
  <table class="matrix-table"><thead><tr>
   <th>货物分类</th><th v-for="t in matrixTypes" :key="t.typeId">{{t.typeName}}</th>
  </tr></thead><tbody>
   <tr v-for="row in matrix.rows" :key="row.categoryId">
    <td class="strong">{{row.categoryName}}</td>
    <td v-for="cell in row.cells" :key="cell.typeId" class="matrix-cell">
     <span v-if="cell.preferred" class="tag green">✓ 优选</span>
     <span v-else-if="cell.compatible" class="tag blue">可装</span>
     <span v-else class="muted">—</span>
    </td>
   </tr>
  </tbody></table>
 </div>

 <!-- POI 站点列表（可按分类过滤） -->
 <div class="panel table-panel" style="margin-top:20px">
  <div class="panel-title poi-list-head">POI 站点列表
   <select v-model="filterCategory" @change="loadPois">
    <option :value="null">全部分类</option>
    <option v-for="c in categoryCounts" :key="c.categoryId" :value="c.categoryId">{{c.categoryName}}</option>
   </select>
  </div>
  <table><thead><tr>
   <th>ID</th><th>名称</th><th>分类</th><th>经度</th><th>纬度</th><th>地址</th><th>能力(吨)</th>
  </tr></thead><tbody>
   <tr v-for="p in pois" :key="p.poiId">
    <td>{{p.poiId}}</td><td class="strong">{{p.poiName}}</td>
    <td><i class="dot-color" :style="{background:catColor(p.categoryId)}"></i>{{p.categoryName}}</td>
    <td>{{p.longitude}}</td><td>{{p.latitude}}</td>
    <td>{{p.address||'-'}}</td><td>{{p.capacity??'-'}}</td>
   </tr>
  </tbody></table>
 </div>
</div>
</template>
<script setup>
// 基础数据页：车型/货物分类/匹配矩阵来自主数据接口，POI 列表支持分类过滤
import {ref,reactive,computed,onMounted} from 'vue'
import http from '../api/http'

const TARGET = 1000 // 评级"优"的 POI 数量参考线
const error = ref('')
const vehicleTypes = ref([])
const categoryCounts = ref([])
const pois = ref([])
const filterCategory = ref(null)
const matrix = reactive({rows: []})

const CAT_COLORS = {1:'#dc2626',2:'#2563eb',3:'#f59e0b',4:'#7c3aed',5:'#64748b',6:'#059669'}
const catColor = id => CAT_COLORS[id] || '#94a3b8'
const totalPoi = computed(() => categoryCounts.value.reduce((s,c) => s+c.count, 0))
// 矩阵表头车型（取第一行的 cells；各行列车型一致）
const matrixTypes = computed(() => matrix.rows.length ? matrix.rows[0].cells : [])

async function loadPois() {
  const params = {limit: 2000}
  if (filterCategory.value != null) params.categoryId = filterCategory.value
  pois.value = (await http.get('/pois', {params})).data
}

async function loadAll() {
  error.value = ''
  try {
    const [types, cats, mx] = await Promise.all([
      http.get('/vehicle-types'), http.get('/poi-categories'), http.get('/match/matrix')
    ])
    vehicleTypes.value = types.data
    categoryCounts.value = cats.data
    Object.assign(matrix, mx.data)
    await loadPois()
  } catch (e) {
    error.value = '基础数据加载失败，请确认后端（8888）与 MySQL 已启动。'
  }
}
onMounted(loadAll)
</script>
