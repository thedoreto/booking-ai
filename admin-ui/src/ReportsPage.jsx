import { useEffect, useState } from 'react'
import {
  Alert, Box, Card, CardContent, CircularProgress, LinearProgress, Link, Table, TableBody, TableCell, TableHead,
  TableRow, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material'
import { getToken, reports } from './api.js'

const PERIODS = [7, 30, 90, 180]

// Отчетите на хотела от логовете на чата за последните 7/30/90/180 дни: обобщение, пътят до резервация,
// търсения без свободни стаи и натиснатите бутони. Сумите са без валута – валутата още не идва от хотела.
export default function ReportsPage({ onOpenShortcut, onUnauthorized }) {
  const [days, setDays] = useState(30)
  // Отчетът за days; друг days – още се зарежда
  const [report, setReport] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let current = true
    reports(getToken(), days)
      .then((result) => current && setReport(result))
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
  }, [days, onUnauthorized])

  function changePeriod(value) {
    if (value) {
      setError(null)
      setDays(value)
    }
  }

  const loading = !error && report?.days !== days
  return (
    <Box>
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
          <Summary summary={report.summary} />
          <Funnel funnel={report.funnel} />
          <NoRooms searches={report.noRooms} />
          <Buttons buttons={report.buttons} onOpenShortcut={onOpenShortcut} />
        </Box>
      )}
    </Box>
  )
}

function Summary({ summary }) {
  const guestShare = summary.steps > 0 ? Math.round((summary.guestSteps / summary.steps) * 100) : 0
  const cards = [
    { label: 'Въпроси в чата', value: count(summary.questions) },
    { label: 'Натиснати бутони', value: count(summary.buttons) },
    {
      label: 'Резервации през чата',
      value: count(summary.bookings),
      note: `${count(summary.bookedRooms)} ${summary.bookedRooms === 1 ? 'стая' : 'стаи'} · сума ${money(summary.bookedTotal)}`,
    },
    { label: 'Откази през чата', value: count(summary.cancellations), note: `сума ${money(summary.canceledTotal)}` },
    { label: 'Влезли потребители', value: count(summary.users), note: `${guestShare}% от действията са от гости` },
  ]
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: 2, mb: 3 }}>
      {cards.map((card) => (
        <Card key={card.label}>
          <CardContent>
            <Typography variant="body2" color="text.secondary">{card.label}</Typography>
            <Typography variant="h4" component="div">{card.value}</Typography>
            {card.note && <Typography variant="caption" color="text.secondary">{card.note}</Typography>}
          </CardContent>
        </Card>
      ))}
    </Box>
  )
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

function Section({ title, hint, children }) {
  return (
    <Card sx={{ mb: 3 }}>
      <CardContent>
        <Typography variant="h6">{title}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{hint}</Typography>
        {children}
      </CardContent>
    </Card>
  )
}

function count(value) {
  return value.toLocaleString('bg-BG')
}

function money(value) {
  return value.toLocaleString('bg-BG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 2026-07-01 → 01.07.2026; друго – както е
function date(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || '')
  return match ? `${match[3]}.${match[2]}.${match[1]}` : value || '—'
}
