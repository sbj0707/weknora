import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: { port: 5180, strictPort: true },
  preview: { port: 4180, strictPort: true },
  test: { environment: 'jsdom', restoreMocks: true },
})
