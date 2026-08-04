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
            <el-select v-model="playerType" size="mini" class="player-select" :disabled="running">
              <el-option label="Jessibuca" value="jessibuca" />
              <el-option label="WebRTC" value="webRTC" />
              <el-option label="H265web" value="h265web" />
            </el-select>
            <el-select v-model="intervalSeconds" size="mini" class="interval-select" :disabled="running">
              <el-option v-for="seconds in intervalOptions" :key="seconds" :label="seconds + ' 秒/帧'" :value="seconds" />
            </el-select>
            <el-button
              v-if="!running"
              type="primary"
              size="mini"
              icon="el-icon-video-play"
              :disabled="!streamInfo || !aiConfigured"
              @click="startAnalysis"
            >开始识别</el-button>
            <el-button
              v-else
              type="danger"
              size="mini"
              icon="el-icon-video-pause"
              @click="stopAnalysis"
            >停止识别</el-button>
            <el-button
              size="mini"
              icon="el-icon-camera"
              :loading="analyzing"
              :disabled="!streamInfo || !aiConfigured || running"
              @click="analyzeOnce"
            >识别一帧</el-button>
          </div>
        </div>

        <div class="video-stage" v-loading="streamLoading" element-loading-text="正在拉取 GB28181 视频流">
          <div v-if="!streamInfo" class="empty-video">
            <i class="el-icon-video-camera" />
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
            {{ analyzing ? '正在分析当前视频帧' : 'AI 持续识别中' }}
          </div>
        </div>
      </section>

      <section class="result-section panel-card">
        <div class="result-header">
          <div>
            <h3>识别结果</h3>
            <p v-if="latestResult">{{ formatTime(latestResult.analyzedAt) }} · 耗时 {{ latestResult.elapsedMs }} ms</p>
            <p v-else>识别结果会在这里实时更新</p>
          </div>
          <el-tag v-if="latestResult" :type="statusTagType(latestResult.status)" effect="dark">
            {{ latestResult.status }}
          </el-tag>
        </div>

        <div v-if="latestFrame && latestResult" class="result-body">
          <div class="annotated-frame">
            <img :src="latestFrame.dataUrl" alt="AI识别视频帧">
            <div
              v-for="(box, index) in latestResult.detections"
              :key="index"
              class="risk-box"
              :class="box.status === '自动告警' ? 'alert-box' : 'review-box'"
              :style="boxStyle(box, latestResult)"
            >
              <span>{{ box.type }} {{ Math.round(box.confidence * 100) }}%</span>
            </div>
          </div>

          <div class="record-description">
            <h4>历史检测记录</h4>
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
              <div class="record-meta-item">
                <span>视频时间：</span>
                <strong>{{ formatVideoTime(latestResult.videoTimeSeconds) }}</strong>
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
          <i class="el-icon-cpu" />
          <p>选择通道并点击“开始识别”</p>
          <span>系统会按设定间隔从正在播放的 GB28181 视频中抽帧分析</span>
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
            <el-table-column prop="riskName" label="风险类型" min-width="190" show-overflow-tooltip />
            <el-table-column label="处理状态" width="110">
              <template slot-scope="scope">
                <el-tag size="mini" :type="statusTagType(scope.row.status)">{{ scope.row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="置信度" width="90">
              <template slot-scope="scope">{{ formatConfidence(scope.row.confidence) }}</template>
            </el-table-column>
            <el-table-column label="视频时间" width="100">
              <template slot-scope="scope">{{ formatVideoTime(scope.row.videoTimeSeconds) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="72" fixed="right">
              <template slot-scope="scope">
                <el-button type="text" size="mini" @click.stop="selectHistory(scope.row)">查看</el-button>
              </template>
            </el-table-column>
            <template slot="empty">
              <span>{{ selectedChannelId ? '当前摄像头暂无历史识别记录' : '请先选择摄像头' }}</span>
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
      description="请在服务器环境变量中配置 AI_ANALYSIS_ENABLED、AI_ANALYSIS_API_URL、AI_ANALYSIS_API_KEY 和 AI_ANALYSIS_MODEL。"
    />
  </div>
</template>

<script>
import DeviceTree from '@/views/common/DeviceTree.vue'
import PlayerTabs from '@/views/common/playerTabs.vue'
import { analyzeVideoFrame, getAiAnalysisConfig, getAiHistory, getAiHistoryImage } from '@/api/aiAnalysis'

export default {
  name: 'AiAnalysis',
  components: { DeviceTree, PlayerTabs },
  data() {
    return {
      configLoaded: false,
      aiConfigured: false,
      selectedChannelId: '',
      selectedChannelName: '',
      streamInfo: null,
      streamLoading: false,
      streamError: '',
      playerType: 'jessibuca',
      intervalSeconds: 10,
      intervalOptions: [5, 10, 15, 30, 60],
      running: false,
      analyzing: false,
      analysisTimer: null,
      requestGeneration: 0,
      historyRequestId: 0,
      currentVideoTimeMs: null,
      latestFrame: null,
      latestResult: null,
      history: [],
      historyLoading: false,
      historyPage: 1,
      historyPageSize: 10,
      historyTotal: 0
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
    this.stopAnalysis(false)
    this.requestGeneration += 1
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
        if (data.defaultIntervalSeconds) {
          const interval = Number(data.defaultIntervalSeconds)
          if (!this.intervalOptions.includes(interval)) this.intervalOptions.push(interval)
          this.intervalOptions.sort((a, b) => a - b)
          this.intervalSeconds = interval
        }
      } catch (error) {
        this.aiConfigured = false
      } finally {
        this.configLoaded = true
      }
    },
    async handleChannelClick(channelId, channel) {
      this.stopAnalysis(false)
      this.requestGeneration += 1
      this.selectedChannelId = String(channelId || '')
      this.selectedChannelName = channel && (channel.name || channel.gbName)
        ? (channel.name || channel.gbName)
        : this.selectedChannelId
      this.streamInfo = null
      this.streamError = ''
      this.streamLoading = true
      this.currentVideoTimeMs = null
      this.latestFrame = null
      this.latestResult = null
      this.revokeHistoryUrls()
      this.history = []
      this.historyPage = 1
      this.historyTotal = 0
      const generation = this.requestGeneration
      this.loadHistory(1, generation)
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
    startAnalysis() {
      if (!this.streamInfo || !this.aiConfigured || this.running) return
      this.running = true
      this.scheduleNextAnalysis(0)
    },
    stopAnalysis(showMessage = true) {
      this.running = false
      if (this.analysisTimer) {
        clearTimeout(this.analysisTimer)
        this.analysisTimer = null
      }
      if (showMessage) this.$message.info('已停止持续识别')
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
          channelId: this.selectedChannelId,
          channelName: this.selectedChannelName,
          imageData: frame.dataUrl,
          imageWidth: frame.width,
          imageHeight: frame.height,
          capturedAt: new Date().toISOString(),
          videoTimeSeconds: this.currentVideoTimeMs === null
            ? null
            : Math.round(this.currentVideoTimeMs) / 1000
        })
        if (generation !== this.requestGeneration || !response || !response.data) return
        this.latestFrame = frame
        this.latestResult = response.data
        await this.loadHistory(1, generation, response.data.analysisId)
      } catch (error) {
        const message = this.errorMessage(error)
        if (!silent || this.running) this.$message.error('AI 识别失败：' + message)
        if (this.running && /未配置|未启用|401|403/.test(message)) this.stopAnalysis(false)
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
    async loadHistory(page = 1, generation = this.requestGeneration, preferredAnalysisId = '') {
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
        if (selected) {
          this.selectHistory(selected)
        } else {
          this.latestFrame = null
          this.latestResult = null
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
    selectHistory(item) {
      if (!item) return
      this.latestFrame = { dataUrl: item.imageObjectUrl || item.imageUrl }
      this.latestResult = item
    },
    revokeHistoryUrls(items = this.history) {
      for (const item of items || []) {
        if (item && item.imageObjectUrl && item.imageObjectUrl.indexOf('blob:') === 0) {
          URL.revokeObjectURL(item.imageObjectUrl)
        }
      }
    },
    historyRowClass({ row }) {
      return this.latestResult && row.analysisId === this.latestResult.analysisId ? 'selected-history-row' : ''
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
    formatVideoTime(value) {
      if (value === null || value === undefined || value === '') return '--'
      const seconds = Number(value)
      return Number.isFinite(seconds) && seconds >= 0 ? seconds.toFixed(2) + 's' : '--'
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
  background: #f3f6fa;
  overflow: hidden;
}

.channel-panel {
  width: 340px;
  min-width: 280px;
  background: #fff;
  border-right: 1px solid #e5e9f0;
  overflow: hidden;
}

.analysis-workspace {
  flex: 1;
  min-width: 0;
  padding: 14px;
  display: grid;
  grid-template-columns: minmax(480px, 1.45fr) minmax(360px, 1fr);
  grid-template-rows: 480px auto;
  gap: 14px;
  overflow-y: auto;
  align-content: start;
}

.panel-card {
  background: #fff;
  border: 1px solid #e5e9f0;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(31, 45, 61, 0.05);
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
  min-height: 54px;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #edf0f5;
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
}

.channel-id {
  color: #909399;
  font-size: 12px;
}

.status-dot,
.pulse-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #c0c4cc;
  flex: none;
}

.status-dot.online,
.pulse-dot {
  background: #24c66b;
}

.pulse-dot {
  box-shadow: 0 0 0 rgba(36, 198, 107, 0.5);
  animation: pulse 1.5s infinite;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.player-select {
  width: 110px;
}

.interval-select {
  width: 100px;
}

.video-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  background: #05080d;
}

.empty-video,
.empty-result {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
  text-align: center;
}

.empty-video i,
.empty-result i {
  font-size: 48px;
  margin-bottom: 12px;
}

.empty-video p,
.empty-result p {
  margin: 0 0 7px;
  color: #c0c4cc;
}

.empty-result p {
  color: #606266;
}

.empty-result span {
  font-size: 12px;
}

.analysis-running-badge {
  position: absolute;
  left: 14px;
  top: 14px;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 11px;
  border-radius: 16px;
  color: #fff;
  background: rgba(10, 18, 28, 0.78);
  font-size: 12px;
}

.result-header h3,
.history-title h3 {
  margin: 0;
  font-size: 16px;
}

.result-header p,
.history-title span {
  margin: 4px 0 0;
  color: #909399;
  font-size: 12px;
}

.result-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
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
  padding: 12px 2px 0;
}

.record-description h4 {
  margin: 0 0 10px;
  color: #303133;
  font-size: 16px;
}

.record-meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  padding: 10px;
  border-radius: 7px;
  background: #090b0e;
}

.record-meta-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
  padding: 9px 11px;
  border-radius: 6px;
  background: #15171a;
  line-height: 1.4;
}

.record-meta-item span {
  flex: none;
  margin-right: 10px;
  color: #aeb4bd;
  font-size: 12px;
}

.record-meta-item strong {
  min-width: 0;
  color: #fff;
  text-align: right;
  word-break: break-word;
  font-weight: 500;
  font-size: 13px;
}

.result-guidance {
  margin-top: 10px;
}

.result-guidance > p {
  margin: 0;
  color: #606266;
  line-height: 1.6;
}

.tips-list {
  margin: 10px 0 0;
  padding: 10px 10px 10px 30px;
  color: #606266;
  background: #fff8e8;
  border-radius: 4px;
  line-height: 1.7;
}

.history-section {
  grid-column: 1 / span 2;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: visible;
}

.history-title {
  min-height: 46px;
  padding-top: 7px;
  padding-bottom: 7px;
}

.history-table-wrap {
  padding: 0 12px;
}

.history-table-wrap >>> .el-table__row {
  cursor: pointer;
}

.history-table-wrap >>> .selected-history-row > td {
  background: #ecf5ff !important;
}

.history-thumb {
  width: 82px;
  height: 44px;
  display: block;
  border-radius: 3px;
  object-fit: cover;
  background: #111;
}

.history-image-placeholder {
  width: 82px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 3px;
  background: #f2f4f7;
  color: #909399;
  font-size: 11px;
}

.history-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 12px 14px 14px;
  border-top: 1px solid #edf0f5;
}

.config-alert {
  position: absolute;
  z-index: 60;
  left: 354px;
  right: 14px;
  bottom: 14px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
}

@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(36, 198, 107, 0.5); }
  70% { box-shadow: 0 0 0 8px rgba(36, 198, 107, 0); }
  100% { box-shadow: 0 0 0 0 rgba(36, 198, 107, 0); }
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

  .history-section {
    grid-column: 1;
  }

  .config-alert {
    left: 304px;
  }
}
</style>
