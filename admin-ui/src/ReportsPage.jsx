import { useEffect, useState } from 'react'
import {
  Alert, Box, CircularProgress, LinearProgress, Link, Tab, Table, TableBody, TableCell, TableHead,
  TableRow, Tabs, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material'
import { geminiReport, getToken, reports } from './api.js'
import GeminiReport from './GeminiReport.jsx'
import { count, date } from './reportFormat.js'
import { Cards, Section } from './reportParts.jsx'

const PERIODS = [7, 30, 90, 180]
const LOADERS = { chat: reports, gemini: geminiReport }

// Отчетите на хотела за последните 7/30/90/180 дни, в два под-таба с общ период:
// „Чат“ – от логовете на чата: обобщение, пътят до резервация, търсения без свободни стаи и натиснатите бутони
// (сумите са без валута – валутата още не идва от хотела); „Gemini“ – заявките и токените (GeminiReport.jsx).
export default function ReportsPage({ onOpenShortcut, onUnauthorized }) {
  const [view, setView] = useState('chat')
  const [days, setDays] = useState(30)
  // Последният зареден отчет: { view, days, data }; друг view или days – още се зарежда
  const [loaded, setLoaded] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let current = true
    LOADERS[view](getToken(), days)
      .then((data) => current && setLoaded({ view, days, data }))
      .catch((e) => {
        if (!current) {
          return
        }
        if (e.code === 'UNAUTHORIZED') {
          onUnauthorized()
        } else {
          setError('Отчетите не могат да се заредят. Опитайте отново.')
        }
      })
    return () => {
      current = false
    }
  }, [view, days, onUnauthorized])

  function changePeriod(value) {
    if (value) {
      setError(null)
      setDays(value)
    }
  }

  function changeView(value) {
    setError(null)
    setView(value)
  }

  // При смяна на периода старият отчет остава блед, докато се зареди новият; при смяна на под-таба – няма какво да се покаже
  const report = loaded?.view === view ? loaded.data : null
  const loading = !error && (report === null || loaded.days !== days)
  return (
    <Box>
      <Tabs value={view} onChange={(e, value) => changeView(value)} sx={{ mb: 2 }}>
        <Tab value="chat" label="Чат" />
        <Tab value="gemini" label="Gemini" />
      </Tabs>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3, flexWrap: 'wrap' }}>
        <Typography variant="body2" color="text.secondary">Период:</Typography>
        <ToggleButtonGroup exclusive size="small" value={days} onChange={(e, value) => changePeriod(value)}
          sx={{ bgcolor: 'background.paper' }}>
          {PERIODS.map((period) => (
            <ToggleButton key={period} value={period}>{period} дни</ToggleButton>
          ))}
        </ToggleButtonGroup>
        {loading && report && <CircularProgress size={20} />}
      </Box>

      {error && <Alert severity="error">{error}</Alert>}
      {!error && !report && (
        <Box sx={{ display: 'flex', justifyContent: 'center', mt: 6 }}>
          <CircularProgress />
        </Box>
      )}
      {!error && report && (
        <Box sx={{ opacity: loading ? 0.5 : 1 }}>
          {view === 'chat' ? (
            <>
              <Summary summary={report.summary} />
              <Funnel funnel={report.funnel} />
              <NoRooms searches={report.noRooms} />
              <Buttons buttons={report.buttons} onOpenShortcut={onOpenShortcut} />
            </>
          ) : (
            <GeminiReport report={report} />
          )}
        </Box>
      )}
    </Box>
  )
}

function Summary({ summary }) {
  const guestShare = summary.steps > 0 ? Math.round((summary.guestSteps / summary.steps) * 100) : 0
  const cards = [
    { label: 'Въпроси в чата', value: count(summary.questions), note: `без отговор в знанията: ${count(summary.unanswered)}` },
    { label: 'Натиснати бутони', value: count(summary.buttons) },
    {
      label: 'Резервации през чата',
      value: count(summary.bookings),
      note: `${count(summary.bookedRooms)} ${summary.bookedRooms === 1 ? 'стая' : 'стаи'} · сума ${money(summary.bookedTotal)}`,
    },
    { label: 'Откази през чата', value: count(summary.cancellations), note: `сума ${money(summary.canceledTotal)}` },
    { label: 'Влезли потребители', value: count(summary.users), note: `${guestShare}% от действията са от гости` },
  ]
  return <Cards cards={cards} />
}

function Funnel({ funnel }) {
  const rows = [
    { label: 'Започнати', value: funnel.started, note: `от чата ${count(funnel.fromChat)} · от бутон ${count(funnel.fromButton)}` },
    { label: 'Търсили с дати', value: funnel.searched },
    { label: 'Видели свободни стаи', value: funnel.roomsShown },
    { label: 'Резервирали', value: funnel.booked },
  ]
  return (
    <Section title="Пътят до резервация" hint="Новите резервации, започнати в периода – от отварянето на календара до резервацията.">
      {funnel.started === 0 ? (
        <Typography color="text.secondary">Няма започнати резервации.</Typography>
      ) : (
        <>
          {rows.map((row) => {
            const percent = Math.round((row.value / funnel.started) * 100)
            return (
              <Box key={row.label} sx={{ mb: 1.5 }}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 2 }}>
                  <Typography variant="body2">
                    {row.label}
                    {row.note && <Typography component="span" variant="caption" color="text.secondary"> ({row.note})</Typography>}
                  </Typography>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>{count(row.value)} · {percent}%</Typography>
                </Box>
                <LinearProgress variant="determinate" value={percent} sx={{ height: 8, borderRadius: 1 }} />
              </Box>
            )
          })}
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
            Само без свободни стаи: {count(funnel.onlyNoRooms)} · Резервацията не е минала: {count(funnel.bookingFailed)}
          </Typography>
        </>
      )}
    </Section>
  )
}

function NoRooms({ searches }) {
  return (
    <Section title="Търсения без свободни стаи" hint="Дати и типове стаи, за които гостите са търсили, но не е имало свободни.">
      {searches.length === 0 ? (
        <Typography color="text.secondary">Няма такива търсения.</Typography>
      ) : (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>От</TableCell>
              <TableCell>До</TableCell>
              <TableCell>Тип стая</TableCell>
              <TableCell align="right">Търсения</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {searches.map((search) => (
              <TableRow key={`${search.startDate}|${search.endDate}|${search.roomType}`}>
                <TableCell>{date(search.startDate)}</TableCell>
                <TableCell>{date(search.endDate)}</TableCell>
                <TableCell>{search.roomType || 'всички'}</TableCell>
                <TableCell align="right">{count(search.count)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Section>
  )
}

function Buttons({ buttons, onOpenShortcut }) {
  return (
    <Section title="Бутони" hint="Колко пъти е натиснат всеки бутон; „без резултат“ – бутонът не е върнал информация.">
      {buttons.length === 0 ? (
        <Typography color="text.secondary">Няма натиснати бутони.</Typography>
      ) : (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Бутон</TableCell>
              <TableCell align="right">Натискания</TableCell>
              <TableCell align="right">Без резултат</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {buttons.map((button) => (
              <TableRow key={button.shortcutId ?? '—'}>
                <TableCell>
                  {button.shortcutId ? (
                    <Link component="button" variant="body2" onClick={() => onOpenShortcut(button.shortcutId)}>
                      {button.label || button.shortcutId}
                    </Link>
                  ) : '—'}
                </TableCell>
                <TableCell align="right">{count(button.count)}</TableCell>
                <TableCell align="right">{count(button.noResult)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Section>
  )
}

function money(value) {
  return value.toLocaleString('bg-BG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
