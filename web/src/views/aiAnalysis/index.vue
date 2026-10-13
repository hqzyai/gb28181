<template>
  <div class="ai-analysis-page">
    <aside class="channel-panel">
      <DeviceTree @clickEvent="handleChannelClick" />
    </aside>

    <main class="analysis-workspace">
      <section class="video-section panel-card">
        <div class="section-toolbar">
          <div class="channel-title">
            <span class="status-dot" :class="streamInfo ? 'online' : ''" />
            <strong>{{ selectedChannelName || '请选择 GB28181 通道' }}</strong>
            <span v-if="selectedChannelId" class="channel-id">{{ selectedChannelId }}</span>
          </div>
          <div class="toolbar-actions">
            <el-select v-model="engine" size="mini" class="engine-select" :disabled="running || analyzing" @change="changeEngine">
              <el-option v-for="item in engines" :key="item.id" :label="item.label + (item.ready ? '' : '（未就绪）')" :value="item.id" :disabled="!item.ready" />
            </el-select>
            <el-select v-model="playerType" size="mini" class="player-select" :disabled="running">
              <el-option label="Jessibuca" value="jessibuca" />
              <el-option label="WebRTC" value="webRTC" />
              <el-option label="H265web" value="h265web" />
            </el-select>
            <el-select v-model="intervalSeconds" size="mini" class="interval-select" :disabled="running" @change="changeInterval">
              <el-option v-for="seconds in availableIntervalOptions" :key="seconds" :label="seconds + ' 秒/帧'" :value="seconds" />
            </el-select>
            <el-button
              v-if="!running"
              type="primary"
              size="mini"
              :disabled="!streamInfo || !selectedEngineReady"
              @click="startAnalysis"
            ><ui-icon name="Play" :size="16" aria-hidden="true" /><span>开始识别</span></el-button>
            <el-button
              v-else
              type="danger"
              size="mini"
              @click="stopAnalysis"
            ><ui-icon name="Pause" :size="16" aria-hidden="true" /><span>停止识别</span></el-button>
            <el-button
              size="mini"
              :loading="analyzing"
              :disabled="!streamInfo || !selectedEngineReady || running"
              @click="analyzeOnce()"
            ><ui-icon v-if="!analyzing" name="Camera" :size="16" aria-hidden="true" /><span>识别一帧</span></el-button>
          </div>
        </div>

        <div class="video-stage" v-loading="streamLoading" element-loading-text="正在拉取 GB28181 视频流">
          <div v-if="!streamInfo" class="empty-video">
            <ui-icon name="Video" :size="24" aria-hidden="true" />
            <p>{{ streamError || '请从左侧选择在线摄像头通道' }}</p>
          </div>
          <PlayerTabs
            v-else
            ref="player"
            :player-type="playerType"
            :show-tab="false"
            :show-button="true"
            @playTimeChange="handlePlayTimeChange"
          />
          <div v-if="running || analyzing" class="analysis-running-badge">
            <span class="pulse-dot" />
            {{ analyzing ? '正在分析当前视频帧' : engine === 'yolo' ? taskState.error ? 'YOLO 检测重试中' : 'YOLO 后台持续检测中' : 'AI 持续识别中' }}
          </div>
        </div>
        <div v-if="engine === 'yolo'" class="engine-note">
          关闭页面后后台检测仍继续。右侧显示最新抽帧标记，蓝色为目标，橙色为待复核风险。
          <span v-if="taskState.error" class="task-error">{{ taskState.error }}</span>
        </div>
      </section>

      <section class="result-section panel-card">
        <div class="result-header">
          <div>
            <h3>{{ selectedHistoryAnalysisId ? '历史识别记录' : '识别结果' }}</h3>
            <p v-if="latestResult">{{ formatTime(latestResult.capturedAt || latestResult.analyzedAt) }} · {{ latestResult.engine || 'vision' }} · 耗时 {{ latestResult.elapsedMs }} ms</p>
            <p v-else>识别结果会在这里实时更新</p>
          </div>
          <div class="result-header-actions">
            <el-button v-if="selectedHistoryAnalysisId" size="mini" type="primary" plain @click="returnToLive">返回实时结果</el-button>
            <el-tag v-if="latestResult" :type="statusTagType(latestResult.status)" effect="dark">
              {{ latestResult.status }}
            </el-tag>
          </div>
        </div>

        <div v-if="latestFrame && latestResult" class="result-body">
          <div class="annotated-frame">
            <img :src="latestFrame.dataUrl" alt="AI识别视频帧">
            <div
              v-for="(box, index) in latestResult.detections"
              :key="index"
              class="risk-box"
              :class="detectionBoxClass(box)"
              :style="boxStyle(box, latestResult)"
            >
              <span>{{ box.type }} {{ Math.round(box.confidence * 100) }}% · {{ box.source || 'vision' }}</span>
            </div>
          </div>

          <div class="record-description">
            <h4>目标与风险</h4>
            <p class="detection-count">目标 {{ objectCount }} 个 · 疑似风险 {{ riskCount }} 个 · {{ latestResult.modelVersion || latestResult.model }}</p>
            <el-tag v-for="(item, index) in latestResult.detections" :key="'object-' + index" size="mini" :type="item.kind === 'object' ? 'info' : 'warning'" class="detection-tag">{{ item.type }} {{ formatConfidence(item.confidence) }}{{ item.kind === 'risk' ? ' · ' + item.status : '' }}</el-tag>
            <div class="record-meta-grid">
              <div class="record-meta-item">
                <span>工地：</span>
                <strong>{{ latestResult.siteName || latestResult.channelName }}</strong>
              </div>
              <div class="record-meta-item">
                <span>检测时间：</span>
                <strong>{{ formatTime(latestResult.analyzedAt) }}</strong>
              </div>
              <div class="record-meta-item">
                <span>风险类型：</span>
                <strong>{{ latestResult.riskName }}</strong>
              </div>
              <div class="record-meta-item">
                <span>处理状态：</span>
                <strong>{{ latestResult.status }}</strong>
              </div>
              <div class="record-meta-item">
                <span>置信度：</span>
                <strong>{{ formatConfidence(latestResult.confidence) }}</strong>
              </div>
            </div>
            <div class="result-guidance">
              <p>{{ latestResult.summary }}</p>
              <ul v-if="latestResult.tips && latestResult.tips.length" class="tips-list">
                <li v-for="tip in latestResult.tips" :key="tip">{{ tip }}</li>
              </ul>
            </div>
          </div>
        </div>

        <div v-else class="empty-result">
          <ui-icon name="Cpu" :size="24" aria-hidden="true" />
          <p>{{ taskState.error || '选择通道并点击“开始识别”' }}</p>
          <span>{{ taskState.error ? '本帧识别未完成，请等待自动重试或检查服务状态' : '系统会按设定间隔从正在播放的 GB28181 视频中抽帧分析' }}</span>
        </div>
      </section>

      <section class="history-section panel-card">
        <div class="history-title">
          <h3>历史识别记录</h3>
          <span>{{ selectedChannelId ? '当前摄像头共 ' + historyTotal + ' 条，每页 10 条' : '请选择摄像头查看独立历史记录' }}</span>
        </div>
        <div class="history-table-wrap" v-loading="historyLoading">
          <el-table
            :data="history"
            size="mini"
            stripe
            :row-class-name="historyRowClass"
            @row-click="selectHistory"
          >
            <el-table-column label="截图" width="108">
              <template slot-scope="scope">
                <img v-if="scope.row.imageObjectUrl" class="history-thumb" :src="scope.row.imageObjectUrl" alt="历史识别帧">
                <span v-else class="history-image-placeholder">加载中</span>
              </template>
            </el-table-column>
            <el-table-column label="检测时间" min-width="168">
              <template slot-scope="scope">{{ formatTime(scope.row.analyzedAt) }}</template>
            </el-table-column>
            <el-table-column prop="siteName" label="工地" min-width="150" show-overflow-tooltip />
            <el-table-column label="引擎" width="90"><template slot-scope="scope">{{ scope.row.engine || 'vision' }}</template></el-table-column>
            <el-table-column prop="riskName" label="风险类型" min-width="190" show-overflow-tooltip />
            <el-table-column label="处理状态" width="110">
              <template slot-scope="scope">
                <el-tag size="mini" :type="statusTagType(scope.row.status)">{{ scope.row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="置信度" width="90">
              <template slot-scope="scope">{{ formatConfidence(scope.row.confidence) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="72" fixed="right">
              <template slot-scope="scope">
                <el-button type="text" size="mini" @click.stop="selectHistory(scope.row)">查看</el-button>
              </template>
            </el-table-column>
            <template slot="empty">
              <div class="history-empty">
                <ui-icon name="History" :size="24" aria-hidden="true" />
                <span>{{ selectedChannelId ? '当前摄像头暂无历史识别记录' : '请先选择摄像头' }}</span>
              </div>
            </template>
          </el-table>
        </div>
        <div v-if="historyTotal > 0" class="history-pagination">
          <el-pagination
            background
            layout="total, prev, pager, next"
            :current-page="historyPage"
            :page-size="historyPageSize"
            :total="historyTotal"
            @current-change="handleHistoryPageChange"
          />
        </div>
      </section>
    </main>

    <el-alert
      v-if="configLoaded && !aiConfigured"
      class="config-alert"
      title="AI 识别服务尚未配置"
      type="warning"
      :closable="false"
      show-icon
      description="请联系管理员检查识别引擎状态，再刷新页面。"
    />
  </div>
</template>

<script>
import DeviceTree from '@/views/common/DeviceTree.vue'
import PlayerTabs from '@/views/common/playerTabs.vue'
import { analyzeVideoFrame, getAiAnalysisConfig, getAiHistory, getAiHistoryImage, startAiTask, stopAiTask, getAiTask, getAiTaskLatest } from '@/api/aiAnalysis'

export default {
  name: 'AiAnalysis',
  components: { DeviceTree, PlayerTabs },
  data() {
    return {
      configLoaded: false,
      aiConfigured: false,
      engine: 'vision',
      engines: [],
      taskState: {},
      taskPollTimer: null,
      lastHistoryRefresh: 0,
      selectedChannelId: '',
      selectedChannelName: '',
      streamInfo: null,
      streamLoading: false,
      streamError: '',
      playerType: 'jessibuca',
      intervalSeconds: 10,
      visionIntervalSeconds: 10,
      yoloIntervalSeconds: 2,
      minRequestIntervalSeconds: 3,
      intervalOptions: [2, 5, 10, 15, 30, 60],
      running: false,
      analyzing: false,
      analysisTimer: null,
      requestGeneration: 0,
      historyRequestId: 0,
      currentVideoTimeMs: null,
      latestFrame: null,
      latestResult: null,
      latestLiveFrame: null,
      latestLiveResult: null,
      selectedHistoryAnalysisId: '',
      pinnedHistoryImageUrl: '',
      history: [],
      historyLoading: false,
      historyPage: 1,
      historyPageSize: 10,
      historyTotal: 0
    }
  },
  computed: {
    availableIntervalOptions() {
      const minimum = this.engine === 'yolo' ? 1 : Math.max(3, this.minRequestIntervalSeconds)
      return this.intervalOptions.filter(seconds => seconds >= minimum && (this.engine !== 'yolo' || seconds <= 60))
    },
    selectedEngineReady() {
      return this.engines.some(item => item.id === this.engine && item.ready)
    },
    objectCount() {
      return this.latestResult ? (this.latestResult.detections || []).filter(item => item.kind === 'object').length : 0
    },
    riskCount() {
      return this.latestResult ? (this.latestResult.detections || []).filter(item => !['object', 'unknown'].includes(item.kind)).length : 0
    }
  },
  created() {
    const savedPlayer = window.localStorage.getItem('globalPlayer')
    if (['jessibuca', 'webRTC', 'h265web'].includes(savedPlayer)) {
      this.playerType = savedPlayer
    }
    this.loadConfig()
  },
  beforeDestroy() {
    this.detachMonitoring()
    this.requestGeneration += 1
    this.clearHistorySelection()
    this.revokeHistoryUrls()
    const player = this.$refs.player
    if (player && player.destroy) player.destroy()
  },
  methods: {
    async loadConfig() {
      try {
        const response = await getAiAnalysisConfig()
        const data = response && response.data ? response.data : {}
        this.aiConfigured = Boolean(data.configured)
        this.engines = Array.isArray(data.engines) ? data.engines : [{ id: 'vision', label: '多模态场景分析', ready: this.aiConfigured }]
        if (!this.running) {
          if (this.engines.some(item => item.id === data.defaultEngine && item.ready)) this.engine = data.defaultEngine
          else if (!this.selectedEngineReady) this.engine = (this.engines.find(item => item.ready) || { id: 'vision' }).id
        }
        const minimum = Number(data.minRequestIntervalSeconds)
        this.minRequestIntervalSeconds = Number.isInteger(minimum) && minimum > 0 ? Math.max(3, minimum) : 3
        const taskInterval = Number(data.taskIntervalSeconds)
        const visionInterval = Number(data.defaultIntervalSeconds)
        if (!this.running) {
          this.yoloIntervalSeconds = Number.isInteger(taskInterval) && taskInterval >= 1 && taskInterval <= 60 ? taskInterval : 2
          this.visionIntervalSeconds = Number.isInteger(visionInterval) && visionInterval >= this.minRequestIntervalSeconds
            ? visionInterval : Math.max(10, this.minRequestIntervalSeconds)
          this.intervalSeconds = this.engine === 'yolo' ? this.yoloIntervalSeconds : this.visionIntervalSeconds
        }
        this.ensureIntervalOption(this.yoloIntervalSeconds)
        this.ensureIntervalOption(this.visionIntervalSeconds)
      } catch (error) {
        this.aiConfigured = false
      } finally {
        this.configLoaded = true
      }
    },
    async handleChannelClick(channelId, channel) {
      this.detachMonitoring()
      this.taskState = {}
      this.requestGeneration += 1
      this.selectedChannelId = String(channelId || '')
      this.selectedChannelName = channel && (channel.name || channel.gbName)
        ? (channel.name || channel.gbName)
        : this.selectedChannelId
      this.streamInfo = null
      this.streamError = ''
      this.streamLoading = true
      this.currentVideoTimeMs = null
      this.resetResultState()
      this.revokeHistoryUrls()
      this.history = []
      this.historyPage = 1
      this.historyTotal = 0
      const generation = this.requestGeneration
      this.loadHistory(1, generation)
      this.restoreTask(generation)
      try {
        const data = await this.$store.dispatch('commonChanel/playChannel', channelId)
        if (generation !== this.requestGeneration || this._isBeingDestroyed) return
        this.streamInfo = data.transcodeStream || data
        await this.$nextTick()
        if (this.$refs.player) this.$refs.player.setStreamInfo(this.streamInfo)
      } catch (error) {
        if (generation !== this.requestGeneration) return
        this.streamError = '视频拉流失败：' + this.errorMessage(error)
      } finally {
        if (generation === this.requestGeneration) this.streamLoading = false
      }
    },
    async restoreTask(generation) {
      try {
        const response = await getAiTask(this.selectedChannelId)
        if (generation !== this.requestGeneration || this._isBeingDestroyed) return
        this.taskState = response.data || {}
        if (this.taskState.running) {
          this.engine = 'yolo'
          this.syncTaskInterval(this.taskState)
          this.running = true
          this.pollTask(generation)
        }
      } catch (error) {
        if (generation === this.requestGeneration) this.taskState = { error: '后台任务状态读取失败：' + this.errorMessage(error) }
      }
    },
    async startAnalysis() {
      if (!this.streamInfo || !this.selectedEngineReady || this.running) return
      if (this.engine === 'yolo') {
        try {
          const generation = this.requestGeneration
          const response = await startAiTask(this.selectedChannelId, this.intervalSeconds)
          if (generation !== this.requestGeneration || this._isBeingDestroyed) return
          this.resetResultState()
          this.taskState = response.data || {}
          this.syncTaskInterval(this.taskState)
          this.running = Boolean(this.taskState.running)
          this.pollTask(generation)
        } catch (error) { this.$message.error('后台检测启动失败：' + this.errorMessage(error)) }
        return
      }
      this.resetResultState()
      this.running = true
      this.scheduleNextAnalysis(0)
    },
    async stopAnalysis(showMessage = true, clearHistoryPin = true) {
      const generation = this.requestGeneration
      if (this.engine === 'yolo' && this.running) {
        try {
          const response = await stopAiTask(this.selectedChannelId)
          if (generation !== this.requestGeneration || this._isBeingDestroyed) return
          this.taskState = response.data || {}
        } catch (error) { this.$message.error('后台检测停止失败：' + this.errorMessage(error)); return }
      }
      this.detachMonitoring()
      if (clearHistoryPin) this.returnToLive()
      if (showMessage) this.$message.info('已停止持续识别')
    },
    detachMonitoring() {
      this.running = false
      if (this.analysisTimer) clearTimeout(this.analysisTimer)
      if (this.taskPollTimer) clearTimeout(this.taskPollTimer)
      this.analysisTimer = null
      this.taskPollTimer = null
    },
    async pollTask(generation) {
      if (!this.running || generation !== this.requestGeneration || this._isBeingDestroyed) return
      try {
        const responses = await Promise.all([getAiTask(this.selectedChannelId), getAiTaskLatest(this.selectedChannelId)])
        if (!this.running || generation !== this.requestGeneration || this._isBeingDestroyed) return
        this.taskState = responses[0].data || {}
        this.syncTaskInterval(this.taskState)
        this.running = Boolean(this.taskState.running)
        const latest = responses[1].data || {}
        if (latest.ready && latest.imageData && latest.result) {
          this.updateLiveResult({ dataUrl: latest.imageData }, latest.result)
        } else {
          if (latest.error) this.taskState = Object.assign({}, this.taskState, { error: latest.error })
          this.clearLiveResult()
        }
        if (Date.now() - this.lastHistoryRefresh > 30000) {
          this.lastHistoryRefresh = Date.now()
          this.loadHistory(1, generation, '', false)
        }
      } catch (error) {
        if (!this.running || generation !== this.requestGeneration || this._isBeingDestroyed) return
        this.taskState = Object.assign({}, this.taskState, { error: '后台状态读取失败：' + this.errorMessage(error) })
        this.clearLiveResult()
      }
      if (this.running && generation === this.requestGeneration) {
        this.taskPollTimer = setTimeout(() => this.pollTask(generation), 2000)
      }
    },
    ensureIntervalOption(interval) {
      if (!this.intervalOptions.includes(interval)) this.intervalOptions.push(interval)
      this.intervalOptions.sort((a, b) => a - b)
    },
    syncTaskInterval(state) {
      const interval = Number(state.intervalSeconds)
      if (!Number.isInteger(interval) || interval < 1 || interval > 60) return
      this.yoloIntervalSeconds = interval
      this.ensureIntervalOption(interval)
      if (this.engine === 'yolo') this.intervalSeconds = interval
    },
    changeInterval(interval) {
      if (this.engine === 'yolo') this.yoloIntervalSeconds = interval
      else this.visionIntervalSeconds = Math.max(this.minRequestIntervalSeconds, Number(interval))
    },
    changeEngine() {
      this.intervalSeconds = this.engine === 'yolo' ? this.yoloIntervalSeconds : Math.max(this.minRequestIntervalSeconds, this.visionIntervalSeconds)
      this.ensureIntervalOption(this.intervalSeconds)
      this.resetResultState()
    },
    scheduleNextAnalysis(delayMs) {
      if (!this.running || this._isBeingDestroyed) return
      if (this.analysisTimer) clearTimeout(this.analysisTimer)
      this.analysisTimer = setTimeout(async () => {
        this.analysisTimer = null
        await this.analyzeOnce(true)
        if (this.running) this.scheduleNextAnalysis(this.intervalSeconds * 1000)
      }, delayMs)
    },
    async analyzeOnce(silent = false) {
      if (this.analyzing || !this.streamInfo) return
      const player = this.$refs.player
      if (!player || !player.captureFrame) {
        if (!silent) this.$message.error('播放器尚未准备好抽帧')
        return
      }
      const generation = this.requestGeneration
      this.analyzing = true
      try {
        const rawFrame = await player.captureFrame(0.78)
        const frame = await this.normalizeFrame(rawFrame)
        if (generation !== this.requestGeneration) return
        const response = await analyzeVideoFrame({
          engine: this.engine,
          channelId: this.selectedChannelId,
          channelName: this.selectedChannelName,
          imageData: frame.dataUrl,
          imageWidth: frame.width,
          imageHeight: frame.height,
          capturedAt: new Date().toISOString()
        })
        if (generation !== this.requestGeneration || !response || !response.data) return
        if (!silent) this.clearHistorySelection()
        this.updateLiveResult(frame, response.data)
        await this.loadHistory(1, generation, response.data.analysisId)
      } catch (error) {
        if (generation !== this.requestGeneration) return
        this.clearLiveResult()
        const message = this.errorMessage(error)
        if (!silent || this.running) this.$message.error('AI 识别失败：' + message)
        if (this.running && /未配置|未启用|401|403/.test(message)) this.stopAnalysis(false, false)
      } finally {
        this.analyzing = false
      }
    },
    normalizeFrame(frame) {
      return new Promise((resolve, reject) => {
        if (!frame || !frame.dataUrl) {
          reject(new Error('播放器没有返回有效视频帧'))
          return
        }
        const image = new Image()
        image.onload = () => {
          const sourceWidth = frame.width || image.naturalWidth
          const sourceHeight = frame.height || image.naturalHeight
          if (!sourceWidth || !sourceHeight) {
            reject(new Error('无法读取视频帧尺寸'))
            return
          }
          const maxSide = 1280
          const scale = Math.min(1, maxSide / Math.max(sourceWidth, sourceHeight))
          const width = Math.max(16, Math.round(sourceWidth * scale))
          const height = Math.max(16, Math.round(sourceHeight * scale))
          const canvas = document.createElement('canvas')
          canvas.width = width
          canvas.height = height
          const context = canvas.getContext('2d')
          context.drawImage(image, 0, 0, width, height)
          const dataUrl = canvas.toDataURL('image/jpeg', 0.78)
          if (!dataUrl || dataUrl.length < 128) {
            reject(new Error('视频抽帧数据为空'))
            return
          }
          resolve({ dataUrl, width, height })
        }
        image.onerror = () => reject(new Error('视频帧编码失败'))
        image.src = frame.dataUrl
      })
    },
    async loadHistory(page = 1, generation = this.requestGeneration, preferredAnalysisId = '', selectFirst = true) {
      if (!this.selectedChannelId) return
      const historyRequestId = ++this.historyRequestId
      this.historyLoading = true
      const nextHistory = []
      try {
        const response = await getAiHistory(this.selectedChannelId, page)
        if (generation !== this.requestGeneration || historyRequestId !== this.historyRequestId || !response || !response.data) return
        const data = response.data
        const records = Array.isArray(data.list) ? data.list : []
        const loadedHistory = await Promise.all(records.map(async record => {
          let imageObjectUrl = ''
          try {
            const imageResponse = await getAiHistoryImage(record.analysisId)
            const imageBlob = imageResponse && imageResponse.data instanceof Blob ? imageResponse.data : imageResponse
            if (imageBlob instanceof Blob) {
              imageObjectUrl = URL.createObjectURL(imageBlob)
            }
          } catch (error) {
            imageObjectUrl = ''
          }
          return Object.assign({}, record, { imageObjectUrl })
        }))
        nextHistory.push(...loadedHistory)
        if (generation !== this.requestGeneration || historyRequestId !== this.historyRequestId) {
          this.revokeHistoryUrls(nextHistory)
          return
        }
        this.revokeHistoryUrls()
        this.history = nextHistory
        this.historyPage = Number(data.page) || page
        this.historyPageSize = Number(data.pageSize) || 10
        this.historyTotal = Number(data.total) || 0
        const selected = nextHistory.find(item => item.analysisId === preferredAnalysisId) || nextHistory[0]
        if (!selectFirst || this.selectedHistoryAnalysisId || (this.running && this.engine === 'yolo')) return
        if (selected) {
          this.selectHistory(selected, false)
        } else {
          this.clearLiveResult()
        }
      } catch (error) {
        if (generation === this.requestGeneration && historyRequestId === this.historyRequestId) {
          this.$message.error('历史识别记录加载失败：' + this.errorMessage(error))
        }
      } finally {
        if (generation === this.requestGeneration && historyRequestId === this.historyRequestId) this.historyLoading = false
      }
    },
    handleHistoryPageChange(page) {
      this.loadHistory(page)
    },
    handlePlayTimeChange(value) {
      const milliseconds = Number(value)
      this.currentVideoTimeMs = Number.isFinite(milliseconds) && milliseconds >= 0 ? milliseconds : null
    },
    updateLiveResult(frame, result) {
      this.latestLiveFrame = frame
      this.latestLiveResult = result
      if (!this.selectedHistoryAnalysisId) {
        this.latestFrame = frame
        this.latestResult = result
      }
    },
    clearLiveResult() {
      this.updateLiveResult(null, null)
    },
    clearHistorySelection(retainedImageUrl = '') {
      const previousUrl = this.pinnedHistoryImageUrl
      this.selectedHistoryAnalysisId = ''
      this.pinnedHistoryImageUrl = ''
      if (previousUrl && previousUrl !== retainedImageUrl && previousUrl.indexOf('blob:') === 0 &&
          !this.history.some(item => item.imageObjectUrl === previousUrl)) {
        URL.revokeObjectURL(previousUrl)
      }
    },
    returnToLive() {
      this.clearHistorySelection()
      this.latestFrame = this.latestLiveFrame
      this.latestResult = this.latestLiveResult
    },
    resetResultState() {
      this.clearHistorySelection()
      this.clearLiveResult()
    },
    selectHistory(item, pin = true) {
      if (!item || !item.analysisId) return
      const imageUrl = item.imageObjectUrl || item.imageUrl
      this.clearHistorySelection(imageUrl)
      if (pin !== false) {
        this.selectedHistoryAnalysisId = item.analysisId
        this.pinnedHistoryImageUrl = imageUrl || ''
      }
      this.latestFrame = { dataUrl: imageUrl }
      this.latestResult = item
    },
    revokeHistoryUrls(items = this.history) {
      for (const item of items || []) {
        if (item && item.imageObjectUrl && item.imageObjectUrl !== this.pinnedHistoryImageUrl && item.imageObjectUrl.indexOf('blob:') === 0) {
          URL.revokeObjectURL(item.imageObjectUrl)
        }
      }
    },
    historyRowClass({ row }) {
      return row.analysisId === this.selectedHistoryAnalysisId ? 'selected-history-row' : ''
    },
    detectionBoxClass(box) {
      if (box.kind === 'object') return 'object-box'
      if (box.kind === 'unknown') return 'unknown-box'
      return box.status === '自动告警' ? 'alert-box' : 'review-box'
    },
    boxStyle(box, result) {
      const width = result.imageWidth || 1
      const height = result.imageHeight || 1
      return {
        left: (box.x / width * 100) + '%',
        top: (box.y / height * 100) + '%',
        width: (box.width / width * 100) + '%',
        height: (box.height / height * 100) + '%'
      }
    },
    statusTagType(status) {
      if (status === '自动告警') return 'danger'
      if (status === '人工复核') return 'warning'
      return 'success'
    },
    formatConfidence(value) {
      const confidence = Number(value)
      return Number.isFinite(confidence) ? Math.round(confidence * 100) + '%' : '--'
    },
    formatTime(value) {
      if (!value) return ''
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) return value
      const pad = number => String(number).padStart(2, '0')
      return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' +
        pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
    },
    errorMessage(error) {
      if (!error) return '未知错误'
      if (typeof error === 'string') return error
      if (error.response && error.response.data && error.response.data.msg) return error.response.data.msg
      return error.message || String(error)
    }
  }
}
</script>

<style scoped>
.ai-analysis-page {
  position: relative;
  display: flex;
  height: calc(100vh - 84px);
  min-height: 620px;
  color: var(--ui-foreground);
  background: var(--ui-background);
  font-family: "Geist", "Noto Sans SC", sans-serif;
  font-size: 0.875rem;
  font-weight: 400;
  line-height: 1.375rem;
  font-synthesis: none;
  overflow: hidden;
}

.channel-panel {
  width: 340px;
  min-width: 280px;
  background: var(--ui-card);
  border-right: 1px solid var(--ui-border);
  overflow: hidden;
}

.analysis-workspace {
  flex: 1;
  min-width: 0;
  padding: 16px;
  display: grid;
  grid-template-columns: minmax(480px, 1.45fr) minmax(360px, 1fr);
  grid-template-rows: 480px auto;
  gap: 16px;
  overflow-y: auto;
  align-content: start;
}

.panel-card {
  color: var(--ui-foreground);
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-card);
  box-shadow: none;
  overflow: hidden;
}

.video-section,
.result-section {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.section-toolbar,
.result-header,
.history-title {
  min-height: 56px;
  padding: 16px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--ui-border);
  gap: 12px;
}

.channel-title {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.channel-title strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 1rem;
  font-weight: 600;
  line-height: 1.5rem;
}

.channel-id {
  color: var(--ui-muted-foreground);
  font-size: 0.75rem;
  line-height: 1.125rem;
  font-variant-numeric: tabular-nums;
}

.status-dot,
.pulse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ui-muted-foreground);
  flex: none;
}

.status-dot.online,
.pulse-dot {
  background: var(--ui-success);
}

.pulse-dot {
  animation: pulse 1.5s infinite;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.toolbar-actions >>> .el-button,
.result-header-actions >>> .el-button {
  min-height: 2rem;
  margin-left: 0;
  padding: 4px 8px;
  border-radius: var(--ui-radius-control);
  font-size: 0.875rem;
  font-weight: 500;
  line-height: 1.25rem;
  box-shadow: none;
}

.toolbar-actions >>> .el-button > span {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  vertical-align: middle;
}

.toolbar-actions >>> .el-button > span > .ui-icon + span {
  margin-inline-start: 0;
}

.toolbar-actions >>> .el-button .ui-icon,
.empty-video > .ui-icon,
.empty-result > .ui-icon,
.history-empty > .ui-icon {
  flex: none;
}

.toolbar-actions >>> .el-input__inner {
  height: 2rem;
  padding-left: 8px;
  border-radius: var(--ui-radius-control);
  font-size: 0.875rem;
  font-weight: 400;
  line-height: 1.25rem;
}

.toolbar-actions >>> .el-input__icon {
  line-height: 2rem;
}

.toolbar-actions >>> .el-button:focus-visible,
.result-header-actions >>> .el-button:focus-visible,
.history-table-wrap >>> .el-button:focus-visible,
.history-pagination >>> button:focus-visible,
.history-pagination >>> .el-pager li:focus-visible,
.toolbar-actions >>> .el-input__inner:focus-visible {
  outline: 2px solid var(--ui-ring);
  outline-offset: 2px;
}

.engine-select {
  width: 170px;
}

.player-select {
  width: 110px;
}

.interval-select {
  width: 100px;
}

.engine-note {
  padding: 8px 12px;
  border-top: 1px solid var(--ui-border);
  color: var(--ui-muted-foreground);
  background: var(--ui-muted);
  font-size: 0.75rem;
  line-height: 1.125rem;
}

.task-error {
  display: block;
  margin-top: 4px;
  color: var(--ui-warning);
}

.detection-count {
  margin: 8px 0 12px;
  color: var(--ui-muted-foreground);
  font-size: 0.75rem;
  line-height: 1.125rem;
}

.detection-tag {
  margin: 0 6px 8px 0;
}

.analysis-workspace >>> .el-tag {
  display: inline-flex;
  align-items: center;
  box-sizing: border-box;
  min-height: 22px;
  height: auto;
  padding: 0 6px;
  color: var(--ui-foreground);
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-badge);
  font-size: 0.75rem;
  font-weight: 500;
  line-height: 1.125rem;
}

.analysis-workspace >>> .el-tag--info {
  color: var(--ui-info);
  border-color: var(--ui-info);
}

.analysis-workspace >>> .el-tag--success {
  color: var(--ui-success);
  border-color: var(--ui-success);
}

.analysis-workspace >>> .el-tag--warning {
  color: var(--ui-warning);
  border-color: var(--ui-warning);
}

.analysis-workspace >>> .el-tag--danger {
  color: var(--ui-destructive);
  border-color: var(--ui-destructive);
}

.analysis-workspace >>> .el-tag--dark {
  color: var(--ui-primary-foreground);
  background: var(--ui-primary);
  border-color: var(--ui-primary);
}

.analysis-workspace >>> .el-tag--dark.el-tag--info {
  color: var(--ui-info-foreground);
  background: var(--ui-info);
  border-color: var(--ui-info);
}

.analysis-workspace >>> .el-tag--dark.el-tag--success {
  color: var(--ui-success-foreground);
  background: var(--ui-success);
  border-color: var(--ui-success);
}

.analysis-workspace >>> .el-tag--dark.el-tag--warning {
  color: var(--ui-warning-foreground);
  background: var(--ui-warning);
  border-color: var(--ui-warning);
}

.analysis-workspace >>> .el-tag--dark.el-tag--danger {
  color: var(--ui-destructive-foreground);
  background: var(--ui-destructive);
  border-color: var(--ui-destructive);
}

/* The player surface and detection overlays retain their media-specific contrast. */
.video-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  background: #05080d;
}

.empty-video,
.empty-result {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  padding: 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  text-align: center;
}

.empty-video {
  color: #a3a3a3;
}

.empty-result {
  color: var(--ui-muted-foreground);
}

.empty-video p,
.empty-result p {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  line-height: 1.5rem;
}

.empty-video p {
  color: #e5e5e5;
}

.empty-result p {
  color: var(--ui-foreground);
}

.empty-result > span {
  font-size: 0.875rem;
  line-height: 1.375rem;
}

.analysis-running-badge {
  position: absolute;
  left: 16px;
  top: 16px;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 22px;
  padding: 4px 8px;
  border-radius: var(--ui-radius-badge);
  color: #fff;
  background: rgba(10, 18, 28, 0.78);
  font-size: 0.75rem;
  font-weight: 500;
  line-height: 1.125rem;
}

.result-header h3,
.history-title h3,
.record-description h4 {
  margin: 0;
  color: var(--ui-foreground);
  font-size: 1rem;
  font-weight: 600;
  line-height: 1.5rem;
}

.result-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.result-header p,
.history-title > span {
  margin: 4px 0 0;
  color: var(--ui-muted-foreground);
  font-size: 0.75rem;
  line-height: 1.125rem;
  font-variant-numeric: tabular-nums;
}

.result-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}

.annotated-frame {
  position: relative;
  width: 100%;
  line-height: 0;
  background: #111;
  overflow: hidden;
}

.annotated-frame img {
  width: 100%;
  height: auto;
  display: block;
}

.risk-box {
  position: absolute;
  box-sizing: border-box;
  border: 2px solid;
  min-width: 3px;
  min-height: 3px;
}

.object-box { border-color: #1677ff; }
.object-box span { background: #1677ff; }
.unknown-box { border-color: #909399; }
.unknown-box span { background: #909399; }

.risk-box span {
  position: absolute;
  left: -2px;
  top: -24px;
  max-width: 230px;
  padding: 2px 5px;
  color: #fff;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.alert-box {
  border-color: #f5222d;
}

.alert-box span {
  background: #f5222d;
}

.review-box {
  border-color: #faad14;
}

.review-box span {
  background: #d48806;
}

.record-description {
  padding: 16px 0 0;
}

.record-meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px;
}

.record-meta-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
  padding: 8px 12px;
  border-radius: var(--ui-radius-control);
  border: 1px solid var(--ui-border);
  background: var(--ui-muted);
  line-height: 1.375rem;
}

.record-meta-item span {
  flex: none;
  margin-right: 12px;
  color: var(--ui-muted-foreground);
  font-size: 0.75rem;
  line-height: 1.125rem;
}

.record-meta-item strong {
  min-width: 0;
  color: var(--ui-foreground);
  text-align: right;
  word-break: break-word;
  font-size: 0.875rem;
  font-weight: 500;
  line-height: 1.375rem;
  font-variant-numeric: tabular-nums;
}

.result-guidance {
  margin-top: 16px;
}

.result-guidance > p {
  margin: 0;
  color: var(--ui-foreground);
  line-height: 1.375rem;
}

.tips-list {
  margin: 12px 0 0;
  padding: 12px 12px 12px 32px;
  color: var(--ui-foreground);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border);
  border-left: 2px solid var(--ui-warning);
  border-radius: var(--ui-radius-popover);
  line-height: 1.375rem;
}

.tips-list li + li {
  margin-top: 8px;
}

.history-section {
  grid-column: 1 / span 2;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: visible;
}

.history-title {
  min-height: 48px;
}

.history-table-wrap {
  padding: 16px 20px;
}

.history-table-wrap >>> .el-table {
  color: var(--ui-foreground);
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-popover);
  font-size: 0.875rem;
  line-height: 1.375rem;
}

.history-table-wrap >>> .el-table th,
.history-table-wrap >>> .el-table td {
  padding: 6px 0;
  border-bottom-color: var(--ui-border);
}

.history-table-wrap >>> .el-table th {
  color: var(--ui-muted-foreground);
  background: var(--ui-muted);
  font-weight: 500;
}

.history-table-wrap >>> .el-table td {
  background: var(--ui-card);
}

.history-table-wrap >>> .el-table .cell {
  min-height: 24px;
  padding: 0 12px;
  line-height: 1.375rem;
}

.history-table-wrap >>> .el-table th .cell {
  line-height: 1.25rem;
}

.history-table-wrap >>> .el-table__row {
  cursor: pointer;
}

.history-table-wrap >>> .el-table--striped .el-table__row--striped > td,
.history-table-wrap >>> .el-table__body tr:hover > td,
.history-table-wrap >>> .el-table__body tr.hover-row > td,
.history-table-wrap >>> .selected-history-row > td {
  background: var(--ui-muted) !important;
}

.history-table-wrap >>> .selected-history-row > td:first-child {
  box-shadow: inset 2px 0 0 var(--ui-primary);
}

.history-table-wrap >>> .el-table::before,
.history-table-wrap >>> .el-table__fixed-right::before {
  background: var(--ui-border);
}

.history-table-wrap >>> .el-table__fixed-right {
  background: var(--ui-card);
}

.history-table-wrap >>> .el-button--text {
  min-height: 2rem;
  padding: 4px 0;
  color: var(--ui-foreground);
  font-size: 0.875rem;
  font-weight: 500;
  line-height: 1.25rem;
}

.history-table-wrap >>> .el-button--text:hover {
  text-decoration: underline;
  text-underline-offset: 4px;
}

.history-thumb {
  width: 82px;
  height: 44px;
  display: block;
  border-radius: var(--ui-radius-badge);
  object-fit: cover;
  background: #111;
}

.history-image-placeholder {
  width: 82px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--ui-radius-badge);
  background: var(--ui-muted);
  color: var(--ui-muted-foreground);
  font-size: 0.75rem;
  line-height: 1.125rem;
}

.history-table-wrap >>> .el-table__empty-text {
  width: 100%;
  color: var(--ui-muted-foreground);
  line-height: 1.375rem;
}

.history-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 32px;
}

.history-empty > span {
  color: var(--ui-foreground);
  font-size: 1rem;
  font-weight: 600;
  line-height: 1.5rem;
}

.history-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 12px 20px 20px;
  border-top: 1px solid var(--ui-border);
}

.history-pagination >>> .el-pagination {
  color: var(--ui-foreground);
  font-size: 0.875rem;
  font-weight: 400;
}

.history-pagination >>> .el-pagination button,
.history-pagination >>> .el-pager li {
  box-sizing: border-box;
  min-width: 2rem;
  height: 2rem;
  border-radius: var(--ui-radius-control);
  color: var(--ui-foreground);
  background: var(--ui-muted);
  font-size: 0.875rem;
  font-weight: 500;
  line-height: 2rem;
}

.history-pagination >>> .el-pager li:not(.disabled).active {
  color: var(--ui-primary-foreground);
  background: var(--ui-primary);
}

.history-pagination >>> .el-pagination__total {
  color: var(--ui-muted-foreground);
  font-size: 0.75rem;
  line-height: 2rem;
}

.config-alert {
  position: absolute;
  z-index: 60;
  left: 356px;
  right: 16px;
  bottom: 16px;
  color: var(--ui-foreground);
  background: var(--ui-card);
  border: 1px solid var(--ui-warning);
  border-radius: var(--ui-radius-popover);
  padding: 12px 16px;
  box-shadow: none;
}

.config-alert >>> .el-alert__icon {
  color: var(--ui-warning);
}

.config-alert >>> .el-alert__title {
  color: var(--ui-foreground);
  font-size: 0.875rem;
  font-weight: 500;
  line-height: 1.25rem;
}

.config-alert >>> .el-alert__description {
  margin: 4px 0 0;
  color: var(--ui-muted-foreground);
  font-size: 0.875rem;
  line-height: 1.375rem;
}

@keyframes pulse {
  0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--ui-success) 50%, transparent); }
  70% { box-shadow: 0 0 0 8px transparent; }
  100% { box-shadow: 0 0 0 0 transparent; }
}

@media (prefers-reduced-motion: reduce) {
  .pulse-dot {
    animation: none;
  }
}

@media (pointer: coarse) {
  .toolbar-actions >>> .el-button,
  .result-header-actions >>> .el-button,
  .history-table-wrap >>> .el-button--text {
    min-height: 2.75rem;
    padding-inline: 12px;
  }

  .toolbar-actions >>> .el-input__inner {
    height: 2.75rem;
    font-size: 1rem;
    line-height: 1.5rem;
  }

  .toolbar-actions >>> .el-input__icon {
    line-height: 2.75rem;
  }

  .history-pagination >>> .el-pagination button,
  .history-pagination >>> .el-pager li {
    min-width: 2.75rem;
    height: 2.75rem;
    line-height: 2.75rem;
  }

  .history-pagination >>> .el-pagination__total {
    line-height: 2.75rem;
  }
}

@media (max-width: 1180px) {
  .channel-panel {
    width: 290px;
  }

  .analysis-workspace {
    grid-template-columns: 1fr;
    grid-template-rows: 430px auto auto;
    overflow-y: auto;
  }

  .section-toolbar,
  .result-header,
  .history-title {
    padding: 16px;
  }

  .result-body {
    padding: 16px;
  }

  .history-section {
    grid-column: 1;
  }

  .history-table-wrap {
    padding: 16px;
  }

  .history-pagination {
    padding: 12px 16px 16px;
  }

  .config-alert {
    left: 306px;
  }
}
</style>
