<template>
  <div class="sidebar-logo-container" :class="{ collapse }">
    <router-link class="sidebar-logo-link" to="/" aria-label="HQZY 首页">
      <img :src="logo" class="sidebar-logo" alt="" :draggable="false">
    </router-link>
  </div>
</template>

<script>
export default {
  name: 'SidebarLogo',
  props: { collapse: { type: Boolean, required: true } },
  data() { return { logo: process.env.BASE_URL + 'hqzyai-logomark.svg' } }
}
</script>

<style lang="scss" scoped>
.sidebar-logo-container {
  width: 100%;
  height: 50px;
  background: var(--ui-sidebar);
  border-bottom: 1px solid var(--ui-sidebar-border);
  .sidebar-logo-link { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; }
  .sidebar-logo { width: 28px; height: 28px; object-fit: contain; flex-shrink: 0; display: block; }
  &.collapse .sidebar-logo { width: 24px; height: 24px; }
}
</style>
