<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{ disabled: boolean; currentHinterName: string }>()
const emit = defineEmits<{ submit: [text: string] }>()
const hint = ref('')

function submit(): void {
  const text = hint.value.trim()
  if (!text || props.disabled) return
  emit('submit', text)
  hint.value = ''
}
</script>

<template>
  <section class="panel" aria-labelledby="hint-title">
    <p class="eyebrow">HINTING</p><h2 id="hint-title">힌트 차례</h2><p>현재 힌트 차례: <strong>{{ currentHinterName }}</strong></p>
    <form @submit.prevent="submit"><label for="liar-hint">힌트</label><textarea id="liar-hint" v-model="hint" maxlength="100" :disabled="disabled" /><button type="submit" aria-label="힌트 제출" :disabled="disabled || !hint.trim()">힌트 제출</button></form>
  </section>
</template>

<style scoped>
.panel { border: 1px solid #e8e7ef; border-radius: 1rem; background: white; padding: 1.15rem; }.eyebrow { margin: 0 0 .25rem; color: #6652d9; font-size: .68rem; font-weight: 900; letter-spacing: .12em; }h2 { margin: 0; font-size: 1.1rem; }form { display: grid; gap: .55rem; margin-top: 1rem; }label { font-size: .78rem; font-weight: 800; }textarea { min-height: 5rem; resize: vertical; }textarea, button { border-radius: .7rem; padding: .7rem; }textarea { border: 1px solid #dcdce6; }button { border: 0; background: #5b47d6; color: white; cursor: pointer; font-weight: 850; }button:disabled, textarea:disabled { cursor: not-allowed; opacity: .55; }
</style>
