import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Админ панелът се сервира от booking-ai на /admin/. Билдът отива направо в target/classes/static/admin
// (Maven го пуска в prepare-package и го слага в jar-а). При npm run dev заявките към /api отиват
// на локалния booking-ai (порт 8081).
export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  build: {
    outDir: '../target/classes/static/admin',
    emptyOutDir: true,
  },
  server: {
    port: 5174,
    proxy: {
      '/api': 'http://localhost:8081',
    },
  },
})
