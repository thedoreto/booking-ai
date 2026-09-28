import { useState } from 'react'
import {
  Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Divider,
  TextField, Typography,
} from '@mui/material'
import { getToken, saveTranslation, suggestTranslation, translateAll } from './api.js'

const MAX_TEXT_LENGTH = 10000

const ERRORS = {
  TEXT_REQUIRED: 'Преводът е задължителен.',
  TEXT_TOO_LONG: 'Преводът е твърде дълъг.',
  UNKNOWN_LANGUAGE: 'Хотелът няма този език.',
  NO_LANGUAGES: 'Хотелът няма други езици.',
  NOT_FOUND: 'Документът вече не съществува.',
  TRANSLATION_FAILED: 'Gemini не отговори – нищо не е записано. Опитайте отново.',
  NETWORK: 'Няма връзка със сървъра. Нищо не е записано.',
}

// Преводите на знанието за бутоните – по един ред за всеки език на хотела без основния.
// Основният текст не се превежда сам: „Добави на …“ (предложение от Gemini, което админът поправя и записва),
// „Редактирай“ за един език или „Преведи на всички езици“ (Gemini, замества всички преводи).
export default function TranslationsSection({ doc, languages, onSaved, onUnauthorized }) {
  const [confirming, setConfirming] = useState(false)
  const [translating, setTranslating] = useState(false)
  const [error, setError] = useState(null)

  function fail(e) {
    if (e.code === 'UNAUTHORIZED') {
      onUnauthorized()
    } else {
      setError(ERRORS[e.code] || ERRORS.NETWORK)
    }
  }

  async function handleTranslateAll() {
    setConfirming(false)
    setError(null)
    setTranslating(true)
    try {
      onSaved(await translateAll(getToken(), doc.id))
    } catch (e) {
      fail(e)
    } finally {
      setTranslating(false)
    }
  }

  const names = languages.map((l) => l.name).join(', ')
  return (
    <Box sx={{ mt: 2 }}>
      <Divider sx={{ mb: 2 }} />
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1, flexWrap: 'wrap', mb: 1 }}>
        <Typography variant="subtitle2">Преводи (за бутоните в чата)</Typography>
        <Button size="small" onClick={() => setConfirming(true)} disabled={translating}
          startIcon={translating ? <CircularProgress size={14} /> : null}>
          {translating ? 'Превеждане…' : 'Преведи на всички езици'}
        </Button>
      </Box>
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
      {languages.map((language) => (
        <TranslationRow key={language.code} doc={doc} language={language} disabled={translating}
          onSaved={onSaved} onUnauthorized={onUnauthorized} />
      ))}
      <Dialog open={confirming} onClose={() => setConfirming(false)}>
        <DialogTitle>Превод на всички езици</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Gemini ще преведе основния текст на {names} и ще замени сегашните преводи, включително ръчните поправки.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirming(false)}>Отказ</Button>
          <Button variant="contained" onClick={handleTranslateAll}>Преведи</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

function TranslationRow({ doc, language, disabled, onSaved, onUnauthorized }) {
  const current = doc.translations?.[language.code] || ''
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState('')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)

  function fail(e) {
    if (e.code === 'UNAUTHORIZED') {
      onUnauthorized()
    } else {
      setError(ERRORS[e.code] || ERRORS.NETWORK)
    }
  }

  function startEdit() {
    setText(current)
    setError(null)
    setNotice(null)
    setEditing(true)
  }

  // Ново: полето се отваря с предложение от Gemini; без отговор – празно, админът пише сам
  async function startAdd() {
    setText('')
    setError(null)
    setNotice(null)
    setEditing(true)
    setLoading(true)
    try {
      const suggestion = await suggestTranslation(getToken(), doc.id, language.code)
      setText(suggestion.text)
      setNotice('Предложение от Gemini – прегледайте го и запишете.')
    } catch (e) {
      if (e.code === 'UNAUTHORIZED') {
        onUnauthorized()
        return
      }
      setNotice('Gemini не предложи превод – напишете го сами.')
    } finally {
      setLoading(false)
    }
  }

  async function handleSave() {
    setError(null)
    setSaving(true)
    try {
      const updated = await saveTranslation(getToken(), doc.id, language.code, text)
      setEditing(false)
      onSaved(updated)
    } catch (e) {
      fail(e)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box sx={{ py: 1 }}>
      <Typography variant="caption" color="text.secondary">{language.name}</Typography>
      {editing ? (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, mt: 0.5 }}>
          {loading ? (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 2 }}>
              <CircularProgress size={18} />
              <Typography variant="body2" color="text.secondary">Gemini превежда…</Typography>
            </Box>
          ) : (
            <TextField value={text} onChange={(e) => setText(e.target.value)} multiline minRows={3} maxRows={20}
              error={text.length > MAX_TEXT_LENGTH} autoFocus />
          )}
          {notice && !loading && <Typography variant="caption" color="text.secondary">{notice}</Typography>}
          {error && <Alert severity="error">{error}</Alert>}
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button size="small" variant="contained" onClick={handleSave} disabled={saving || loading || !text.trim()}>
              {saving ? 'Записване…' : 'Запази'}
            </Button>
            <Button size="small" onClick={() => setEditing(false)} disabled={saving}>Отказ</Button>
          </Box>
        </Box>
      ) : current ? (
        <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 1 }}>
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{current}</Typography>
          <Button size="small" onClick={startEdit} disabled={disabled} sx={{ flexShrink: 0 }}>Редактирай</Button>
        </Box>
      ) : (
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
          <Typography variant="body2" color="text.secondary">Няма превод – бутонът показва основния текст.</Typography>
          <Button size="small" variant="outlined" onClick={startAdd} disabled={disabled} sx={{ flexShrink: 0 }}>
            Добави на {language.name}
          </Button>
        </Box>
      )}
    </Box>
  )
}
