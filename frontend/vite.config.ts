import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { keycloakify } from 'keycloakify/vite-plugin'
import { visualizer } from 'rollup-plugin-visualizer'
import path from 'node:path'

export default defineConfig(({ mode }) => ({
  plugins: [
    react(),

    keycloakify({
      accountThemeImplementation: 'none',
      themeName: 'deepfake',
      keycloakVersionTargets: {
        '22-to-25': false,
        'all-other-versions': 'keycloak-theme.jar',
      },
    }),

    mode === 'analyze' &&
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
    passWithNoTests: true,
    exclude: ['tests/e2e/**', 'node_modules/**'],
    coverage: {
      provider: 'v8',
      // lcov feeds SonarCloud; html is uploaded as a CI artifact.
      reporter: ['text-summary', 'lcov', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/test/**',
        'src/**/*.d.ts',
        'src/api/generated/**',
        // Component catalogue, routed only in development builds.
        'src/pages/Playground/**',
      ],
    },
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
}))
