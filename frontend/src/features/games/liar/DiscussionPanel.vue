<script setup lang="ts">
defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ propose: []; approve: [] }>()
</script>

<template>
  <section class="panel" aria-labelledby="discussion-title"><p class="eyebrow">DISCUSSION</p><h2 id="discussion-title">토론 시간</h2><p>힌트를 바탕으로 라이어를 찾아보세요.</p><div><button type="button" aria-label="토론 종료 제안" :disabled="disabled" @click="emit('propose')">토론 종료 제안</button><button type="button" aria-label="토론 종료 찬성" :disabled="disabled" @click="emit('approve')">토론 종료 찬성</button></div></section>
</template>

<style scoped>
.panel { border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.1rem; }div { display: flex; flex-wrap: wrap; gap: .55rem; }button { border: 0; border-radius: .7rem; background: #e9e5ff; color: #513cc6; cursor: pointer; font-weight: 850; padding: .7rem; }button:disabled { cursor: not-allowed; opacity: .55; }
</style>
