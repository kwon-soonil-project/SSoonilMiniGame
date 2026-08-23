import { describe, expect, it, vi } from 'vitest'
import { EventSequencer } from './eventSequencer'

describe('EventSequencer', () => {
  it('reloads the snapshot when a room event sequence is skipped', async () => {
    const reload = vi.fn().mockResolvedValue({ sequence: 8, participants: [] })
    const apply = vi.fn()
    const sequencer = new EventSequencer(6, reload)

    await sequencer.accept({ sequence: 8, type: 'PLAYER_JOINED', payload: {} }, apply)

    expect(reload).toHaveBeenCalledOnce()
    expect(apply).not.toHaveBeenCalled()
    expect(sequencer.current).toBe(8)
  })

  it('applies only the next event and ignores duplicate or stale delivery', async () => {
    const reload = vi.fn()
    const apply = vi.fn()
    const sequencer = new EventSequencer(3, reload)

    await sequencer.accept({ sequence: 4, type: 'PLAYER_READY_CHANGED', payload: {} }, apply)
    await sequencer.accept({ sequence: 4, type: 'PLAYER_READY_CHANGED', payload: {} }, apply)
    await sequencer.accept({ sequence: 2, type: 'PLAYER_JOINED', payload: {} }, apply)

    expect(apply).toHaveBeenCalledOnce()
    expect(reload).not.toHaveBeenCalled()
    expect(sequencer.current).toBe(4)
  })
})
