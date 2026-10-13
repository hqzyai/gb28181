import icons from '@/assets/lucide/icons.json'
import sources from '@/assets/lucide/sources.json'

export default {
  name: 'UiIcon',
  functional: true,
  props: { name: { type: String, required: true }, size: { type: [Number, String], default: 16 } },
  render(h, context) {
    const key = context.props.name
    const mapped = sources.legacySvgMapping[key] || sources.elementMapping[key.replace(/^el-icon-/, '')] || key
    const name = mapped.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
    const nodes = icons[name] || icons['circle-help']
    const data = context.data
    return h('svg', {
      ...data,
      class: ['ui-icon', data.class],
      attrs: { ...data.attrs, xmlns: 'http://www.w3.org/2000/svg', width: context.props.size, height: context.props.size,
        viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 1.75,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': (data.attrs || {})['aria-label'] ? 'false' : 'true', focusable: 'false' }
    }, nodes.map(([tag, attrs]) => h(tag, { attrs: { ...attrs, 'vector-effect': 'non-scaling-stroke' } })))
  }
}
