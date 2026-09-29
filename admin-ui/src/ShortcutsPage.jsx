import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert, Box, Button, Card, CardContent, Chip, CircularProgress, Dialog, DialogActions, DialogContent,
  DialogContentText, DialogTitle, IconButton, Tooltip, Typography,
} from '@mui/material'
import { deleteShortcut, getToken, knowledge, reorderShortcuts, settings, shortcutTools, shortcuts } from './api.js'
import ShortcutEditor from './ShortcutEditor.jsx'
import { knowledgeTitle } from './knowledgeTitle.js'

// Бутоните на хотела (shortcuts_<hotelId>) в реда, в който излизат в чата: ↑↓ сменят реда, добавяне, редакция на място,
// изтриване. focusShortcutId – бутонът, към който се стига от таба „Знания“.
export default function ShortcutsPage({ focusShortcutId, onUnauthorized }) {
  const [items, setItems] = useState(null)
  const [hotel, setHotel] = useState(null)
  const [documents, setDocuments] = useState([])
  const [tools, setTools] = useState([])
  const [error, setError] = useState(null)
  const [orderError, setOrderError] = useState(null)
  const [moving, setMoving] = useState(false)
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    const token = getToken()
    Promise.all([shortcuts(token), settings(token), knowledge(token), shortcutTools(token)])
      .then(([buttons, hotelSettings, docs, toolList]) => {
        setHotel(hotelSettings)
        setDocuments(docs)
        setTools(toolList)
        setItems(buttons)
      })
      .catch((e) => {
        if (e.code === 'UNAUTHORIZED') {
          onUnauthorized()
        } else {
          setError('Бутоните не могат да се заредят. Опитайте отново.')
        }
      })
  }, [onUnauthorized])

  const categories = useMemo(
    () => [...new Set((items || []).map((s) => s.category).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'bg')),
    [items],
  )

  async function move(index, delta) {
    const reordered = [...items]
    const [moved] = reordered.splice(index, 1)
    reordered.splice(index + delta, 0, moved)
    const previous = items
    setItems(reordered)
    setOrderError(null)
    setMoving(true)
    try {
      setItems(await reorderShortcuts(getToken(), reordered.map((s) => s.shortcutId)))
    } catch (e) {
      setItems(previous)
      if (e.code === 'UNAUTHORIZED') {
        onUnauthorized()
      } else {
        // INVALID_ORDER – междувременно бутоните са променени (друг таб)
        setOrderError('Редът не е записан. Презаредете страницата и опитайте отново.')
      }
    } finally {
      setMoving(false)
    }
  }

  function handleSaved(updated) {
    setItems((current) => current.map((s) => (s.shortcutId === updated.shortcutId ? updated : s)))
  }

  function handleCreated(created) {
    setAdding(false)
    setItems((current) => [...current, created])
  }

  function handleDeleted(shortcutId) {
    setItems((current) => current.filter((s) => s.shortcutId !== shortcutId))
  }

  if (error) {
    return <Alert severity="error">{error}</Alert>
  }
  if (!items) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 6 }}>
        <CircularProgress />
      </Box>
    )
  }

  const editorProps = {
    languages: hotel.languages,
    defaultLanguage: hotel.defaultLanguage,
    categories,
    documents,
    tools,
    onUnauthorized,
  }
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3, flexWrap: 'wrap' }}>
        <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }}>
          {items.length} бутона – в този ред се показват в чата
        </Typography>
        <Button variant="contained" onClick={() => setAdding(true)} disabled={adding}>
          Добави бутон
        </Button>
      </Box>

      {adding && (
        <Card sx={{ mb: 3 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Нов бутон
            </Typography>
            <ShortcutEditor shortcut={null} {...editorProps} onSaved={handleCreated} onCancel={() => setAdding(false)} />
          </CardContent>
        </Card>
      )}

      {orderError && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setOrderError(null)}>{orderError}</Alert>}
      {items.length === 0 && <Alert severity="info">Хотелът още няма бутони.</Alert>}

      {items.map((shortcut, index) => (
        <ShortcutItem
          key={shortcut.shortcutId}
          shortcut={shortcut}
          focused={shortcut.shortcutId === focusShortcutId}
          canMoveUp={index > 0 && !moving}
          canMoveDown={index < items.length - 1 && !moving}
          onMoveUp={() => move(index, -1)}
          onMoveDown={() => move(index, 1)}
          editorProps={editorProps}
          onSaved={handleSaved}
          onDeleted={handleDeleted}
        />
      ))}
    </Box>
  )
}

function ShortcutItem({ shortcut, focused, canMoveUp, canMoveDown, onMoveUp, onMoveDown, editorProps, onSaved, onDeleted }) {
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const ref = useRef(null)
  const { languages, defaultLanguage, documents, onUnauthorized } = editorProps
  const missing = languages.filter((l) => l.code !== defaultLanguage && !shortcut.label[l.code])

  useEffect(() => {
    if (focused) {
      ref.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, [focused])

  return (
    <Card ref={ref} sx={{ mb: 1, outline: focused ? 2 : 0, outlineColor: 'primary.main' }}>
      <CardContent sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', '&:last-child': { pb: 2 } }}>
        <Box sx={{ display: 'flex', flexDirection: 'column' }}>
          <Tooltip title="Нагоре">
            <span>
              <IconButton size="small" disabled={!canMoveUp} onClick={onMoveUp} aria-label="Нагоре">↑</IconButton>
            </span>
          </Tooltip>
          <Tooltip title="Надолу">
            <span>
              <IconButton size="small" disabled={!canMoveDown} onClick={onMoveDown} aria-label="Надолу">↓</IconButton>
            </span>
          </Tooltip>
        </Box>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          {editing ? (
            <ShortcutEditor
              shortcut={shortcut}
              {...editorProps}
              onSaved={(updated) => {
                setEditing(false)
                onSaved(updated)
              }}
              onCancel={() => setEditing(false)}
            />
          ) : (
            <>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                <Typography sx={{ fontWeight: 500 }}>{shortcut.label[defaultLanguage] || shortcut.shortcutId}</Typography>
                {shortcut.category && <Chip label={shortcut.category} size="small" variant="outlined" />}
                {!shortcut.isActive && <Chip label="неактивен" size="small" />}
                {!shortcut.guestVisible && <Chip label="скрит за гост" size="small" color="info" variant="outlined" />}
                {missing.map((l) => (
                  <Chip key={l.code} label={`без ${l.code.toUpperCase()}`} size="small" color="warning" variant="outlined" />
                ))}
              </Box>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                {describeAction(shortcut.action, documents)}
              </Typography>
              <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1, flexWrap: 'wrap', mt: 1 }}>
                <Typography variant="caption" color="text.secondary" component="div">
                  id: <Box component="span" sx={{ fontFamily: 'monospace', userSelect: 'all' }}>{shortcut.shortcutId}</Box>
                </Typography>
                <Box sx={{ display: 'flex', gap: 1 }}>
                  <Button size="small" color="error" onClick={() => setConfirming(true)}>
                    Изтрий
                  </Button>
                  <Button size="small" variant="outlined" onClick={() => setEditing(true)}>
                    Редактирай
                  </Button>
                </Box>
              </Box>
              <DeleteDialog
                open={confirming}
                shortcut={shortcut}
                label={shortcut.label[defaultLanguage] || shortcut.shortcutId}
                onClose={() => setConfirming(false)}
                onDeleted={() => onDeleted(shortcut.shortcutId)}
                onUnauthorized={onUnauthorized}
              />
            </>
          )}
        </Box>
      </CardContent>
    </Card>
  )
}

function DeleteDialog({ open, shortcut, label, onClose, onDeleted, onUnauthorized }) {
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState(null)

  async function handleDelete() {
    setError(null)
    setDeleting(true)
    try {
      await deleteShortcut(getToken(), shortcut.shortcutId)
      onClose()
      onDeleted()
    } catch (e) {
      if (e.code === 'UNAUTHORIZED') {
        onUnauthorized()
      } else if (e.code === 'NOT_FOUND') {
        onClose()
        onDeleted()
      } else {
        setError('Няма връзка със сървъра. Бутонът не е изтрит.')
      }
    } finally {
      setDeleting(false)
    }
  }

  return (
    <Dialog open={open} onClose={deleting ? undefined : onClose}>
      <DialogTitle>Изтриване на бутон</DialogTitle>
      <DialogContent>
        <DialogContentText>
          Бутонът „{label}“ ще бъде изтрит завинаги и няма да се показва в чата. Знанията, към които сочи, остават.
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

function describeAction(action, documents) {
  if (!action) {
    return 'Без действие – в чата показва „Информацията не е намерена.“'
  }
  if (action.type === 'tool') {
    return `Действие: ${action.tool}`
  }
  const byId = new Map(documents.map((doc) => [doc.id, doc]))
  const titles = (action.knowledgeIds || []).map((id) => (byId.has(id) ? knowledgeTitle(byId.get(id)) : '(изтрито знание)'))
  return `Знания: ${titles.join(' · ') || '—'}`
}
