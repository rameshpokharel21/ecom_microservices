import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // 5173 is not a default worth losing: it is hardcoded in the gateway's CORS bean
  // and in Keycloak's Web origins, so strictPort fails loudly instead of silently
  // moving to 5174 and producing an unexplainable CORS error.
  server: { port: 5173, strictPort: true },
})
