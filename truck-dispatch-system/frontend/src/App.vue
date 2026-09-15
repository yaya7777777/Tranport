<template>
<div class="layout">
 <aside class="sidebar">
  <div class="brand"><div class="brand-icon">🚚</div><div><div class="brand-title">运输调度平台</div><div class="brand-subtitle">TRUCK DISPATCH</div></div></div>
  <nav><router-link to="/dashboard">📊 运行总览</router-link><router-link to="/vehicles">🚚 车辆管理</router-link><router-link to="/orders">📦 订单调度</router-link></nav>
  <div class="sidebar-foot">MySQL · Spring Boot · Vue 3</div>
 </aside>
 <main class="main">
  <header class="topbar"><div><div class="page-title">货车调度系统</div><div class="page-desc">车辆、订单、路线与实时定位统一管理</div></div><div class="status-pill">● 系统在线</div></header>
  <section class="content"><router-view/></section>
 </main>
</div>
</template>
