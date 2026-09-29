import { useEffect, useMemo, useState } from 'react'
import {
  Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Link, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material'
import {
  analyzeSuggestions, getToken, knowledge, settings, setSuggestionStatus, shortcutTools, shortcuts, suggestions,
} from './api.js'
import KnowledgeEditor from './KnowledgeEditor.jsx'
import ShortcutEditor from './ShortcutEditor.jsx'
import { knowledgeTitle } from './knowledgeTitle.js'
import { count, dateTime } from './reportFormat.js'

const PERIODS = [7, 30, 90, 180]
const COOLDOWN_MS = 60 * 60 * 1000

const ERRORS = {
  NO_QUESTIONS: 'В периода няма въпроси в чата – няма какво да се анализира.',
  ANALYSIS_RUNNING: 'Анализът вече върви (от друг раздел или админ). Презаредете след малко.',
  ANALYSIS_FAILED: 'Gemini не отговори (понякога е претоварен) – нищо не е записано. Опитайте отново след 5 минути.',
  INVALID_PERIOD: 'Невалиден период.',
  NETWORK: 'Няма връзка със сървъра.',
}

// Таб „Предложения“: анализ на въпросите от чата с Gemini (само по бутон, веднъж на час) → липсващи знания и нови бутони.
// „Създай“ отваря на място формата от „Знания“/„Бутони“, попълнена с предложението; след запис предложението е
// „създадено“. Нищо не влиза в знанията или бутоните без админа.
export default function SuggestionsPage({ onOpenShortcut, onUnauthorized }) {
  const [analysis, setAnalysis] = useState(undefined)
  const [documents, setDocuments] = useState([])
  const [buttons, setButtons] = useState([])
  const [hotel, setHotel] = useState(null)
  const [tools, setTools] = useState([])
  const [error, setError] = useState(null)
  const [days, setDays] = useState(30)
  const [analyzing, setAnalyzing] = useState(false)
  const [analyzeError, setAnalyzeError] = useState(null)
  const [showHandled, setShowHandled] = useState(false)

  useEffect(() => {
    const token = getToken()
    Promise.all([suggestions(token), knowledge(token), shortcuts(token), settings(token), shortcutTools(token)])
      .then(([latest, docs, buttonList, hotelSettings, toolList]) => {
        setDocuments(docs)
        setButtons(buttonList)
        setHotel(hotelSettings)
        setTools(toolList)
        setAnalysis(latest)
      })
      .catch((e) => {
        if (e.code === 'UNAUTHORIZED') {
          onUnauthorized()
        } else {
          setError('Предложенията не могат да се заредят. Опитайте отново.')
        }
      })
  }, [onUnauthorized])

  async function analyze() {
    setAnalyzeError(null)
    setAnalyzing(true)
    try {
      setAnalysis(await analyzeSuggestions(getToken(), days))
      setShowHandled(false)
    } catch (e) {
      if (e.code === 'UNAUTHORIZED') {
        onUnauthorized()
      } else if (e.code === 'TOO_SOON') {
        setAnalyzeError(`Анализът може да се пусне отново след ${dateTime(e.body.retryAt)}.`)
      } else {
        setAnalyzeError(ERRORS[e.code] || ERRORS.NETWORK)
      }
    } finally {
      setAnalyzing(false)
    }
  }

  // Предложението е създадено или отхвърлено – веднага в страницата, после в booking-ai
  async function markItem(item, status) {
    setAnalysis((current) => ({
      ...current,
      items: current.items.map((i) => (i.id === item.id ? { ...i, status } : i)),
    }))
    try {
      await setSuggestionStatus(getToken(), analysis.id, item.id, status)
    } catch (e) {
      if (e.code === 'UNAUTHORIZED') {
        onUnauthorized()
      }
      // Иначе остава само в страницата – следващият анализ може да го предложи пак
    }
  }

  const documentsById = useMemo(() => new Map(documents.map((doc) => [doc.id, doc])), [documents])
  const knowledgeCategories = useMemo(() => distinct(documents.map((doc) => doc.category)), [documents])
  const allTags = useMemo(() => distinct(documents.flatMap((doc) => doc.tags || [])), [documents])
  const buttonCategories = useMemo(() => distinct(buttons.map((b) => b.category)), [buttons])

  if (error) {
    return <Alert severity="error">{error}</Alert>
  }
  if (analysis === undefined) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 6 }}>
        <CircularProgress />
      </Box>
    )
  }

  const items = analysis?.items || []
  const open = items.filter((item) => item.status === 'new')
  const handled = items.filter((item) => item.status !== 'new')
  const nextAt = analysis ? new Date(new Date(analysis.createdAt).getTime() + COOLDOWN_MS) : null
  const cardProps = {
    documentsById, knowledgeCategories, allTags, buttonCategories, hotel, tools, documents, onUnauthorized, onOpenShortcut,
    onKnowledgeCreated: (item, created) => {
      setDocuments((docs) => [...docs, created])
      markItem(item, 'accepted')
    },
    onButtonCreated: (item, created) => {
      setButtons((current) => [...current, created])
      markItem(item, 'accepted')
    },
    onDismiss: (item) => markItem(item, 'dismissed'),
  }

  return (
    <Box>
      <Card sx={{ mb: 3 }}>
        <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Typography variant="body2" color="text.secondary">
            Gemini чете въпросите на гостите за периода и предлага знания, които липсват, и бутони за честите въпроси.
            Анализът е едно извикване към Gemini и може да се пуска веднъж на час.
          </Typography>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
            <ToggleButtonGroup exclusive size="small" value={days} onChange={(e, value) => value && setDays(value)}
              disabled={analyzing}>
              {PERIODS.map((period) => (
                <ToggleButton key={period} value={period}>{period} дни</ToggleButton>
              ))}
            </ToggleButtonGroup>
            <Button variant="contained" onClick={analyze} disabled={analyzing}>
              {analyzing ? 'Gemini анализира…' : `Анализирай последните ${days} дни`}
            </Button>
            {analyzing && <CircularProgress size={20} />}
          </Box>
          {analysis && (
            <Typography variant="body2" color="text.secondary">
              Последен анализ: {dateTime(analysis.createdAt)} · {analysis.days} дни · {count(analysis.questions)} различни
              въпроса · {count((analysis.inputTokens ?? 0) + (analysis.outputTokens ?? 0))} токена ·
              следващият – след {dateTime(nextAt)}
            </Typography>
          )}
          {analyzeError && <Alert severity={analyzeError.startsWith('Анализът може') ? 'info' : 'error'}>{analyzeError}</Alert>}
        </CardContent>
      </Card>

      {!analysis && <Typography color="text.secondary">Още няма анализ. Натиснете „Анализирай“.</Typography>}
      {analysis && items.length === 0 && (
        <Typography color="text.secondary">Gemini няма предложения за този период.</Typography>
      )}
      {analysis && items.length > 0 && open.length === 0 && (
        <Typography color="text.secondary" sx={{ mb: 2 }}>Всички предложения от този анализ са разгледани.</Typography>
      )}
      {open.map((item) => <SuggestionCard key={item.id} item={item} {...cardProps} />)}

      {handled.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <Button size="small" onClick={() => setShowHandled((value) => !value)}>
            {showHandled ? 'Скрий разгледаните' : `Покажи разгледаните (${handled.length})`}
          </Button>
          {showHandled && handled.map((item) => <SuggestionCard key={item.id} item={item} {...cardProps} />)}
        </Box>
      )}
    </Box>
  )
}

function SuggestionCard({
  item, documentsById, knowledgeCategories, allTags, buttonCategories, hotel, tools, documents, onUnauthorized,
  onOpenShortcut, onKnowledgeCreated, onButtonCreated, onDismiss,
}) {
  const [creating, setCreating] = useState(false)
  const [createdButton, setCreatedButton] = useState(null)
  const isKnowledge = item.type === 'missing_knowledge'
  const isNew = item.status === 'new'

  return (
    <Card sx={{ mb: 2, opacity: isNew ? 1 : 0.6 }}>
      <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          <Chip size="small" color={isKnowledge ? 'warning' : 'primary'} label={isKnowledge ? 'Липсващо знание' : 'Нов бутон'} />
          <Typography variant="subtitle1" sx={{ fontWeight: 500 }}>{isKnowledge ? item.topic : item.label}</Typography>
          {item.count != null && (
            <Typography variant="body2" color="text.secondary">
              {count(item.count)} {item.count === 1 ? 'въпрос' : 'въпроса'}
            </Typography>
          )}
          {item.status === 'accepted' && <Chip size="small" variant="outlined" color="success" label="създадено" />}
          {item.status === 'dismissed' && <Chip size="small" variant="outlined" label="отхвърлено" />}
        </Box>

        {item.questions?.length > 0 && (
          <Box component="ul" sx={{ m: 0, pl: 3 }}>
            {item.questions.map((question) => (
              <Typography component="li" variant="body2" key={question}>{question}</Typography>
            ))}
          </Box>
        )}

        {isKnowledge && !creating && (
          <Box sx={{ bgcolor: 'grey.100', p: 1.5, borderRadius: 1 }}>
            <Typography variant="caption" color="text.secondary">
              Чернова от Gemini – празните места [..] са факти, които той не знае:
            </Typography>
            <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{item.draft}</Typography>
          </Box>
        )}
        {!isKnowledge && (
          <Typography variant="body2">
            Знание: {item.knowledgeIds.map((id) => (documentsById.has(id) ? knowledgeTitle(documentsById.get(id)) : '(изтрито знание)')).join(', ')}
          </Typography>
        )}

        {creating && isKnowledge && (
          <KnowledgeEditor
            initial={{ title: item.topic, text: item.draft }}
            categories={knowledgeCategories}
            allTags={allTags}
            onSaved={(created) => {
              setCreating(false)
              onKnowledgeCreated(item, created)
            }}
            onCancel={() => setCreating(false)}
            onUnauthorized={onUnauthorized}
          />
        )}
        {creating && !isKnowledge && (
          <ShortcutEditor
            initial={{
              label: { [hotel.defaultLanguage]: item.label },
              action: { type: 'knowledge', knowledgeIds: item.knowledgeIds.filter((id) => documentsById.has(id)) },
            }}
            languages={hotel.languages}
            defaultLanguage={hotel.defaultLanguage}
            categories={buttonCategories}
            documents={documents}
            tools={tools}
            onSaved={(created) => {
              setCreating(false)
              setCreatedButton(created.shortcutId)
              onButtonCreated(item, created)
            }}
            onCancel={() => setCreating(false)}
            onUnauthorized={onUnauthorized}
          />
        )}

        {createdButton && (
          <Typography variant="body2">
            Бутонът е създаден и е последен в чата – <Link component="button" variant="body2"
              onClick={() => onOpenShortcut(createdButton)}>отвори го в „Бутони“</Link>, за да го преместиш.
          </Typography>
        )}

        {isNew && !creating && (
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button variant="contained" size="small" onClick={() => setCreating(true)}>
              {isKnowledge ? 'Създай знание' : 'Създай бутон'}
            </Button>
            <Button size="small" onClick={() => onDismiss(item)}>Отхвърли</Button>
          </Box>
        )}
      </CardContent>
    </Card>
  )
}

function distinct(values) {
  return [...new Set(values.filter(Boolean))].sort((a, b) => a.localeCompare(b, 'bg'))
}
