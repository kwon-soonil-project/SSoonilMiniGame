import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import App from './App.vue'

describe('App', () => {
  it('renders the service name', () => {
    expect(mount(App).text()).toContain('미니게임 놀이터')
  })
})
