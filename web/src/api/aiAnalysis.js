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
