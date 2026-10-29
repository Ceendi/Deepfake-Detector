import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'

const [htmlPath, templatePath] = process.argv.slice(2)
const html = readFileSync(htmlPath, 'utf8').replace(/\r\n?/g, '\n')
const inline = html.match(/<script>([\s\S]*?)<\/script>/)?.[1]
if (!inline) throw new Error('Inline theme script missing')
const hash = createHash('sha256').update(inline).digest('base64')
process.stdout.write(readFileSync(templatePath, 'utf8').replaceAll('__THEME_SCRIPT_HASH__', hash))
