// Переходник для плагина BuildAI: принимает запросы в формате Ollama (/api/generate)
// и пересылает их в любой OpenAI-совместимый API (DeepSeek, YandexGPT, ...).
//
// BuildAI не умеет передавать ключ API и понимает только ответ Ollama ({"response": "..."}),
// поэтому ключ хранится здесь, в файле key (права 600), и никогда не попадает
// ни в конфиг плагина, ни в консоль сервера.
//
// Запуск: node proxy.js   (настройки — config.json рядом, ключ — файл key рядом)

'use strict'
const http = require('http')
const https = require('https')
const fs = require('fs')
const path = require('path')

const dir = __dirname
const config = JSON.parse(fs.readFileSync(path.join(dir, 'config.json'), 'utf8'))
const readKey = () => fs.readFileSync(path.join(dir, 'key'), 'utf8').trim()

const log = (...a) => console.log(new Date().toISOString(), ...a)

/** Модель из запроса BuildAI, если она похожа на модель апстрима, иначе — из config.json. */
function pickModel(requested) {
  if (requested && !/^(gemma|llama|%)/i.test(requested) && config.allow_model_override) return requested
  return config.model
}

/** ИИ часто оборачивает ответ в ```...``` — BuildAI ждёт голый список команд. */
function cleanup(text) {
  return text.replace(/^```[a-z]*\s*$/gim, '').replace(/```/g, '').trim()
}

function callUpstream(prompt, model, seed) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({
      model,
      messages: [{ role: 'user', content: prompt }],
      temperature: config.temperature ?? 0.4,
      max_tokens: config.max_tokens ?? 4096,
      ...(Number.isInteger(seed) ? { seed } : {}),
    })
    const url = new URL(config.upstream.replace(/\/+$/, '') + '/chat/completions')
    const req = https.request(url, {
      method: 'POST',
      timeout: (config.timeout_seconds ?? 120) * 1000,
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        Authorization: `${config.auth_scheme || 'Bearer'} ${readKey()}`,
      },
    }, res => {
      let data = ''
      res.on('data', c => (data += c))
      res.on('end', () => {
        if (res.statusCode !== 200) return reject(new Error(`апстрим ответил ${res.statusCode}: ${data.slice(0, 300)}`))
        try {
          resolve(JSON.parse(data).choices[0].message.content || '')
        } catch (e) {
          reject(new Error('непонятный ответ апстрима: ' + data.slice(0, 300)))
        }
      })
    })
    req.on('timeout', () => req.destroy(new Error('таймаут апстрима')))
    req.on('error', reject)
    req.end(body)
  })
}

const server = http.createServer((req, res) => {
  const reply = (code, obj) => {
    res.writeHead(code, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(obj))
  }
  if (req.method !== 'POST' || !req.url.startsWith('/api/generate')) return reply(404, { error: 'нужен POST /api/generate' })
  let raw = ''
  req.on('data', c => {
    raw += c
    if (raw.length > 1e6) req.destroy()
  })
  req.on('end', async () => {
    let input
    try {
      input = JSON.parse(raw)
    } catch (e) {
      return reply(400, { error: 'плохой JSON' })
    }
    const model = pickModel(input.model)
    const started = Date.now()
    try {
      const text = cleanup(await callUpstream(String(input.prompt || ''), model, Number(input.seed)))
      log(`ok модель=${model} строк=${text.split('\n').length} за ${Date.now() - started}мс`)
      reply(200, { model, response: text, done: true })
    } catch (e) {
      log(`ошибка модель=${model}: ${e.message}`)
      reply(502, { error: e.message })
    }
  })
})

// Только localhost: снаружи переходник недоступен.
server.listen(config.port ?? 11434, '127.0.0.1', () => log(`переходник слушает 127.0.0.1:${config.port ?? 11434} → ${config.upstream}`))
