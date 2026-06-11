import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { keycloakify } from 'keycloakify/vite-plugin'
import { visualizer } from 'rollup-plugin-visualizer'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    // Theme Keycloacka budowany z tego samego projektu (Keycloakify). themeName = loginTheme
    // w realm-export. Jeden JAR pod Keycloak 26 (target "all-other-versions").
    keycloakify({
      accountThemeImplementation: 'none',
      themeName: 'deepfake',
      keycloakVersionTargets: {
        '22-to-25': false,
        'all-other-versions': 'keycloak-theme.jar',
      },
    }),
    visualizer({ filename: 'dist/stats.html', gzipSize: true, brotliSize: true }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
