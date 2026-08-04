<template>
  <div :id="'h265Player-' + _uid" ref="container" style="background-color: #000000; position: relative; display: flex; align-items: center; justify-content: center;" @dblclick="fullscreenSwich" @mouseenter="showBar = true" @mouseleave="showBar = false">
    <div :id="'glplayer-' + _uid" ref="playerBox" style="width: 100%; height: 100%; margin: 0 auto;">
      <div v-if="playerLoading" class="play-loading">
        <i class="el-icon-loading" />
        <span style="margin-left: 5px">视频加载中</span>
      </div>
    </div>

    <div v-if="showButton" id="buttonsBox" class="buttons-box" :style="{ opacity: showBar ? 1 : 0, pointerEvents: showBar ? 'auto' : 'none' }">
      <div class="buttons-box-left">
        <i v-if="!playing" class="iconfont icon-play h265web-btn" @click="unPause" />
        <i v-if="playing" class="iconfont icon-pause h265web-btn" @click="pause" />
        <i class="iconfont icon-stop h265web-btn" @click="destroy" />
        <i v-if="isNotMute" class="iconfont icon-audio-high h265web-btn" @click="mute()" />
        <i v-if="!isNotMute" class="iconfont icon-audio-mute h265web-btn" @click="cancelMute()" />
      </div>
      <div class="buttons-box-right">
        <!--          <i class="iconfont icon-file-record1 h265web-btn"></i>-->
        <!--          <i class="iconfont icon-xiangqing2 h265web-btn" ></i>-->
        <i
          class="iconfont icon-camera1196054easyiconnet h265web-btn"
          style="font-size: 1rem !important"
          @click="screenshot"
        />
        <i class="iconfont icon-shuaxin11 h265web-btn" @click="playBtnClick" />
        <i v-if="!fullscreen" class="iconfont icon-weibiaoti10 h265web-btn" @click="fullscreenSwich" />
        <i v-if="fullscreen" class="iconfont icon-weibiaoti11 h265web-btn" @click="fullscreenSwich" />
      </div>
    </div>
  </div>
</template>

<script>
const h265webPlayer = {}
/**
 * 从github上复制的
 * @see https://github.com/numberwolf/h265web.js/blob/master/example_normal/index.js
 */
const token = 'base64:QXV0aG9yOmNoYW5neWFubG9uZ3xudW1iZXJ3b2xmLEdpdGh1YjpodHRwczovL2dpdGh1Yi5jb20vbnVtYmVyd29sZixFbWFpbDpwb3JzY2hlZ3QyM0Bmb3htYWlsLmNvbSxRUTo1MzEzNjU4NzIsSG9tZVBhZ2U6aHR0cDovL3h2aWRlby52aWRlbyxEaXNjb3JkOm51bWJlcndvbGYjODY5NCx3ZWNoYXI6bnVtYmVyd29sZjExLEJlaWppbmcsV29ya0luOkJhaWR1'
import dragZoom from '../../mixins/dragZoom'
export default {
  name: 'H265web',
  mixins: [dragZoom],
  props: ['videoUrl', 'error', 'hasAudio', 'height', 'showButton'],
  data() {
    return {
      playing: false,
      isNotMute: false,
      quieting: false,
      fullscreen: false,
      loaded: false, // mute
      speed: 0,
      kBps: 0,
      btnDom: null,
      videoInfo: null,
      volume: 1,
      rotate: 0,
      vod: true, // 点播
      forceNoOffscreen: false,
      playerWidth: 0,
      playerHeight: 0,
      inited: false,
      playerLoading: false,
      mediaInfo: null,
      showBar: true,
      localVideoUrl: '',
      parentNodeResizeObserver: null,
      resizeRetryTimer: null,
      tearingDown: false
    }
  },
  watch: {
    playing(newData, oldData) {
      this.$emit('playStatusChange', newData)
    },
    immediate: true
  },
  mounted() {},
  beforeDestroy() {
    this.tearingDown = true
    this.clearResizeRetry()
    if (this.parentNodeResizeObserver) {
      this.parentNodeResizeObserver.disconnect()
      this.parentNodeResizeObserver = null
    }
    this.releasePlayer()
  },
  methods: {
    updatePlayerDomSize() {
      const dom = this.$refs.container
      const playerBox = this.$refs.playerBox
      if (!dom || !dom.parentNode || !playerBox || this.tearingDown) {
        return false
      }
      if (!this.parentNodeResizeObserver && typeof ResizeObserver !== 'undefined') {
        this.parentNodeResizeObserver = new ResizeObserver(() => {
          this.updatePlayerDomSize()
        })
        this.parentNodeResizeObserver.observe(dom.parentNode)
      }
      const boxWidth = dom.parentNode.clientWidth
      const boxHeight = dom.parentNode.clientHeight
      let width = boxWidth
      let height = (9 / 16) * width
      if (boxHeight > 0 && boxWidth > boxHeight / 9 * 16) {
        height = boxHeight
        width = boxHeight / 9 * 16
      }

      const clientHeight = Math.min(document.body.clientHeight, document.documentElement.clientHeight)
      if (height > clientHeight) {
        height = clientHeight
        width = (16 / 9) * height
      }

      playerBox.style.width = width + 'px'
      playerBox.style.height = height + 'px'
      this.playerWidth = width
      this.playerHeight = height
      const player = h265webPlayer[this._uid]
      if (this.playing && player && player.resize) {
        player.resize(this.playerWidth, this.playerHeight)
      }
      return width > 0 && height > 0
    },
    resize(width, height) {
      if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
        this.updatePlayerDomSize()
        return
      }
      const playerBox = this.$refs.playerBox
      if (!playerBox || this.tearingDown) return
      this.playerWidth = width
      this.playerHeight = height
      playerBox.style.width = width + 'px'
      playerBox.style.height = height + 'px'
      const player = h265webPlayer[this._uid]
      if (this.playing && player && player.resize) {
        player.resize(this.playerWidth, this.playerHeight)
      }
    },
    create(url) {
      if (this.tearingDown || !this.$refs.playerBox) return
      this.playerLoading = true
      const options = {}
      h265webPlayer[this._uid] = new window.new265webjs(url, Object.assign(
        {
          player: 'glplayer-' + this._uid,
          width: this.playerWidth,
          height: this.playerHeight,
          token: token,
          extInfo: {
            coreProbePart: 0.4,
            probeSize: 8192,
            ignoreAudio: this.hasAudio === null ? 0 : (this.hasAudio ? 0 : 1)
          }
        },
        options
      ))
      const h265web = h265webPlayer[this._uid]
      h265web.onOpenFullScreen = () => {
        if (h265webPlayer[this._uid] !== h265web || this.tearingDown) return
        this.fullscreen = true
      }
      h265web.onCloseFullScreen = () => {
        if (h265webPlayer[this._uid] !== h265web || this.tearingDown) return
        this.fullscreen = false
      }
      h265web.onReadyShowDone = () => {
        if (h265webPlayer[this._uid] !== h265web || this.tearingDown) return
        // 准备好显示了，尝试自动播放
        const result = h265web.play()
        this.playing = result
        this.playerLoading = false
      }
      h265web.onLoadFinish = () => {
        if (h265webPlayer[this._uid] !== h265web || this.tearingDown) return
        this.loaded = true
        // 可以获取mediaInfo
        // @see https://github.com/numberwolf/h265web.js/blob/8b26a31ffa419bd0a0f99fbd5111590e144e36a8/example_normal/index.js#L252C9-L263C11
        this.mediaInfo = h265web.mediaInfo()
      }
      h265web.onPlayTime = (videoPTS) => {
        if (h265webPlayer[this._uid] !== h265web || this.tearingDown) return
        this.$emit('playTimeChange', videoPTS * 1000)
      }
      try {
        h265web.do()
      } catch (error) {
        console.error('H265web -> create failed:', error)
        this.playerLoading = false
        this.releasePlayer()
      }
    },
    screenshot: function() {
      try {
        const canvas = this.renderCurrentFrame()
        const link = document.createElement('a')
        link.download = 'screenshot.png'
        link.href = canvas.toDataURL('image/png').replace('image/png', 'image/octet-stream')
        link.click()
      } catch (error) {
        this.$message.error(error.message || '当前画面截图失败')
      }
    },
    captureFrame(quality = 0.78) {
      const canvas = this.renderCurrentFrame()
      const normalizedQuality = Math.min(1, Math.max(0.1, Number(quality) || 0.78))
      const dataUrl = canvas.toDataURL('image/jpeg', normalizedQuality)
      if (!dataUrl || dataUrl.length < 128) {
        throw new Error('H265 播放器没有返回有效视频帧')
      }
      return {
        dataUrl,
        width: canvas.width,
        height: canvas.height
      }
    },
    renderCurrentFrame() {
      const playerBox = this.$refs.playerBox
      if (!playerBox || !h265webPlayer[this._uid] || !this.playing) {
        throw new Error('H265 播放器尚未准备好抽帧')
      }

      const videos = Array.from(playerBox.querySelectorAll('video'))
      const video = videos.find(item => item.readyState >= 2 && item.videoWidth >= 16 && item.videoHeight >= 16)
      const canvases = Array.from(playerBox.querySelectorAll('canvas'))
        .filter(item => item.width >= 16 && item.height >= 16)
        .sort((left, right) => (right.width * right.height) - (left.width * left.height))
      const source = video || canvases[0]
      const width = video ? video.videoWidth : (source && source.width)
      const height = video ? video.videoHeight : (source && source.height)
      if (!source || !width || !height) {
        throw new Error('H265 播放器当前画面尚未就绪')
      }

      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext('2d', { alpha: false })
      if (!context) {
        throw new Error('浏览器无法创建视频抽帧画布')
      }
      try {
        context.drawImage(source, 0, 0, width, height)
      } catch (error) {
        throw new Error('H265 播放器当前画面抽取失败')
      }
      return canvas
    },
    playBtnClick: function(event) {
      this.play(this.localVideoUrl || this.videoUrl)
    },
    refresh: function() {
      this.play(this.localVideoUrl || this.videoUrl)
    },
    play: function(url) {
      if (!url || this.tearingDown) {
        return
      }
      this.localVideoUrl = url
      this.clearResizeRetry()
      this.releasePlayer()
      if (!this.updatePlayerDomSize()) {
        this.resizeRetryTimer = setTimeout(() => {
          this.resizeRetryTimer = null
          this.play(this.localVideoUrl)
        }, 300)
        return
      }
      this.create(url)
    },
    unPause: function() {
      if (h265webPlayer[this._uid]) {
        h265webPlayer[this._uid].play()
        this.playing = h265webPlayer[this._uid].isPlaying()
      }
      this.err = ''
    },
    pause: function() {
      if (h265webPlayer[this._uid]) {
        h265webPlayer[this._uid].pause()
        this.playing = h265webPlayer[this._uid].isPlaying()
      }
      this.err = ''
    },
    mute: function() {
      if (h265webPlayer[this._uid]) {
        h265webPlayer[this._uid].setVoice(0.0)
        this.isNotMute = false
      }
    },
    cancelMute: function() {
      if (h265webPlayer[this._uid]) {
        h265webPlayer[this._uid].setVoice(1.0)
        this.isNotMute = true
      }
    },
    destroy: function() {
      this.clearResizeRetry()
      this.releasePlayer()
      this.playing = false
      this.playerLoading = false
      this.err = ''
    },
    stop: function() {
      this.destroy()
    },
    fullscreenSwich: function() {
      const player = h265webPlayer[this._uid]
      if (!player) return
      const isFull = this.isFullscreen()
      if (isFull) {
        player.closeFullScreen()
      } else {
        player.fullScreen()
      }
      this.fullscreen = !isFull
    },
    isFullscreen: function() {
      return document.fullscreenElement ||
        document.msFullscreenElement ||
        document.mozFullScreenElement ||
        document.webkitFullscreenElement || false
    },
    setPlaybackRate: function(speed) {
      const player = h265webPlayer[this._uid]
      if (player) player.setPlaybackRate(speed)
    },
    clearResizeRetry() {
      if (this.resizeRetryTimer) {
        clearTimeout(this.resizeRetryTimer)
        this.resizeRetryTimer = null
      }
    },
    releasePlayer() {
      const player = h265webPlayer[this._uid]
      delete h265webPlayer[this._uid]
      if (player && player.release) {
        try {
          player.release()
        } catch (error) {
          console.warn('H265web -> release failed:', error)
        }
      }
      this.playing = false
      this.loaded = false
    },
    getVideoElement() {
      return this.$refs.playerBox
    },
    getVideoRect() {
      const element = this.getVideoElement()
      return element ? element.getBoundingClientRect() : null
    }
  }
}
</script>

<style>
.play-loading {
  width: 100%;
  height: 100%;
  color: rgb(255, 255, 255);
  display: flex;
  align-items: center;
  margin: 0 auto;
  justify-content: center;
  font-size: 18px;
}
.buttons-box {
  width: 100%;
  height: 56px;
  background: linear-gradient(to top, rgba(0, 0, 0, 1), rgba(0, 0, 0, 0));
  position: absolute;
  transition: opacity 0.3s ease;
  display: -webkit-box;
  display: -ms-flexbox;
  display: flex;
  align-items: flex-end;
  padding-bottom: 10px;
  left: 0;
  bottom: 0;
  user-select: none;
  z-index: 10;
}

.h265web-btn {
  width: 20px;
  color: rgb(255, 255, 255);
  margin: 0px 10px;
  padding: 0px 2px;
  cursor: pointer;
  text-align: center;
  font-size: 0.8rem !important;
}

.buttons-box-right {
  position: absolute;
  right: 0;
  bottom: 10px;
}
.player-loading {
  width: fit-content;
  height: 30px;
  position: absolute;
  left: calc(50% - 52px);
  top: calc(50% - 52px);
  color: #fff;
  font-size: 16px;
}
.player-loading i{
  font-size: 24px;
  line-height: 24px;
  text-align: center;
  display: block;
}
.player-loading span{
  display: inline-block;
  font-size: 16px;
  height: 24px;
  line-height: 24px;
}
</style>
