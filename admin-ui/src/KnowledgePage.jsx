import { useEffect, useMemo, useState } from 'react'
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Chip, CircularProgress, InputAdornment, TextField, Typography,
} from '@mui/material'
import { getToken, knowledge } from './api.js'

const NO_CATEGORY = 'Без категория'

// Знанията на хотела (knowledge_<hotelId>) – само преглед: групирани по категория, търсене по заглавие, текст и етикети
export default function KnowledgePage({ onUnauthorized }) {
  const [documents, setDocuments] = useState(null)
  const [error, setError] = useState(null)
  const [search, setSearch] = useState('')

  useEffect(() => {
    knowledge(getToken())
      .then(setDocuments)
      .catch((e) => {
        if (e.code === 'UNAUTHORIZED') {
          onUnauthorized()
        } else {
          setError('Знанията не могат да се заредят. Опитайте отново.')
        }
      })
  }, [onUnauthorized])

  const groups = useMemo(() => groupByCategory(filter(documents || [], search)), [documents, search])

  if (error) {
    return <Alert severity="error">{error}</Alert>
  }
  if (!documents) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 6 }}>
        <CircularProgress />
      </Box>
    )
  }

  const shown = groups.reduce((sum, [, docs]) => sum + docs.length, 0)
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3, flexWrap: 'wrap' }}>
        <TextField
          size="small"
          placeholder="Търсене"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          sx={{ flexGrow: 1, maxWidth: 400, bgcolor: 'background.paper' }}
          slotProps={{ input: { startAdornment: <InputAdornment position="start">🔍</InputAdornment> } }}
        />
        <Typography variant="body2" color="text.secondary">
          {search ? `${shown} от ${documents.length}` : documents.length} документа
        </Typography>
      </Box>

      {documents.length === 0 && <Alert severity="info">Хотелът още няма знания.</Alert>}
      {documents.length > 0 && shown === 0 && <Alert severity="info">Няма документи за „{search}“.</Alert>}

      {groups.map(([category, docs]) => (
        <Box key={category} sx={{ mb: 3 }}>
          <Typography variant="overline" color="text.secondary">
            {category} ({docs.length})
          </Typography>
          {docs.map((doc) => (
            <KnowledgeItem key={doc.id} doc={doc} />
          ))}
        </Box>
      ))}
    </Box>
  )
}

function KnowledgeItem({ doc }) {
  return (
    <Accordion disableGutters>
      <AccordionSummary expandIcon="▾">
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', minWidth: 0 }}>
          <Typography sx={{ fontWeight: 500 }}>{doc.title || preview(doc.text)}</Typography>
          {(doc.tags || []).map((tag) => (
            <Chip key={tag} label={tag} size="small" variant="outlined" />
          ))}
        </Box>
      </AccordionSummary>
      <AccordionDetails>
        <Typography sx={{ whiteSpace: 'pre-wrap', mb: 2 }}>{doc.text || '—'}</Typography>
        <Typography variant="caption" color="text.secondary" component="div">
          id: <Box component="span" sx={{ fontFamily: 'monospace', userSelect: 'all' }}>{doc.id}</Box>
          {doc.source && <> · източник: {doc.source}</>}
        </Typography>
      </AccordionDetails>
    </Accordion>
  )
}

function filter(documents, search) {
  const query = search.trim().toLowerCase()
  if (!query) {
    return documents
  }
  return documents.filter((doc) =>
    [doc.title, doc.text, doc.category, ...(doc.tags || [])].some((field) => field && field.toLowerCase().includes(query)),
  )
}

// [[категория, [документи]], ...] в реда от сървъра (по категория и заглавие)
function groupByCategory(documents) {
  const groups = new Map()
  for (const doc of documents) {
    const category = doc.category || NO_CATEGORY
    if (!groups.has(category)) {
      groups.set(category, [])
    }
    groups.get(category).push(doc)
  }
  return [...groups.entries()]
}

function preview(text) {
  if (!text) {
    return '(без заглавие)'
  }
  return text.length > 80 ? `${text.slice(0, 80)}…` : text
}
