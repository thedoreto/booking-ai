// Формат на числата и датите в отчетите

// null (полето не се отнася, напр. токени при embedding) – „—“
export function count(value) {
  return value == null ? '—' : value.toLocaleString('bg-BG')
}

// Оценка на близостта (0..1) – 3 знака
export function score(value) {
  return value == null ? '—' : value.toLocaleString('bg-BG', { minimumFractionDigits: 3, maximumFractionDigits: 3 })
}

// 2026-09-29T08:15:00Z → 29.09, 11:15 (местното време на браузъра)
export function dateTime(value) {
  if (!value) {
    return '—'
  }
  return new Date(value).toLocaleString('bg-BG', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
}

// 2026-07-01 → 01.07.2026; друго – както е
export function date(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || '')
  return match ? `${match[3]}.${match[2]}.${match[1]}` : value || '—'
}
