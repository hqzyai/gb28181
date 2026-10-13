<template>
  <div id="operationsForSystemInfo" class="app-container operations-system-info-page">
    <el-descriptions v-for="(value, key) in systemInfoList" :key="key" :title="key" :column="2" :loading="loading" class="operations-system-description">
      <el-descriptions-item v-for="(childValue, childKey) in value" :key="childKey">
        <template slot="label">
          <span>{{ childKey }}</span>
        </template>
        <span v-if="!childValue.startsWith('http')">{{ childValue }}</span>
        <a v-else target="_blank" :href="childValue">{{ childValue }}</a>
      </el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<script>

export default {
  name: 'OperationsSystemInfo',
  data() {
    return {
      loading: false,
      winHeight: window.innerHeight - 220,
      systemInfoList: {}
    }
  },
  created() {
    this.initData()
  },
  methods: {
    initData: function() {
      this.loading = true
      this.$store.dispatch('server/info')
        .then(data => {
          this.systemInfoList = data
        })
        .catch((error) => {
          console.log(error)
        })
        .finally(() => {
          this.loading = false
        })
    }
  }
}
</script>

<style scoped>
.operations-system-description {
  margin-block-end: 32px;
  padding: 20px;
  color: var(--ui-foreground);
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-card);
  box-shadow: none;
}

.operations-system-description >>> .el-descriptions__title {
  font-size: 1rem;
  line-height: 1.5rem;
  font-weight: 600;
}

.operations-system-description >>> .el-descriptions__body {
  color: var(--ui-foreground);
  background: transparent;
}

.operations-system-description >>> .el-descriptions-item__label {
  color: var(--ui-muted-foreground);
  font-weight: 500;
}

.operations-system-description >>> .el-descriptions-item__content {
  min-width: 0;
  overflow-wrap: anywhere;
}

.operations-system-description a {
  color: var(--ui-primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}
</style>
