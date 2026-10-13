<template>
  <div class="navbar">
    <hamburger :is-active="sidebar.opened" class="hamburger-container" @toggleClick="toggleSideBar" />

    <breadcrumb class="breadcrumb-container" />

    <div class="right-menu">
      <el-dropdown class="avatar-container" trigger="click">
        <div class="avatar-wrapper">
          欢迎，{{ name }}
          <i class="el-icon-caret-bottom" />
        </div>
        <el-dropdown-menu slot="dropdown" class="user-dropdown">
          <el-dropdown-item @click.native="changePassword">
            <span style="display:block;">修改密码</span>
          </el-dropdown-item>
          <el-dropdown-item @click.native="logout">
            <span style="display:block;">注销</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
    </div>
    <changePasswordDialog ref="changePasswordDialog"></changePasswordDialog>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import Breadcrumb from '@/components/Breadcrumb'
import Hamburger from '@/components/Hamburger'
import changePasswordDialog from './dialog/changePassword.vue'

export default {
  components: {
    Breadcrumb,
    Hamburger,
    changePasswordDialog
  },
  computed: {
    ...mapGetters([
      'sidebar',
      'name'
    ])
  },
  methods: {
    toggleSideBar() {
      this.$store.dispatch('app/toggleSideBar')
    },
    async logout() {
      await this.$store.dispatch('user/logout')
      console.log('logout')
      this.$router.push(`/login?redirect=${this.$route.fullPath}`)
    },
    changePassword() {
      this.$refs.changePasswordDialog.openDialog(this.logout)
    }
  }
}
</script>

<style lang="scss" scoped>
.navbar {
  height: 50px;
  overflow: hidden;
  position: relative;
  background: var(--ui-background);
  border-bottom: 1px solid var(--ui-border);
  box-shadow: none;

  .hamburger-container {
    line-height: 46px;
    height: 100%;
    float: left;
    cursor: pointer;
    transition: background-color 120ms;
    -webkit-tap-highlight-color:transparent;

    &:hover {
      background: var(--ui-accent)
    }
  }

  .breadcrumb-container {
    float: left;
  }

  .right-menu {
    float: right;
    height: 100%;
    line-height: 50px;


    .right-menu-item {
      display: inline-block;
      padding: 0 8px;
      height: 100%;
      font-size: .875rem;
      color: var(--ui-muted-foreground);
      vertical-align: text-bottom;

      &.hover-effect {
        cursor: pointer;
        transition: background-color 120ms;

        &:hover {
          background: var(--ui-accent)
        }
      }
    }

    .avatar-container {
      margin-right: 32px;

      .avatar-wrapper {
        margin-top: 4px;
        position: relative;
        cursor: pointer;
        .user-avatar {
          cursor: pointer;
          width: 40px;
          height: 40px;
          border-radius: 50%;
        }

        .el-icon-caret-bottom {
          cursor: pointer;
          position: absolute;
          right: -20px;
          top: 19px;
          font-size: .75rem;
        }
      }
    }
  }
}
</style>
