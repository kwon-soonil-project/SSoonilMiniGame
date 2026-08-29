<script setup lang="ts">
import type { LiarFinalScoreEntry, LiarRoundResult } from '../gameTypes'

defineProps<{
  result?: LiarRoundResult
  final: boolean
  disabled: boolean
  isHost: boolean
  liarName?: string
  answer?: string
  finalScores?: LiarFinalScoreEntry[]
}>()
const emit = defineEmits<{ returnToWaiting: [] }>()
</script>

<template>
  <section class="panel" aria-labelledby="result-title">
    <p class="eyebrow">{{ final ? 'GAME RESULT' : 'ROUND RESULT' }}</p>
    <h2 id="result-title">{{ final ? '최종 결과' : '라운드 결과' }}</h2>
    <p v-if="result">{{ result.invalidated ? '이번 라운드는 무효입니다.' : result.liarGuessedCorrectly ? '라이어가 제시어를 맞혀 역전했습니다.' : result.winner === 'LIAR' ? '라이어가 살아남아 승리했습니다.' : '시민이 승리했습니다.' }}</p>
    <dl v-if="liarName || answer" class="round-secrets">
      <div v-if="liarName"><dt>라이어:</dt><dd>{{ ` ${liarName}` }}</dd></div>
      <div v-if="answer"><dt>제시어:</dt><dd>{{ ` ${answer}` }}</dd></div>
    </dl>
    <ol v-if="final" data-region="final-ranking" class="final-ranking">
      <li v-for="entry in finalScores ?? []" :key="entry.actorId">
        <strong>{{ entry.rank }}위</strong> {{ entry.nickname }} <span>{{ entry.score }}점</span>
      </li>
    </ol>
    <button v-if="final && isHost" type="button" aria-label="대기방으로 돌아가기" :disabled="disabled" @click="emit('returnToWaiting')">대기방으로 돌아가기</button>
  </section>
</template>

<style scoped>
.panel { border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.1rem; }.round-secrets { display: grid; gap: .35rem; }.round-secrets div { display: flex; gap: .35rem; }.round-secrets dt { font-weight: 800; }.round-secrets dd { margin: 0; }.final-ranking { display: grid; gap: .4rem; padding: 0; list-style: none; }.final-ranking li { display: flex; gap: .35rem; }.final-ranking span { margin-left: auto; }button { border: 0; border-radius: .7rem; background: #5b47d6; color: white; cursor: pointer; font-weight: 850; padding: .7rem; }button:disabled { cursor: not-allowed; opacity: .55; }
</style>
