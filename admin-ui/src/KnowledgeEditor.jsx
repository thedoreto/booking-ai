import { useState } from 'react'
import { Alert, Autocomplete, Box, Button, TextField, Typography } from '@mui/material'
import { createKnowledge, getToken, updateKnowledge } from './api.js'

const MAX_TEXT_LENGTH = 10000

const ERRORS = {
  TEXT_REQUIRED: 'Текстът е задължителен.',
  TEXT_TOO_LONG: `Текстът е твърде дълъг (най-много ${MAX_TEXT_LENGTH.toLocaleString('bg-BG')} знака).`,
  NOT_FOUND: 'Документът вече не съществува.',
  EMBEDDING_FAILED: 'Gemini не отговори – нищо не е записано. Опитайте отново.',
  NETWORK: 'Няма връзка със сървъра. Промяната не е записана.',
}

// Формата за ново знание (doc = null) или за редакция. Embedding-ът се прави в booking-ai: за новото – винаги,
// при редакция – само ако текстът е променен.
export default function KnowledgeEditor({ doc: existing, categories, allTags, onSaved, onCancel, onUnauthorized }) {
  const isNew = !existing
  const doc = existing || {}
  const [title, setTitle] = useState(doc.title || '')
  const [category, setCategory] = useState(doc.category || '')
  const [tags, setTags] = useState(doc.tags || [])
  const [source, setSource] = useState(doc.source || '')
  const [text, setText] = useState(doc.text || '')
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)

  const textChanged = !isNew && text.trim() !== (doc.text || '').trim()

  async function handleSubmit(event) {
    event.preventDefault()
    setError(null)
    setSaving(true)
    try {
      const changes = { title, category, tags, source, text }
      const saved = isNew
        ? await createKnowledge(getToken(), changes)
        : await updateKnowledge(getToken(), doc.id, changes)
      onSaved(saved)
    } catch (e) {
      if (e.code === 'UNAUTHORIZED') {
        onUnauthorized()
        return
      }
      setError(ERRORS[e.code] || ERRORS.NETWORK)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box component="form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <TextField label="Заглавие" value={title} onChange={(e) => setTitle(e.target.value)} size="small" />
      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
        <Autocomplete
          freeSolo
          options={categories}
          inputValue={category}
          onInputChange={(e, value) => setCategory(value)}
          sx={{ flex: '1 1 200px' }}
          renderInput={(params) => <TextField {...params} label="Категория" size="small" />}
        />
        <TextField label="Източник" value={source} onChange={(e) => setSource(e.target.value)} size="small"
          sx={{ flex: '1 1 200px' }} />
      </Box>
      <Autocomplete
        multiple
        freeSolo
        autoSelect
        options={allTags}
        value={tags}
        onChange={(e, value) => setTags(value)}
        renderInput={(params) => (
          <TextField {...params} label="Етикети" size="small" helperText="Enter добавя нов етикет" />
        )}
      />
      <TextField
        label="Текст"
        value={text}
        onChange={(e) => setText(e.target.value)}
        multiline
        minRows={4}
        maxRows={20}
        required
        error={text.length > MAX_TEXT_LENGTH}
        helperText={`${text.length.toLocaleString('bg-BG')} / ${MAX_TEXT_LENGTH.toLocaleString('bg-BG')} знака`}
      />
      {error && <Alert severity="error">{error}</Alert>}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <Button type="submit" variant="contained" disabled={saving}>
          {saving ? 'Записване…' : 'Запази'}
        </Button>
        <Button onClick={onCancel} disabled={saving}>
          Отказ
        </Button>
        {textChanged && (
          <Typography variant="caption" color="text.secondary">
            Текстът е променен – ще се изчисли наново embedding.
          </Typography>
        )}
      </Box>
    </Box>
  )
}
