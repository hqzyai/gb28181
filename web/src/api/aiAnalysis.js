import request from '@/utils/request'

export function getAiAnalysisConfig() {
  return request({
    method: 'get',
    url: '/api/ai/config',
    timeout: 15000
  })
}

export function analyzeVideoFrame(data) {
  return request({
    method: 'post',
    url: '/api/ai/analyze-frame',
    data,
    timeout: 210000,
    maxContentLength: 5 * 1024 * 1024,
    maxBodyLength: 5 * 1024 * 1024
  })
}

export function getAiHistory(channelId, page) {
  return request({
    method: 'get',
    url: '/api/ai/history',
    params: { channelId, page },
    timeout: 15000
  })
}

export function getAiHistoryImage(analysisId) {
  return request({
    method: 'get',
    url: '/api/ai/history/' + encodeURIComponent(analysisId) + '/image',
    responseType: 'blob',
    timeout: 30000
  })
}
