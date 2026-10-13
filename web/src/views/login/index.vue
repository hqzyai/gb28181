<template>
  <div class="login-container">
    <div

      style="justify-content: center;
      align-items: center;
      width: 100%;
      height: 100vh;
      display: flex;
      background: var(--ui-muted);
      background-position: center center;
      background-repeat: no-repeat;
      background-size: cover;"
    >
      <el-form ref="loginForm" :model="loginForm" :rules="loginRules" class="login-form" auto-complete="on" label-position="left">
        <div class="title-container">
          <h3 class="title">WVP视频平台</h3>
        </div>

        <el-form-item prop="username">
          <span class="svg-container">
            <svg-icon icon-class="user" />
          </span>
          <el-input
            ref="username"
            v-model="loginForm.username"
            placeholder="用户名"
            name="username"
            type="text"
            tabindex="1"
            auto-complete="on"
          />
        </el-form-item>

        <el-form-item prop="password">
          <span class="svg-container">
            <svg-icon icon-class="password" />
          </span>
          <el-input
            :key="passwordType"
            ref="password"
            v-model="loginForm.password"
            :type="passwordType"
            placeholder="密码"
            name="password"
            tabindex="2"
            auto-complete="on"
            @keyup.enter.native="handleLogin"
          />
          <span class="show-pwd" @click="showPwd">
            <svg-icon :icon-class="passwordType === 'password' ? 'eye' : 'eye-open'" />
          </span>
        </el-form-item>

        <el-button :loading="loading" type="primary" style="width:100%;margin-bottom:0;" @click.native.prevent="handleLogin">登录</el-button>

      </el-form>
    </div>

  </div>
</template>

<script>
import {validUsername} from '@/utils/validate'

export default {
  name: 'Login',
  data() {
    const validateUsername = (rule, value, callback) => {
      if (!validUsername(value)) {
        callback(new Error('请输入用户名'))
      } else {
        callback()
      }
    }
    const validatePassword = (rule, value, callback) => {
      callback()
    }
    return {
      loginForm: {
        username: '',
        password: ''
      },
      loginRules: {
        username: [{ required: true, trigger: 'blur', validator: validateUsername }],
        password: [{ required: true, trigger: 'blur', validator: validatePassword }]
      },
      loading: false,
      passwordType: 'password',
      redirect: undefined
    }
  },
  watch: {
    $route: {
      handler: function(route) {
        this.redirect = route.query && route.query.redirect
      },
      immediate: true
    }
  },
  methods: {
    showPwd() {
      if (this.passwordType === 'password') {
        this.passwordType = ''
      } else {
        this.passwordType = 'password'
      }
      this.$nextTick(() => {
        this.$refs.password.focus()
      })
    },
    handleLogin() {
      this.$refs.loginForm.validate(valid => {
        if (valid) {
          this.loading = true
          this.$store.dispatch('user/login', this.loginForm).then((re) => {
            this.$router.push({ path: this.redirect || '/' })
            this.loading = false
          }).catch((error) => {
              this.$message({
                showClose: true,
                message: error,
                type: 'error'
              })
            })
            .finally(() => {
              this.loading = false
            })
        } else {
          console.log('error submit!!')
          return false
        }
      })
    }
  }
}
</script>

<style lang="scss">
.login-container {
  margin: 0;
  width: 100%;
  min-height: 100%;
  background: var(--ui-muted);
  .el-input { display: inline-block; width: calc(100% - 40px); }
  .el-input input {
    color: var(--ui-foreground);
    background: var(--ui-background);
    caret-color: var(--ui-foreground);
    padding-inline-end: 2rem;
    &:-webkit-autofill { -webkit-text-fill-color: var(--ui-foreground); box-shadow: 0 0 0 1000px var(--ui-background) inset; }
  }
  .el-form-item { margin-bottom: 1rem; }
}
</style>

<style lang="scss" scoped>
.login-container {
  .login-form {
    position: relative;
    width: 448px;
    max-width: calc(100% - 2rem);
    padding: 2rem;
    margin: 0 auto;
    background: var(--ui-card);
    border-radius: var(--ui-radius-card);
    border: 1px solid var(--ui-border);
  }
  .svg-container { color: var(--ui-muted-foreground); vertical-align: middle; width: 32px; display: inline-block; }
  .title-container .title { font-size: 1.75rem; line-height: 2.25rem; color: var(--ui-foreground); margin: 0 auto 2rem; text-align: center; font-weight: 600; }
  .show-pwd { position: absolute; right: 8px; top: 0; min-width: 24px; min-height: 36px; color: var(--ui-muted-foreground); cursor: pointer; display: inline-flex; align-items: center; }
}
</style>
