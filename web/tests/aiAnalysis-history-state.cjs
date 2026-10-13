// Run with Node only. Execute the Vue component's real data/methods with mocked API IO.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vmModule = require('node:vm')
const { test } = require('node:test')

const source = fs.readFileSync(path.join(__dirname, '../src/views/aiAnalysis/index.vue'), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'module.exports =')
const live = id => ({ ready: true, imageData: 'data:image/jpeg;base64,' + id, result: { analysisId: id, detections: [] } })
const history = id => ({ analysisId: id, imageObjectUrl: 'blob:' + id, detections: [] })

function createHarness() {
  const revoked = []
  const context = {
    module: { exports: {} }, DeviceTree: {}, PlayerTabs: {}, Blob, Date,
    URL: { revokeObjectURL: url => revoked.push(url), createObjectURL: () => 'blob:new-image' },
    setTimeout: () => 1, clearTimeout: () => {},
    getAiTask: async () => ({ data: { running: true, intervalSeconds: 2 } }),
    getAiTaskLatest: async () => ({ data: live('live-new') }),
    startAiTask: async () => ({ data: { running: true, intervalSeconds: 2 } }),
    stopAiTask: async () => ({ data: { running: false } }),
    analyzeVideoFrame: async () => ({ data: live('one-frame').result })
  }
  vmModule.runInNewContext(script, context, { filename: 'aiAnalysis/index.vue' })
  const component = context.module.exports
  const state = component.data()
  for (const [name, method] of Object.entries(component.methods)) state[name] = method.bind(state)
  Object.defineProperty(state, 'selectedEngineReady', { get: () => true })
  Object.assign(state, {
    engine: 'yolo', selectedChannelId: '1', streamInfo: {}, lastHistoryRefresh: Date.now(),
    $message: { error: () => {}, info: () => {} },
    $refs: { player: { setStreamInfo: () => {}, captureFrame: async () => ({ dataUrl: 'frame', width: 320, height: 180 }) } },
    $store: { dispatch: async () => ({ url: 'current-camera' }) }, $nextTick: async () => {}
  })
  return { state, context, revoked }
}

test('selected history remains fixed while actual background polling updates task and live cache', async () => {
  const { state } = createHarness()
  state.running = true
  const selected = history('history-one')
  state.history = [selected]
  state.selectHistory(selected)
  await state.pollTask(state.requestGeneration)
  assert.equal(state.latestResult, selected)
  assert.equal(state.latestFrame.dataUrl, 'blob:history-one')
  assert.equal(state.selectedHistoryAnalysisId, 'history-one')
  assert.equal(state.latestLiveResult.analysisId, 'live-new')
  assert.equal(state.taskState.running, true)
  state.returnToLive()
  assert.equal(state.selectedHistoryAnalysisId, '')
  assert.equal(state.latestResult.analysisId, 'live-new')
  assert.equal(state.latestFrame.dataUrl, 'data:image/jpeg;base64,live-new')
  assert.match(source, /@click="returnToLive">返回实时结果/)
})

test('background frame or transport errors clear live cache without clearing a history pin', async () => {
  for (const transport of [false, true]) {
    const { state, context } = createHarness()
    state.running = true
    state.updateLiveResult({ dataUrl: 'old-live-frame' }, live('old-live').result)
    const selected = history('historical')
    state.selectHistory(selected)
    if (transport) context.getAiTask = async () => { throw new Error('network unavailable') }
    else context.getAiTaskLatest = async () => ({ data: { ready: false, error: '本帧未完成识别' } })
    await state.pollTask(state.requestGeneration)
    assert.equal(state.latestResult, selected)
    assert.equal(state.latestFrame.dataUrl, 'blob:historical')
    assert.equal(state.selectedHistoryAnalysisId, 'historical')
    assert.equal(state.latestLiveFrame, null)
    assert.equal(state.latestLiveResult, null)
    state.returnToLive()
    assert.equal(state.latestFrame, null)
    assert.equal(state.latestResult, null)
  }
})

test('history blob survives list refresh and is released when its orphan pin is cleared', () => {
  const { state, revoked } = createHarness()
  const selected = history('pinned-image')
  state.history = [selected, history('other-image')]
  state.selectHistory(selected)
  state.revokeHistoryUrls()
  assert.deepEqual(revoked, ['blob:other-image'])
  state.history = [] // Replaced after an authenticated list/image refresh.
  state.returnToLive()
  assert.deepEqual(revoked, ['blob:other-image', 'blob:pinned-image'])
})

test('channel switch clears pin, display and live cache', async () => {
  const { state } = createHarness()
  state.running = true
  state.updateLiveResult({ dataUrl: 'old-live' }, live('old').result)
  state.selectHistory(history('old-history'))
  state.loadHistory = async () => {}
  state.restoreTask = async () => {}
  await state.handleChannelClick('2', { name: 'New camera' })
  assert.equal(state.selectedChannelId, '2')
  assert.equal(state.selectedHistoryAnalysisId, '')
  assert.equal(state.latestFrame, null)
  assert.equal(state.latestResult, null)
  assert.equal(state.latestLiveFrame, null)
  assert.equal(state.latestLiveResult, null)
})

test('successful start/stop clear pin; failed actions preserve it', async () => {
  for (const action of ['start', 'stop']) {
    for (const failed of [false, true]) {
      const { state, context } = createHarness()
      state.running = action === 'stop'
      state.updateLiveResult({ dataUrl: 'latest-live' }, live('last-live').result)
      const selected = history('pinned')
      state.selectHistory(selected)
      state.pollTask = async () => {}
      if (failed) context[action === 'start' ? 'startAiTask' : 'stopAiTask'] = async () => { throw new Error('action failed') }
      await (action === 'start' ? state.startAnalysis() : state.stopAnalysis(false))
      assert.equal(state.selectedHistoryAnalysisId, failed ? 'pinned' : '')
      if (failed) assert.equal(state.latestResult, selected)
      else if (action === 'start') assert.equal(state.latestResult, null)
      else assert.equal(state.latestResult.analysisId, 'last-live')
    }
  }
})

test('an in-flight poll cannot resume display after stop', async () => {
  const { state, context } = createHarness()
  let resolveStatus, resolveLatest
  state.running = true
  state.updateLiveResult({ dataUrl: 'before-stop' }, live('before-stop').result)
  state.selectHistory(history('pinned'))
  context.getAiTask = () => new Promise(resolve => { resolveStatus = resolve })
  context.getAiTaskLatest = () => new Promise(resolve => { resolveLatest = resolve })
  const pending = state.pollTask(state.requestGeneration)
  await state.stopAnalysis(false)
  resolveStatus({ data: { running: true, intervalSeconds: 2 } })
  resolveLatest({ data: live('stale-poll') })
  await pending
  assert.equal(state.running, false)
  assert.equal(state.selectedHistoryAnalysisId, '')
  assert.equal(state.latestResult.analysisId, 'before-stop')
})

test('one-frame analysis failure clears stale live cache while keeping selected history', async () => {
  const { state, context } = createHarness()
  state.updateLiveResult({ dataUrl: 'old-frame' }, live('old').result)
  const selected = history('review-record')
  state.selectHistory(selected)
  state.normalizeFrame = async frame => frame
  context.analyzeVideoFrame = async () => { throw new Error('inference failed') }
  await state.analyzeOnce()
  assert.equal(state.latestResult, selected)
  assert.equal(state.selectedHistoryAnalysisId, 'review-record')
  assert.equal(state.latestLiveFrame, null)
  assert.equal(state.latestLiveResult, null)
})

test('fatal live-analysis error stops monitoring without dismissing the history being reviewed', async () => {
  const { state, context } = createHarness()
  state.engine = 'vision'
  state.running = true
  state.updateLiveResult({ dataUrl: 'old-frame' }, live('old').result)
  const selected = history('review-record')
  state.selectHistory(selected)
  state.normalizeFrame = async frame => frame
  context.analyzeVideoFrame = async () => { throw new Error('401 未配置') }
  await state.analyzeOnce(true)
  assert.equal(state.running, false)
  assert.equal(state.latestResult, selected)
  assert.equal(state.selectedHistoryAnalysisId, 'review-record')
  assert.equal(state.latestLiveFrame, null)
})

test('old-channel stop response cannot unpin a history selected on the new channel', async () => {
  const { state, context } = createHarness()
  let resolveStop
  state.running = true
  context.stopAiTask = () => new Promise(resolve => { resolveStop = resolve })
  const pending = state.stopAnalysis(false)
  state.loadHistory = async () => {}
  state.restoreTask = async () => {}
  await state.handleChannelClick('2', { name: 'New camera' })
  const selected = history('new-channel-history')
  state.selectHistory(selected)
  resolveStop({ data: { running: false } })
  await pending
  assert.equal(state.selectedChannelId, '2')
  assert.equal(state.selectedHistoryAnalysisId, 'new-channel-history')
  assert.equal(state.latestResult, selected)
})

test('manual one-frame success returns to fresh results while an automatic frame preserves a history pin', async () => {
  for (const silent of [false, true]) {
    const { state } = createHarness()
    const selected = history('review-record')
    state.selectHistory(selected)
    state.normalizeFrame = async frame => frame
    state.loadHistory = async () => {}
    await state.analyzeOnce(silent)
    assert.equal(state.latestLiveResult.analysisId, 'one-frame')
    assert.equal(state.selectedHistoryAnalysisId, silent ? 'review-record' : '')
    assert.equal(state.latestResult, silent ? selected : state.latestLiveResult)
  }
})
