import { Box, Card, CardContent, Typography } from '@mui/material'

// Общото за двата отчета („Чат“ и „Gemini“): карти с числа и секция с заглавие; форматът – в reportFormat.js

// cards: [{ label, value, note? }]
export function Cards({ cards }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: 2, mb: 3 }}>
      {cards.map((card) => (
        <Card key={card.label}>
          <CardContent>
            <Typography variant="body2" color="text.secondary">{card.label}</Typography>
            <Typography variant="h4" component="div">{card.value}</Typography>
            {card.note && <Typography variant="caption" color="text.secondary">{card.note}</Typography>}
          </CardContent>
        </Card>
      ))}
    </Box>
  )
}

export function Section({ title, hint, children }) {
  return (
    <Card sx={{ mb: 3 }}>
      <CardContent>
        <Typography variant="h6">{title}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{hint}</Typography>
        {children}
      </CardContent>
    </Card>
  )
}
