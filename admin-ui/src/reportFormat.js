// Формат на числата и датите в отчетите

// null (полето не се отнася, напр. токени при embedding) – „—“
export function count(value) {
  return value == null ? '—' : value.toLocaleString('bg-BG')
}

// 2026-07-01 → 01.07.2026; друго – както е
export function date(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || '')
  return match ? `${match[3]}.${match[2]}.${match[1]}` : value || '—'
}
