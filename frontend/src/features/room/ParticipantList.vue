<script setup lang="ts">
import type { RoomParticipant } from './roomStore'

defineProps<{ participants: RoomParticipant[]; hostId: string; scores?: Record<string, number> }>()
</script>

<template>
  <section class="participant-panel" data-region="participants" aria-labelledby="participants-title">
    <header>
      <div><p class="eyebrow">PLAYERS</p><h2 id="participants-title">참가자</h2></div>
      <span>{{ participants.length }}명</span>
    </header>
    <ul>
      <li v-for="participant in participants" :key="participant.actorId">
        <span class="avatar" aria-hidden="true">{{ participant.nickname.slice(0, 1) }}</span>
        <span class="identity">
          <strong>{{ participant.nickname }}</strong>
          <small v-if="participant.actorId === hostId">방장</small>
          <small v-else-if="participant.spectator">관전 중</small>
        </span>
        <span class="ready" :class="{ active: participant.ready }">
          {{ scores ? `${scores[participant.actorId] ?? 0}점` : participant.ready ? '준비 완료' : '준비 전' }}
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.participant-panel { min-width: 0; border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }
header { display: flex; align-items: end; justify-content: space-between; gap: 1rem; }
.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }
h2 { margin: 0; font-size: 1.1rem; } header > span { color: #727586; font-size: .8rem; }
ul { display: grid; gap: .65rem; margin: 1rem 0 0; padding: 0; list-style: none; }
li { display: flex; align-items: center; gap: .65rem; border-radius: .8rem; background: #f8f8fc; padding: .7rem; }
.avatar { display: grid; width: 2rem; height: 2rem; place-items: center; border-radius: 50%; background: #eae6ff; color: #5843cf; font-weight: 900; }
.identity { display: grid; min-width: 0; flex: 1; }.identity strong { overflow: hidden; font-size: .86rem; text-overflow: ellipsis; white-space: nowrap; }.identity small { color: #777a89; font-size: .7rem; }
.ready { color: #8a8c98; font-size: .7rem; font-weight: 800; }.ready.active { color: #16834b; }
</style>
