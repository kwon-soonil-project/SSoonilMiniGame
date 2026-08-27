<script setup lang="ts">
import type { GamePrivateState, GamePublicState, LiarAction } from './gameTypes'
import type { RoomParticipant } from '../room/roomStore'
import LiarGameView from './liar/LiarGameView.vue'

const props = defineProps<{
  publicState: GamePublicState
  privateState: GamePrivateState | null
  participants: RoomParticipant[]
  actorId: string
  connected: boolean
}>()
const emit = defineEmits<{ action: [payload: { action: LiarAction; data: Record<string, unknown> }] }>()
</script>

<template>
  <section class="game-shell" data-region="game-shell" aria-label="라이어 게임">
    <div class="game-stage">
      <LiarGameView
        :public-state="props.publicState"
        :private-state="props.privateState"
        :participants="props.participants"
        :actor-id="props.actorId"
        :connected="props.connected"
        @action="emit('action', $event)"
      />
    </div>
    <aside class="game-sidebar" aria-label="게임 정보와 대화">
      <section class="score-panel" aria-labelledby="score-title">
        <p class="eyebrow">SCOREBOARD</p><h2 id="score-title">점수</h2>
        <ol>
          <li v-for="participant in props.participants" :key="participant.actorId">
            <span>{{ participant.nickname }}</span><strong>{{ props.publicState.scores[participant.actorId] ?? 0 }}</strong>
          </li>
        </ol>
      </section>
      <slot name="sidebar" />
    </aside>
  </section>
</template>

<style scoped>
.game-shell { display: grid; grid-template-columns: minmax(0, 1fr) minmax(18rem, .42fr); gap: 1rem; margin-top: 2rem; }.game-stage { min-width: 0; }.game-sidebar { display: grid; align-content: start; gap: 1rem; min-width: 0; }.score-panel { border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.1rem; }ol { display: grid; gap: .45rem; margin: .9rem 0 0; padding: 0; list-style: none; }li { display: flex; justify-content: space-between; gap: .5rem; border-radius: .65rem; background: #f8f8fc; padding: .55rem .65rem; font-size: .8rem; }li strong { color: #5440c9; }
@media (max-width: 899px) { .game-shell { grid-template-columns: 1fr; }.game-sidebar { grid-template-columns: repeat(2, minmax(0, 1fr)); }.score-panel { grid-column: 1 / -1; } }
@media (max-width: 767px) { .game-shell { margin-top: 1.25rem; }.game-sidebar { grid-template-columns: 1fr; }.score-panel { grid-column: auto; }:slotted(.desktop-chat) { display: none; } }
</style>
