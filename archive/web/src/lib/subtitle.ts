/**
 * SRT / WebVTT / ASS-SSA parsing and serialisation.
 *
 * The golden rule of this file: timestamps and structure never reach the model.
 * We hand out `Cue.text` for translation and put the result straight back into
 * the same slot, so a subtitle can never drift out of sync no matter what the
 * model returns.
 */

export type SubFormat = 'srt' | 'vtt' | 'ass'

interface SrtMeta { kind: 'srt'; index: string; timing: string }
interface VttMeta { kind: 'vtt'; id?: string; timing: string }
interface AssMeta { kind: 'ass'; lineIndex: number; prefix: string }

export interface Cue {
  /** Stable index into `Subtitle.cues`; also the id shown to the model. */
  id: number
  /** Original untouched text, including any markup. */
  text: string
  /** Set once translated. */
  translated?: string
  /** Format-specific payload used to rebuild the file faithfully. */
  meta: SrtMeta | VttMeta | AssMeta
}

export interface Subtitle {
  format: SubFormat
  cues: Cue[]
  /** Everything needed by `serialize` that is not a cue. */
  scaffold: string[]
  fileName: string
}

const BOM = /^﻿/

export function detectFormat(fileName: string, content: string): SubFormat {
  const ext = fileName.toLowerCase().split('.').pop()
  if (ext === 'ass' || ext === 'ssa') return 'ass'
  if (ext === 'vtt') return 'vtt'
  if (ext === 'srt') return 'srt'
  // Fall back to sniffing: mis-named files are common in subtitle packs.
  if (/^\s*WEBVTT/.test(content)) return 'vtt'
  if (/^\s*\[Script Info\]/im.test(content)) return 'ass'
  return 'srt'
}

export function parse(fileName: string, raw: string): Subtitle {
  const content = raw.replace(BOM, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const format = detectFormat(fileName, content)
  if (format === 'ass') return parseAss(fileName, content)
  if (format === 'vtt') return parseVtt(fileName, content)
  return parseSrt(fileName, content)
}

/* ------------------------------------------------------------------ SRT */

const SRT_TIMING = /^\s*-?\d{1,3}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*-?\d{1,3}:\d{2}:\d{2}[,.]\d{1,3}/

function parseSrt(fileName: string, content: string): Subtitle {
  const cues: Cue[] = []
  for (const block of content.split(/\n{2,}/)) {
    const lines = block.split('\n')
    while (lines.length && lines[0].trim() === '') lines.shift()
    if (!lines.length) continue

    let cursor = 0
    let index = ''
    if (!SRT_TIMING.test(lines[0]) && lines.length > 1 && SRT_TIMING.test(lines[1])) {
      index = lines[0].trim()
      cursor = 1
    }
    const timingLine = lines[cursor]
    if (!timingLine || !SRT_TIMING.test(timingLine)) continue

    const text = lines.slice(cursor + 1).join('\n').trim()
    if (!text) continue
    cues.push({
      id: cues.length,
      text,
      meta: { kind: 'srt', index: index || String(cues.length + 1), timing: timingLine.trim() },
    })
  }
  return { format: 'srt', cues, scaffold: [], fileName }
}

/* ------------------------------------------------------------------ VTT */

function parseVtt(fileName: string, content: string): Subtitle {
  const cues: Cue[] = []
  const scaffold: string[] = []

  for (const block of content.split(/\n{2,}/)) {
    const lines = block.split('\n').filter((l) => l.trim() !== '')
    if (!lines.length) continue

    const head = lines[0].trim()
    // WEBVTT header plus NOTE / STYLE / REGION blocks pass through untouched.
    if (/^WEBVTT/.test(head) || /^(NOTE|STYLE|REGION)\b/.test(head)) {
      scaffold.push(block.trim())
      continue
    }

    let cursor = 0
    let id: string | undefined
    if (!head.includes('-->') && lines.length > 1 && lines[1].includes('-->')) {
      id = head
      cursor = 1
    }
    const timingLine = lines[cursor]
    if (!timingLine || !timingLine.includes('-->')) continue

    const text = lines.slice(cursor + 1).join('\n').trim()
    if (!text) continue
    cues.push({ id: cues.length, text, meta: { kind: 'vtt', id, timing: timingLine.trim() } })
  }

  if (!scaffold.length) scaffold.push('WEBVTT')
  return { format: 'vtt', cues, scaffold, fileName }
}

/* ------------------------------------------------------------------ ASS */

function parseAss(fileName: string, content: string): Subtitle {
  const lines = content.split('\n')
  const cues: Cue[] = []
  // The Format: line inside [Events] says which comma-separated field holds
  // the text. It is always last, but its position varies between files.
  let textFieldIndex = 9
  let inEvents = false

  lines.forEach((line, lineIndex) => {
    const trimmed = line.trim()
    if (/^\[.*\]$/.test(trimmed)) {
      inEvents = /^\[events\]$/i.test(trimmed)
      return
    }
    if (inEvents && /^Format\s*:/i.test(trimmed)) {
      const fields = trimmed
        .replace(/^Format\s*:/i, '')
        .split(',')
        .map((f) => f.trim().toLowerCase())
      const idx = fields.indexOf('text')
      if (idx >= 0) textFieldIndex = idx
      return
    }
    if (!inEvents || !/^Dialogue\s*:/i.test(trimmed)) return

    const body = line.replace(/^(\s*Dialogue\s*:)/i, '')
    const head = line.slice(0, line.length - body.length)
    // Split on the first `textFieldIndex` commas only — the text itself may
    // contain any number of them.
    const parts = splitN(body, ',', textFieldIndex)
    if (parts.length <= textFieldIndex) return

    const text = parts[textFieldIndex]
    if (!text.trim()) return
    const prefix = head + parts.slice(0, textFieldIndex).join(',') + ','
    cues.push({ id: cues.length, text: text.trim(), meta: { kind: 'ass', lineIndex, prefix } })
  })

  return { format: 'ass', cues, scaffold: lines, fileName }
}

function splitN(input: string, sep: string, n: number): string[] {
  const out: string[] = []
  let rest = input
  for (let i = 0; i < n; i++) {
    const at = rest.indexOf(sep)
    if (at === -1) return [...out, rest]
    out.push(rest.slice(0, at))
    rest = rest.slice(at + 1)
  }
  out.push(rest)
  return out
}

/* ------------------------------------------------------------ serialise */

export function serialize(sub: Subtitle): string {
  const pick = (c: Cue) => c.translated ?? c.text

  if (sub.format === 'srt') {
    const body = sub.cues
      .map((c, i) => {
        const m = c.meta as SrtMeta
        return i + 1 + '\n' + m.timing + '\n' + pick(c)
      })
      .join('\n\n')
    return body + '\n'
  }

  if (sub.format === 'vtt') {
    const head = sub.scaffold.join('\n\n')
    const body = sub.cues
      .map((c) => {
        const m = c.meta as VttMeta
        return (m.id ? m.id + '\n' : '') + m.timing + '\n' + pick(c)
      })
      .join('\n\n')
    return head + '\n\n' + body + '\n'
  }

  // ASS: rebuild the original file and swap only the Dialogue text fields.
  const lines = [...sub.scaffold]
  for (const c of sub.cues) {
    const m = c.meta as AssMeta
    lines[m.lineIndex] = m.prefix + pick(c).replace(/\n/g, '\\N')
  }
  return lines.join('\n')
}

/** `Boruto E01.en.srt` + `bn` -> `Boruto E01.bn.srt` */
export function outputName(fileName: string, langTag: string): string {
  const dot = fileName.lastIndexOf('.')
  const ext = dot === -1 ? 'srt' : fileName.slice(dot + 1)
  let stem = dot === -1 ? fileName : fileName.slice(0, dot)
  // Drop a trailing language tag if the source already carried one.
  stem = stem.replace(/\.(en|eng|english|jp|jpn|ja|hi|hin|es|fr|ar|id|pt|ru|ko|zh)$/i, '')
  return stem + '.' + langTag + '.' + ext
}
