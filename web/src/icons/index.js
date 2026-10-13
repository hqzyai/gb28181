import Vue from 'vue'
import UiIcon from '@/components/UiIcon'
import SvgIcon from '@/components/SvgIcon'// svg component

// register globally
Vue.component('ui-icon', UiIcon)
Vue.component('svg-icon', SvgIcon)

const req = require.context('./svg', false, /\.svg$/)
const requireAll = requireContext => requireContext.keys().map(requireContext)
requireAll(req)
