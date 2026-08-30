import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RoomSettingsPanel from './RoomSettingsPanel.vue'

describe('RoomSettingsPanel', () => {
  it('emits only the room-settings command fields when it receives a full room snapshot', async () => {
    const wrapper = mount(RoomSettingsPanel, {
      props: {
        settings: {
          gameType: 'LIAR',
          maxParticipants: 10,
          rounds: 3,
          actionSeconds: 30,
          discussionSeconds: 90,
          categoryPack: 'all',
          roomId: 'room-1',
          game: null,
        } as never,
        editable: true,
      },
    })

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('save')).toEqual([[{
      gameType: 'LIAR',
      maxParticipants: 10,
      rounds: 3,
      actionSeconds: 30,
      discussionSeconds: 90,
      categoryPack: 'all',
    }]])
  })
})
