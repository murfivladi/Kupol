// Иконки PWA из логотипа (public/favicon.svg): node scripts/pwa-icons.mjs. Результат — public/icons/*.png, в репозитории.
// Обычная иконка — со скруглением, как favicon; maskable и apple-touch — на весь квадрат: система сама обрежет по своей форме,
// поэтому знак ужат в безопасную зону (центральные 80%).
import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'

const grad = `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
  <stop offset="0" stop-color="#6366f1"/><stop offset=".5" stop-color="#8b5cf6"/><stop offset="1" stop-color="#ec4899"/>
</linearGradient></defs>`
const glyph = `<path d="M17 20l15 26 15-26" fill="none" stroke="#fff" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="49" cy="15" r="3.5" fill="#fff" opacity=".9"/>`

const rounded = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">${grad}<rect width="64" height="64" rx="18" fill="url(#g)"/>${glyph}</svg>`
// full bleed: фон на весь квадрат, знак уменьшен до 72% вокруг центра
const bleed = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">${grad}<rect width="64" height="64" fill="url(#g)"/>
  <g transform="translate(32 32) scale(.72) translate(-32 -32)">${glyph}</g></svg>`

const out = [
  ['icon-192.png', rounded, 192],
  ['icon-512.png', rounded, 512],
  ['maskable-512.png', bleed, 512],
  ['apple-touch-icon.png', bleed, 180],
]

await mkdir('public/icons', { recursive: true })
const browser = await chromium.launch()
const page = await browser.newPage()
for (const [name, svg, size] of out) {
  await page.setViewportSize({ width: size, height: size })
  await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`)
  await page.screenshot({ path: `public/icons/${name}`, omitBackground: true, clip: { x: 0, y: 0, width: size, height: size } })
  console.log(name, size)
}
await browser.close()
