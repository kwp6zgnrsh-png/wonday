import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/api/insurance/admin/weather/': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/admin/weather/', '/api/admin/weather/'),
      },
      '/api/insurance/weather/': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/weather/', '/api/weather/'),
      },
      '/api/insurance/platform/link': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/platform/link', '/api/platform/link'),
      },
      '/api/insurance/rewards/': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/rewards/', '/api/rewards/'),
      },
      '/api/insurance/platform/salary-account': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/platform/salary-account', '/api/platform/salary-account'),
      },
      '/api/insurance/claims': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/claims', '/api/claims'),
      },
      '/api/insurance/contracts': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/contracts', '/api/contracts'),
        configure: (proxy) => {
          proxy.on('proxyRes', (response) => {
            const location = response.headers.location
            if (location) {
              response.headers.location = location.replace(
                /^\/api\/contracts(?=\/|[?#]|$)/,
                '/api/insurance/contracts',
              )
            }
          })
        },
      },
      '/api/insurance/products': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/products', '/api/products'),
      },
      '/api/insurance/auth/': {
        target: 'http://127.0.0.1:8080',
        rewrite: (path) => path.replace('/api/insurance/auth/', '/api/auth/'),
      },
    },
  },
})
