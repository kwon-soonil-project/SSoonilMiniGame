<script setup lang="ts">
import type { LobbyRoom } from './lobbyStore'

defineProps<{ room: LobbyRoom }>()

const gameLabels = {
  LIAR: '라이어 게임',
  DRAWING: '그림 퀴즈',
  CHOSUNG: '초성 퀴즈',
  MAJORITY: '다수결 예측',
} as const
</script>

<template>
  <article class="room-card" :class="{ playing: room.status === 'PLAYING' }">
    <div class="card-topline">
      <span class="game-badge">{{ gameLabels[room.gameType] }}</span>
      <span class="status" :class="room.status.toLowerCase()">
        <span aria-hidden="true">●</span> {{ room.status === 'WAITING' ? '대기 중' : '진행 중' }}
      </span>
    </div>
    <h2>{{ room.title }}</h2>
    <dl>
      <div><dt>방장</dt><dd>{{ room.hostNickname }}</dd></div>
      <div><dt>인원</dt><dd>{{ room.participantCount }} / {{ room.maxParticipants }}명</dd></div>
      <div><dt>입장</dt><dd>{{ room.passwordProtected ? '🔒 비밀번호 필요' : '🔓 바로 입장' }}</dd></div>
    </dl>
    <p v-if="room.status === 'PLAYING'" class="spectator-note">관전한 뒤 다음 라운드부터 참여해요.</p>
    <a class="join-link" :href="`/rooms/${room.code}`">
      {{ room.status === 'WAITING' ? '방 입장하기' : '관전하기' }}
      <span aria-hidden="true">→</span>
    </a>
  </article>
</template>

<style scoped>
.room-card { display: flex; flex-direction: column; min-width: 0; border: 1px solid #e7e6ef; border-radius: 1.1rem; background: white; box-shadow: 0 8px 24px rgb(35 31 66 / 6%); padding: 1.25rem; }
.room-card.playing { background: #fbfbfd; }
.card-topline { display: flex; align-items: center; justify-content: space-between; gap: .6rem; }
.game-badge { overflow: hidden; color: #5b47d6; font-size: .75rem; font-weight: 800; text-overflow: ellipsis; white-space: nowrap; }
.status { font-size: .76rem; font-weight: 800; white-space: nowrap; }
.status.waiting { color: #16834b; }
.status.playing { color: #a15c00; }
h2 { margin: .9rem 0 1.1rem; font-size: 1.15rem; letter-spacing: -.02em; }
dl { display: grid; gap: .55rem; margin: 0; color: #5d6171; font-size: .86rem; }
dl div { display: flex; justify-content: space-between; gap: .75rem; }
dt { color: #858898; }
dd { margin: 0; font-weight: 650; text-align: right; }
.spectator-note { color: #8a5a10; font-size: .8rem; line-height: 1.45; }
.join-link { display: flex; justify-content: space-between; margin-top: auto; padding-top: 1.1rem; color: #4f3ac7; font-size: .88rem; font-weight: 800; text-decoration: none; }
.join-link:focus-visible { outline: 3px solid #ad9fff; outline-offset: 4px; }
@media (max-width: 767px) { .room-card { display: grid; grid-template-columns: 1fr auto; gap: .4rem 1rem; padding: 1rem; } .card-topline, h2, dl, .spectator-note { grid-column: 1; } h2 { margin: .35rem 0; } dl { grid-template-columns: repeat(2, max-content); gap: .25rem 1rem; } dl div { gap: .35rem; } dl div:last-child { display: none; } .join-link { grid-column: 2; grid-row: 1 / span 3; align-self: center; padding: .7rem; background: #f0edff; border-radius: .75rem; } .join-link span { display: none; } }
</style>
