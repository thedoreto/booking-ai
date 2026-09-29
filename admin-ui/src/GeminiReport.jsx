import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { count, date } from './reportFormat.js'
import { Cards, Section } from './reportParts.jsx'

// Кой вика Gemini – имената на източниците от gemini_usage_<hotelId>
const SOURCES = {
  chat: 'Въпроси в чата',
  chat_embedding: 'Търсене в знанията (чат)',
  admin_embedding: 'Знания – добавяне и редакция (админ)',
  admin_translation: 'Преводи (админ)',
}

// Отчетът „Gemini“: заявки и токени за периода, по източник и по дни, колко от действията в чата минават без Gemini
// и колко пъти хотелът е стигнал лимита си. Без цена – Gemini връща токените, не цената.
export default function GeminiReport({ report }) {
  const { total, bySource, byDay, share } = report
  const tokens = (total.inputTokens ?? 0) + (total.outputTokens ?? 0)
  const withoutGemini = share.steps > 0 ? Math.round(((share.steps - share.withGemini) / share.steps) * 100) : null
  const cards = [
    { label: 'Заявки към Gemini', value: count(total.calls), note: `грешки ${count(total.errors)}` },
    {
      label: 'Токени',
      value: count(tokens),
      note: `вход ${count(total.inputTokens ?? 0)} · изход ${count(total.outputTokens ?? 0)}`,
    },
    {
      label: 'Текст за embedding',
      value: count(total.characters ?? 0),
      note: 'знака – Gemini не връща токени за embedding',
    },
    {
      label: 'Действия в чата без Gemini',
      value: withoutGemini == null ? '—' : `${withoutGemini}%`,
      note: `${count(share.withGemini)} от ${count(share.steps)} действия са викали Gemini`,
    },
  ]
  return (
    <>
      <Cards cards={cards} />
      <Section title="По източник" hint="Кой вика Gemini – гостите в чата или админ панелът.">
        {bySource.length === 0 ? (
          <Typography color="text.secondary">Няма заявки към Gemini за периода.</Typography>
        ) : (
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Източник</TableCell>
                  <TableCell>Модел</TableCell>
                  <TableCell align="right">Заявки</TableCell>
                  <TableCell align="right">Грешки</TableCell>
                  <TableCell align="right">Токени вход</TableCell>
                  <TableCell align="right">Токени изход</TableCell>
                  <TableCell align="right">Знаци</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {bySource.map((row) => (
                  <TableRow key={`${row.key}|${row.model}`}>
                    <TableCell>{SOURCES[row.key] || row.key}</TableCell>
                    <TableCell>{row.model || '—'}</TableCell>
                    <TableCell align="right">{count(row.calls)}</TableCell>
                    <TableCell align="right">{count(row.errors)}</TableCell>
                    <TableCell align="right">{count(row.inputTokens)}</TableCell>
                    <TableCell align="right">{count(row.outputTokens)}</TableCell>
                    <TableCell align="right">{count(row.characters)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </Section>
      <Section title="По дни"
        hint={`Денят е по тихоокеанско време – тогава Gemini нулира дневната квота. Лимит на хотела: ${count(report.limitPerDay)} въпроса на ден и ${count(report.limitPerMinute)} на минута.`}>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Дневният лимит е стигнат: {count(share.limitDay)} пъти · минутният: {count(share.limitMinute)} пъти
        </Typography>
        {byDay.length === 0 ? (
          <Typography color="text.secondary">Няма заявки към Gemini за периода.</Typography>
        ) : (
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Ден</TableCell>
                  <TableCell align="right">Заявки</TableCell>
                  <TableCell align="right">Грешки</TableCell>
                  <TableCell align="right">Токени</TableCell>
                  <TableCell align="right">Знаци</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {byDay.map((day) => (
                  <TableRow key={day.key}>
                    <TableCell>{date(day.key)}</TableCell>
                    <TableCell align="right">{count(day.calls)}</TableCell>
                    <TableCell align="right">{count(day.errors)}</TableCell>
                    <TableCell align="right">
                      {day.inputTokens == null && day.outputTokens == null
                        ? '—' : count((day.inputTokens ?? 0) + (day.outputTokens ?? 0))}
                    </TableCell>
                    <TableCell align="right">{count(day.characters)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </Section>
    </>
  )
}
