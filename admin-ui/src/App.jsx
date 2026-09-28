import { useEffect, useState } from 'react'
import { Box, CircularProgress } from '@mui/material'
import { clearToken, getToken, me, saveToken } from './api.js'
import LoginPage from './LoginPage.jsx'
import HomePage from './HomePage.jsx'

// Влезлият админ ({ hotelId, email, name }) или null. При отваряне пазеният токен се проверява с /me.
export default function App() {
  const [admin, setAdmin] = useState(null)
  const [checking, setChecking] = useState(() => Boolean(getToken()))

  useEffect(() => {
    const token = getToken()
    if (!token) {
      return
    }
    me(token)
      .then(setAdmin)
      .catch(() => clearToken())
      .finally(() => setChecking(false))
  }, [])

  function handleLogin({ token, hotelId, email, name }) {
    saveToken(token)
    setAdmin({ hotelId, email, name })
  }

  function handleLogout() {
    clearToken()
    setAdmin(null)
  }

  if (checking) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 10 }}>
        <CircularProgress />
      </Box>
    )
  }
  return admin ? <HomePage admin={admin} onLogout={handleLogout} /> : <LoginPage onLogin={handleLogin} />
}
