import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import App from './App.vue'

describe('App', () => {
  it('renders the active route view', () => {
    const wrapper = mount(App, {
      global: { stubs: { RouterView: { template: '<p>미니게임 놀이터</p>' } } },
    })
    expect(wrapper.text()).toContain('미니게임 놀이터')
  })
})
