<template>
  <div :class="{ 'is-active': isActive }" style="padding: 0 16px;" @click="toggleClick">
    <ui-icon
      :name="isActive ? 'PanelLeftClose' : 'PanelLeftOpen'"
      :size="20"
      :class="{ 'is-active': isActive }"
      class="hamburger"
    />
  </div>
</template>

<script>
import UiIcon from '@/components/UiIcon'

export default {
  name: 'Hamburger',
  components: { UiIcon },
  props: {
    isActive: {
      type: Boolean,
      default: false
    }
  },
  methods: {
    toggleClick() {
      this.$emit('toggleClick')
    }
  }
}
</script>

<style scoped>
.hamburger {
  display: inline-block;
  vertical-align: middle;
  width: 20px;
  height: 20px;
}

</style>
