import { useState } from 'react'
import { Alert, Box, Button, Card, CardContent, TextField, Typography } from '@mui/material'
import { login } from './api.js'

const ERRORS = {
  INVALID_CREDENTIALS: 'Грешен хотел, имейл или парола.',
  TOO_MANY_ATTEMPTS: 'Твърде много грешни опити. Опитайте отново след 15 минути.',
  NETWORK: 'Няма връзка със сървъра. Опитайте отново.',
}

// Последният хотел се помни в браузъра (не е тайна) – да не се пише всеки път.
// Хотелът може да дойде и от адреса: /admin/?hotel=40_robbers
const HOTEL_KEY = 'adminHotelId'

function initialHotel() {
  const fromUrl = new URLSearchParams(window.location.search).get('hotel')
  if (fromUrl) {
    return fromUrl
  }
  try {
    return localStorage.getItem(HOTEL_KEY) || ''
  } catch {
    return ''
  }
}

export default function LoginPage({ onLogin }) {
  const [hotelId, setHotelId] = useState(initialHotel)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const result = await login(hotelId.trim(), email.trim(), password)
      try {
        localStorage.setItem(HOTEL_KEY, result.hotelId)
      } catch {
        // без localStorage хотелът просто не се помни
      }
      onLogin(result)
    } catch (e) {
      setError(ERRORS[e.code] || ERRORS.NETWORK)
      setPassword('')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', bgcolor: 'grey.100', px: 2 }}>
      <Card sx={{ width: '100%', maxWidth: 400 }}>
        <CardContent sx={{ p: 4 }}>
          <Typography variant="h5" component="h1" gutterBottom>
            Админ панел
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            Вход за администратора на хотела
          </Typography>
          <Box component="form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField label="Хотел" value={hotelId} onChange={(e) => setHotelId(e.target.value)} required autoFocus={!hotelId} />
            <TextField label="Имейл" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required
              autoComplete="username" autoFocus={Boolean(hotelId)} />
            <TextField label="Парола" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required
              autoComplete="current-password" />
            {error && <Alert severity="error">{error}</Alert>}
            <Button type="submit" variant="contained" size="large" disabled={loading}>
              {loading ? 'Влизане…' : 'Вход'}
            </Button>
          </Box>
        </CardContent>
      </Card>
    </Box>
  )
}
