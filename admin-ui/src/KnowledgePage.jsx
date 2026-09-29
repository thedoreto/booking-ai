import { useEffect, useMemo, useState } from 'react'
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Dialog,
  DialogActions, DialogContent, DialogContentText, DialogTitle, InputAdornment, Link, TextField, Tooltip, Typography,
} from '@mui/material'
import { deleteKnowledge, getToken, knowledge, settings } from './api.js'
import KnowledgeEditor from './KnowledgeEditor.jsx'
import TranslationsSection from './TranslationsSection.jsx'
import { knowledgeTitle } from './knowledgeTitle.js'

const NO_CATEGORY = 'Без категория'

// Знанията на хотела (knowledge_<hotelId>): групирани по категория, търсене по заглавие, текст и етикети; добавяне,
// редакция на място и изтриване. При всяко знание пише кои бутони го ползват – такова знание не се трие.
export default function KnowledgePage({ onOpenShortcut, onUnauthorized }) {
  const [documents, setDocuments] = useState(null)
  const [error, setError] = useState(null)
  const [search, setSearch] = useState('')
  const [adding, setAdding] = useState(false)
  // Езиците за преводите – на хотела, без основния (text е на него)
  const [translationLanguages, setTranslationLanguages] = useState([])

  useEffect(() => {
    Promise.all([knowledge(getToken()), settings(getToken())])
      .then(([docs, hotel]) => {
        setTranslationLanguages(hotel.languages.filter((l) => l.code !== hotel.defaultLanguage))
        setDocuments(docs)
      })
      .catch((e) => {
        if (e.code === 'UNAUTHORIZED') {
          onUnauthorized()
        } else {
          setError('Знанията не могат да се заредят. Опитайте отново.')
        }
      })
  }, [onUnauthorized])

  const groups = useMemo(() => groupByCategory(filter(documents || [], search)), [documents, search])
  // За подсказките във формата – всички категории и етикети на хотела
  const categories = useMemo(() => distinct((documents || []).map((doc) => doc.category)), [documents])
  const allTags = useMemo(() => distinct((documents || []).flatMap((doc) => doc.tags || [])), [documents])

  function handleSaved(updated) {
    setDocuments((docs) => sortDocuments(docs.map((doc) => (doc.id === updated.id ? updated : doc))))
  }

  function handleCreated(created) {
    setAdding(false)
    setDocuments((docs) => sortDocuments([...docs, created]))
  }

  function handleDeleted(id) {
    setDocuments((docs) => docs.filter((doc) => doc.id !== id))
  }

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
        <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }}>
          {search ? `${shown} от ${documents.length}` : documents.length} документа
        </Typography>
        <Button variant="contained" onClick={() => setAdding(true)} disabled={adding}>
          Добави знание
        </Button>
      </Box>

      {adding && (
        <Card sx={{ mb: 3 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Ново знание
            </Typography>
            <KnowledgeEditor
              doc={null}
              categories={categories}
              allTags={allTags}
              onSaved={handleCreated}
              onCancel={() => setAdding(false)}
              onUnauthorized={onUnauthorized}
            />
          </CardContent>
        </Card>
      )}

      {documents.length === 0 && <Alert severity="info">Хотелът още няма знания.</Alert>}
      {documents.length > 0 && shown === 0 && <Alert severity="info">Няма документи за „{search}“.</Alert>}

      {groups.map(([category, docs]) => (
        <Box key={category} sx={{ mb: 3 }}>
          <Typography variant="overline" color="text.secondary">
            {category} ({docs.length})
          </Typography>
          {docs.map((doc) => (
            <KnowledgeItem key={doc.id} doc={doc} categories={categories} allTags={allTags}
              translationLanguages={translationLanguages} onSaved={handleSaved} onDeleted={handleDeleted}
              onOpenShortcut={onOpenShortcut} onUnauthorized={onUnauthorized} />
          ))}
        </Box>
      ))}
    </Box>
  )
}

function KnowledgeItem({
  doc, categories, allTags, translationLanguages, onSaved, onDeleted, onOpenShortcut, onUnauthorized,
}) {
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const usedBy = doc.usedBy || []
  const missing = translationLanguages.filter((l) => !doc.translations?.[l.code])

  return (
    <Accordion disableGutters>
      <AccordionSummary expandIcon="▾">
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', minWidth: 0 }}>
          <Typography sx={{ fontWeight: 500 }}>{knowledgeTitle(doc)}</Typography>
          {(doc.tags || []).map((tag) => (
            <Chip key={tag} label={tag} size="small" variant="outlined" />
          ))}
          {missing.map((l) => (
            <Chip key={l.code} label={`без ${l.code.toUpperCase()}`} size="small" color="warning" variant="outlined" />
          ))}
          {usedBy.length > 0 && (
            <Chip label={`бутони: ${usedBy.length}`} size="small" color="primary" variant="outlined" />
          )}
        </Box>
      </AccordionSummary>
      <AccordionDetails>
        {editing ? (
          <KnowledgeEditor
            doc={doc}
            categories={categories}
            allTags={allTags}
            onSaved={(updated) => {
              setEditing(false)
              onSaved(updated)
            }}
            onCancel={() => setEditing(false)}
            onUnauthorized={onUnauthorized}
          />
        ) : (
          <>
            <Typography sx={{ whiteSpace: 'pre-wrap', mb: 2 }}>{doc.text || '—'}</Typography>
            <Typography variant="body2" sx={{ mb: 1 }}>
              {usedBy.length > 0
                ? <>Ползва се от {usedBy.length === 1 ? 'бутона' : 'бутоните'}: {usedBy.map((ref, i) => (
                  <span key={ref.shortcutId}>
                    {i > 0 && ', '}
                    <Link component="button" variant="body2" sx={{ fontWeight: 600, verticalAlign: 'baseline' }}
                      onClick={() => onOpenShortcut(ref.shortcutId)}>{ref.label}</Link>
                  </span>
                ))}</>
                : <Box component="span" sx={{ color: 'text.secondary' }}>Не се ползва от бутон.</Box>}
            </Typography>
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1, flexWrap: 'wrap' }}>
              <Typography variant="caption" color="text.secondary" component="div">
                id: <Box component="span" sx={{ fontFamily: 'monospace', userSelect: 'all' }}>{doc.id}</Box>
                {doc.source && <> · източник: {doc.source}</>}
              </Typography>
              <Box sx={{ display: 'flex', gap: 1 }}>
                <Tooltip title={usedBy.length > 0 ? `Ползва се от: ${labels(usedBy)}. Първо го махнете от бутоните.` : ''}>
                  <span>
                    <Button size="small" color="error" disabled={usedBy.length > 0} onClick={() => setConfirming(true)}>
                      Изтрий
                    </Button>
                  </span>
                </Tooltip>
                <Button size="small" variant="outlined" onClick={() => setEditing(true)}>
                  Редактирай
                </Button>
              </Box>
            </Box>
            {translationLanguages.length > 0 && (
              <TranslationsSection doc={doc} languages={translationLanguages} onSaved={onSaved} onUnauthorized={onUnauthorized} />
            )}
            <DeleteDialog
              open={confirming}
              doc={doc}
              onClose={() => setConfirming(false)}
              onDeleted={() => onDeleted(doc.id)}
              onUsed={(updated) => onSaved({ ...doc, usedBy: updated })}
              onUnauthorized={onUnauthorized}
            />
          </>
        )}
      </AccordionDetails>
    </Accordion>
  )
}

function DeleteDialog({ open, doc, onClose, onDeleted, onUsed, onUnauthorized }) {
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState(null)

  async function handleDelete() {
    setError(null)
    setDeleting(true)
    try {
      await deleteKnowledge(getToken(), doc.id)
      onClose()
      onDeleted()
    } catch (e) {
      if (e.code === 'UNAUTHORIZED') {
        onUnauthorized()
      } else if (e.code === 'IN_USE') {
        // Междувременно е добавено в бутон
        setError(`Не е изтрито – ползва се от: ${labels(e.body.usedBy || [])}.`)
        onUsed(e.body.usedBy || [])
      } else if (e.code === 'NOT_FOUND') {
        onClose()
        onDeleted()
      } else {
        setError('Няма връзка със сървъра. Знанието не е изтрито.')
      }
    } finally {
      setDeleting(false)
    }
  }

  return (
    <Dialog open={open} onClose={deleting ? undefined : onClose}>
      <DialogTitle>Изтриване на знание</DialogTitle>
      <DialogContent>
        <DialogContentText>
          „{knowledgeTitle(doc)}“ ще бъде изтрито завинаги.
        </DialogContentText>
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={deleting}>Отказ</Button>
        <Button color="error" variant="contained" onClick={handleDelete} disabled={deleting}>
          {deleting ? 'Изтриване…' : 'Изтрий'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

function labels(usedBy) {
  return usedBy.map((ref) => ref.label).join(', ')
}

// Като сървъра: по категория, после по заглавие; без категория – първи
function sortDocuments(documents) {
  return [...documents].sort((a, b) =>
    (a.category || '').localeCompare(b.category || '', 'bg') || (a.title || '').localeCompare(b.title || '', 'bg'))
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

function distinct(values) {
  return [...new Set(values.filter(Boolean))].sort((a, b) => a.localeCompare(b, 'bg'))
}
