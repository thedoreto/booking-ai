import { useState } from 'react'
import { AppBar, Box, Button, Container, Tab, Tabs, Toolbar, Typography } from '@mui/material'
import KnowledgePage from './KnowledgePage.jsx'

// Страницата след вход: горна лента и табове. Тук ще влязат отчетите, бутоните и т.н.
export default function HomePage({ admin, onLogout }) {
  const [tab, setTab] = useState('knowledge')

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'grey.100' }}>
      <AppBar position="static">
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
            Админ панел – {admin.hotelId}
          </Typography>
          <Typography variant="body2" sx={{ display: { xs: 'none', sm: 'block' } }}>
            {admin.name || admin.email}
          </Typography>
          <Button color="inherit" onClick={onLogout}>
            Изход
          </Button>
        </Toolbar>
      </AppBar>
      <Box sx={{ bgcolor: 'background.paper', borderBottom: 1, borderColor: 'divider' }}>
        <Container>
          <Tabs value={tab} onChange={(e, value) => setTab(value)}>
            <Tab value="knowledge" label="Знания" />
          </Tabs>
        </Container>
      </Box>
      <Container sx={{ py: 3 }}>
        {tab === 'knowledge' && <KnowledgePage onUnauthorized={onLogout} />}
      </Container>
    </Box>
  )
}
