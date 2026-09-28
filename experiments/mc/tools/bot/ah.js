// Аукцион: продавец выставляет, покупатель покупает через GUI, продавец снимает второй лот.
const mineflayer = require('mineflayer')
const { execSync } = require('child_process')
const ChatMessage = require('prismarine-chat')('1.16.5')
const wait = ms => new Promise(r => setTimeout(r, ms))
const srv = c => execSync(`SSHPASS='TKvJt4Lr5Zdz0j' sshpass -e ssh mc@37.153.70.33 'screen -S mc -p 0 -X stuff "${c}$(printf \\\\r)"'`)
const text = j => { try { return new ChatMessage(JSON.parse(j)).toString() } catch { return String(j) } }
const make = name => new Promise(res => {
  const b = mineflayer.createBot({ host: 'play.vladislavb.ru', port: 25565, username: name, version: '1.16.5', auth: 'offline' })
  b.on('messagestr', (m, pos) => { if (pos !== 'game_info' && !/Зарегистр|\[\+\]|\[-\]|Не так быстро/.test(m)) console.log(`   [${name} чат]`, m) })
  b.once('spawn', () => res(b))
})
function dump(bot, label) {
  const w = bot.currentWindow
  if (!w) return console.log(`--- ${bot.username}: ${label}: окно закрыто`)
  const items = []
  for (let i = 0; i < w.inventoryStart; i++) {
    const it = w.slots[i]
    if (!it || it.name.endsWith('glass_pane')) continue
    const n = it.nbt?.value?.display?.value?.Name?.value
    const lore = (it.nbt?.value?.display?.value?.Lore?.value?.value || []).map(text).filter(l => l.trim()).join(' | ')
    items.push(`[${i}] ${n ? text(n) : it.name + ' x' + it.count}${lore && i < 45 ? '  «' + lore + '»' : ''}`)
  }
  console.log(`--- ${bot.username}: ${label}: "${text(w.title)}"\n     ${items.join('\n     ')}`)
}
const opened = bot => new Promise(r => bot.once('windowOpen', () => setTimeout(r, 400)))
async function click(bot, slot) { const o = opened(bot); bot.clickWindow(slot, 0, 0).catch(() => {}); await Promise.race([o, wait(2500)]) }
const count = (bot, name) => bot.inventory.items().filter(i => i.name === name).reduce((s, i) => s + i.count, 0)

;(async () => {
  const s = await make('AhSeller'); s.chat('/register ahpass123 ahpass123'); await wait(5000)
  const b = await make('AhBuyer'); b.chat('/register ahpass123 ahpass123'); await wait(2000)
  srv('give AhSeller diamond 16'); await wait(1000); srv('give AhSeller gold_ingot 5'); await wait(1500)
  s.setQuickBarSlot(0); await wait(300)
  console.log('у продавца алмазов:', count(s, 'diamond'), '| золота:', count(s, 'gold_ingot'))
  s.chat('/ah sell 50'); await wait(1500)
  console.log('у продавца алмазов после выставления:', count(s, 'diamond'))
  s.chat('/balance'); await wait(1000)

  let o = opened(b); b.chat('/ah'); await o; dump(b, 'аукцион')
  await click(b, 0); dump(b, 'подтверждение')
  b.clickWindow(0, 0, 0).catch(() => {}); await wait(2000)
  console.log('у покупателя алмазов:', count(b, 'diamond'))
  b.chat('/balance'); await wait(1000)
  s.chat('/balance'); await wait(1000)

  // Второй лот и снятие через "Мои лоты".
  s.setQuickBarSlot(1); await wait(300)
  s.chat('/ah sell 10'); await wait(1500)
  o = opened(s); s.chat('/ah my'); await o; dump(s, 'мои лоты')
  s.clickWindow(0, 0, 0).catch(() => {}); await wait(1500)
  console.log('золото вернулось продавцу:', count(s, 'gold_ingot'))
  s.quit(); b.quit(); await wait(500); process.exit(0)
})()
setTimeout(() => process.exit(0), 70000)
