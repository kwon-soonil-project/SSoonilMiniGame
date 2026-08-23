import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import HomeView from './HomeView.vue'

describe('HomeView', () => {
  it('offers Google login and an accessible guest dialog', async () => {
    const wrapper = mount(HomeView, { global: { plugins: [createPinia()] } })

    expect(wrapper.get('a[href="/oauth2/authorization/google"]').text()).toContain('Google')
    await wrapper.get('button[data-action="open-guest"]').trigger('click')

    expect(wrapper.get('[role="dialog"]').attributes('aria-labelledby')).toBe('guest-dialog-title')
    expect(wrapper.get('label[for="guest-nickname"]').text()).toContain('닉네임')
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})
