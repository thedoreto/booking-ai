import { AppBar, Box, Button, Container, Toolbar, Typography } from '@mui/material'

// Началната страница след вход. Тук ще влязат отчетите, знанията и бутоните.
export default function HomePage({ admin, onLogout }) {
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
      <Container sx={{ py: 4 }}>
        <Typography variant="h5" gutterBottom>
          Здравейте{admin.name ? `, ${admin.name}` : ''}!
        </Typography>
        <Typography color="text.secondary">
          Влязохте като администратор на хотел {admin.hotelId} ({admin.email}).
        </Typography>
      </Container>
    </Box>
  )
}
