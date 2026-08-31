<script setup lang="ts">
import type { LiarPrivateState } from '../gameTypes'

defineProps<{ privateState: LiarPrivateState | null }>()
</script>

<template>
  <section class="role-panel" aria-labelledby="role-title">
    <p class="eyebrow">MY ROLE</p><h2 id="role-title">내 역할: {{ privateState?.role === 'LIAR' ? '라이어' : privateState?.role === 'CITIZEN' ? '시민' : '관전자' }}</h2>
    <p v-if="privateState">카테고리: <strong>{{ privateState.category }}</strong></p>
    <p v-if="privateState?.role === 'CITIZEN' && privateState.word">제시어: <strong>{{ privateState.word }}</strong></p>
    <p v-else-if="privateState?.role === 'LIAR'">다른 참가자의 힌트로 제시어를 추리하세요.</p>
  </section>
</template>

<style scoped>
.role-panel { border-radius: 1rem; background: #2b2843; color: white; padding: 1.35rem; }.eyebrow { margin: 0 0 .3rem; color: #c8c0ff; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.35rem; }.role-panel p:last-child { margin-bottom: 0; color: #ddd9ef; }
</style>
