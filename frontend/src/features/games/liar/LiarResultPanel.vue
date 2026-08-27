<script setup lang="ts">
import type { LiarRoundResult } from '../gameTypes'

defineProps<{ result?: LiarRoundResult; final: boolean; disabled: boolean }>()
const emit = defineEmits<{ returnToWaiting: [] }>()
</script>

<template>
  <section class="panel" aria-labelledby="result-title"><p class="eyebrow">{{ final ? 'GAME RESULT' : 'ROUND RESULT' }}</p><h2 id="result-title">{{ final ? '최종 결과' : '라운드 결과' }}</h2><p v-if="result">{{ result.invalidated ? '이번 라운드는 무효입니다.' : result.winner === 'LIAR' ? '라이어가 승리했습니다.' : '시민이 승리했습니다.' }}</p><button v-if="final" type="button" aria-label="대기방으로 돌아가기" :disabled="disabled" @click="emit('returnToWaiting')">대기방으로 돌아가기</button></section>
</template>

<style scoped>
.panel { border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.1rem; }button { border: 0; border-radius: .7rem; background: #5b47d6; color: white; cursor: pointer; font-weight: 850; padding: .7rem; }button:disabled { cursor: not-allowed; opacity: .55; }
</style>
