import { useState } from 'react'
import {
  Alert, Autocomplete, Box, Button, FormControlLabel, MenuItem, Switch, TextField, ToggleButton, ToggleButtonGroup,
  Typography,
} from '@mui/material'
import { createShortcut, getToken, updateShortcut } from './api.js'
import { knowledgeTitle } from './knowledgeTitle.js'

const MAX_SHORTCUT_ID_LENGTH = 50

const ERRORS = {
  SHORTCUT_ID_REQUIRED: 'Идентификаторът е задължителен.',
  SHORTCUT_ID_INVALID: `Идентификаторът може да съдържа само латински букви, цифри, _ и - (до ${MAX_SHORTCUT_ID_LENGTH} знака); не може да е „order“ или „tools“.`,
  SHORTCUT_ID_TAKEN: 'Вече има бутон с този идентификатор.',
  LABEL_REQUIRED: 'Надписът на основния език е задължителен.',
  UNKNOWN_LANGUAGE: 'Надпис на език, който хотелът няма.',
  ACTION_REQUIRED: 'Изберете какво прави бутонът.',
  KNOWLEDGE_REQUIRED: 'Изберете поне едно знание.',
  UNKNOWN_KNOWLEDGE: 'Някое от избраните знания вече не съществува. Презаредете страницата.',
  UNKNOWN_TOOL: 'Изберете действие от списъка.',
  NOT_FOUND: 'Бутонът вече не съществува.',
  NETWORK: 'Няма връзка със сървъра. Промяната не е записана.',
}

// Формата за нов бутон (shortcut = null) или за редакция. languages – езиците на хотела, основният е задължителен.
// Действието: знания (в реда на избор – в този ред излизат в чата) или tool.
// initial – попълнените полета на новия бутон (от таба „Предложения“: надпис и знания).
export default function ShortcutEditor({
  shortcut: existing, initial, languages, defaultLanguage, categories, documents, tools, onSaved, onCancel, onUnauthorized,
}) {
  const isNew = !existing
  const shortcut = existing || initial || {}
  const [shortcutId, setShortcutId] = useState(shortcut.shortcutId || '')
  const [label, setLabel] = useState(shortcut.label || {})
  const [category, setCategory] = useState(shortcut.category || '')
  const [isActive, setIsActive] = useState(shortcut.isActive ?? true)
  const [guestVisible, setGuestVisible] = useState(shortcut.guestVisible ?? true)
  const [type, setType] = useState(shortcut.action?.type || 'knowledge')
  const [knowledgeIds, setKnowledgeIds] = useState(shortcut.action?.knowledgeIds || [])
  const [tool, setTool] = useState(shortcut.action?.tool || '')
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)

  const documentsById = new Map(documents.map((doc) => [doc.id, doc]))
  const selectedTool = tools.find((t) => t.name === tool)

  async function handleSubmit(event) {
    event.preventDefault()
    setError(null)
    setSaving(true)
    try {
      const action = type === 'tool' ? { type, tool } : { type, knowledgeIds }
      const changes = { shortcutId, label, category, isActive, guestVisible, action }
      const saved = isNew
        ? await createShortcut(getToken(), changes)
        : await updateShortcut(getToken(), shortcut.shortcutId, changes)
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
      <TextField
        label="Идентификатор"
        value={shortcutId}
        onChange={(e) => setShortcutId(e.target.value)}
        size="small"
        required
        disabled={!isNew}
        helperText={isNew ? 'Латински букви, цифри, _ и -. След създаване не се сменя.' : 'Не се сменя.'}
        slotProps={{ htmlInput: { maxLength: MAX_SHORTCUT_ID_LENGTH, style: { fontFamily: 'monospace' } } }}
      />
      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
        {languages.map((language) => (
          <TextField
            key={language.code}
            label={`Надпис – ${language.name}`}
            value={label[language.code] || ''}
            onChange={(e) => setLabel((current) => ({ ...current, [language.code]: e.target.value }))}
            size="small"
            required={language.code === defaultLanguage}
            sx={{ flex: '1 1 200px' }}
          />
        ))}
      </Box>
      <Autocomplete
        freeSolo
        options={categories}
        inputValue={category}
        onInputChange={(e, value) => setCategory(value)}
        renderInput={(params) => <TextField {...params} label="Категория" size="small" />}
      />
      <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>
        <FormControlLabel
          control={<Switch checked={isActive} onChange={(e) => setIsActive(e.target.checked)} />}
          label="Активен (показва се в чата)"
        />
        <FormControlLabel
          control={<Switch checked={guestVisible} onChange={(e) => setGuestVisible(e.target.checked)} />}
          label="Видим за гост (без вход)"
        />
      </Box>

      <Box>
        <Typography variant="subtitle2" gutterBottom>Какво прави бутонът</Typography>
        <ToggleButtonGroup exclusive size="small" value={type} onChange={(e, value) => value && setType(value)}>
          <ToggleButton value="knowledge">Показва знания</ToggleButton>
          <ToggleButton value="tool">Действие</ToggleButton>
        </ToggleButtonGroup>
      </Box>
      {type === 'knowledge' ? (
        <Autocomplete
          multiple
          options={documents.map((doc) => doc.id)}
          value={knowledgeIds}
          onChange={(e, value) => setKnowledgeIds(value)}
          getOptionLabel={(id) => (documentsById.has(id) ? knowledgeTitle(documentsById.get(id)) : `(изтрито) ${id}`)}
          groupBy={(id) => documentsById.get(id)?.category || 'Без категория'}
          renderInput={(params) => (
            <TextField {...params} label="Знания" size="small"
              helperText="В чата се показват в реда на избор. За друг ред – махнете и изберете отново." />
          )}
        />
      ) : (
        <TextField
          select
          label="Действие"
          value={tool}
          onChange={(e) => setTool(e.target.value)}
          size="small"
          helperText={selectedTool?.description}
        >
          {tools.map((t) => (
            <MenuItem key={t.name} value={t.name} sx={{ fontFamily: 'monospace' }}>{t.name}</MenuItem>
          ))}
        </TextField>
      )}

      {error && <Alert severity="error">{error}</Alert>}
      <Box sx={{ display: 'flex', gap: 1 }}>
        <Button type="submit" variant="contained" disabled={saving}>
          {saving ? 'Записване…' : 'Запази'}
        </Button>
        <Button onClick={onCancel} disabled={saving}>
          Отказ
        </Button>
      </Box>
    </Box>
  )
}
