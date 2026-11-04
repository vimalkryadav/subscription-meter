import { defineConfig } from 'vite';
import { resolve } from 'node:path';
export default defineConfig({
  base: './',
  publicDir: false,
  build: { outDir: '../target/frontend-build', emptyOutDir: true,
    sourcemap: false, assetsDir: 'assets/react',
    rolldownOptions: { input: { portal: resolve(import.meta.dirname, 'index.html'), admin: resolve(import.meta.dirname, 'manage.html') } } },
  server: { proxy: { '/portal': 'http://127.0.0.1:8080', '/admin': 'http://127.0.0.1:8080' } }
});
