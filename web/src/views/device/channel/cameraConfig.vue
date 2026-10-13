<template>
  <div id="dhCameraConfigPage">
    <el-page-header content="相机配置" @back="$emit('close')" />
    <div class="camera-config-body">
      <div class="config-sidebar">
        <el-menu :default-active="activeTab" @select="handleMenuSelect">
          <el-menu-item index="base">
            <ui-icon name="House" :size="16" style="margin-right: 8px;" />
            <span>基础属性</span>
          </el-menu-item>
          <el-menu-item index="alarm">
            <ui-icon name="Bell" :size="16" style="margin-right: 8px;" />
            <span>报警配置</span>
          </el-menu-item>
          <el-menu-item index="recordControl">
            <ui-icon name="Video" :size="16" style="margin-right: 8px;" />
            <span>录像控制</span>
          </el-menu-item>
          <el-menu-item index="upgrade">
            <ui-icon name="Wrench" :size="16" style="margin-right: 8px;" />
            <span>设备运维</span>
          </el-menu-item>
        </el-menu>
      </div>
      <div class="config-content">
        <basicPropertyConfig v-if="activeTab === 'base'" :device-id="deviceId" :channel-device-id="channelDeviceId" />
        <alarmConfig v-if="activeTab === 'alarm'" :device-id="deviceId" :channel-device-id="channelDeviceId" />
        <upgradeConfig v-if="activeTab === 'upgrade'" :device-id="deviceId" :channel-device-id="channelDeviceId" />
        <recordControlConfig v-if="activeTab === 'recordControl'" :device-id="deviceId" :channel-device-id="channelDeviceId" />
      </div>
    </div>
  </div>
</template>

<script>
import basicPropertyConfig from './basicPropertyConfig.vue'
import alarmConfig from './alarmConfig.vue'
import upgradeConfig from './upgradeConfig.vue'
import recordControlConfig from './recordControlConfig.vue'

export default {
  name: 'CameraConfigPage',
  components: { basicPropertyConfig, alarmConfig, upgradeConfig, recordControlConfig },
  props: {
    deviceId: { type: String, default: null },
    channelDeviceId: { type: String, default: null }
  },
  data() {
    return {
      activeTab: 'base'
    }
  },
  methods: {
    handleMenuSelect(index) {
      this.activeTab = index
    }
  }
}
</script>

<style scoped>
#dhCameraConfigPage {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.camera-config-body {
  flex: 1;
  display: flex;
  overflow: hidden;
  padding-top: 16px;
}
.config-sidebar {
  width: 160px;
  flex: none;
  border-right: 1px solid var(--ui-border);
}
.config-sidebar .el-menu {
  border-right: none;
}
.config-content {
  flex: 1;
  overflow: auto;
  padding: 0 16px;
}
.placeholder-tab {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: var(--ui-muted-foreground);
  font-size: 1rem;
  line-height: 1.5rem;
}
</style>
