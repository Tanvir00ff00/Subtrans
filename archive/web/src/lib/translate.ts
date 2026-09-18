/**
 * The translation engine.
 *
 * Three things go wrong when you paste subtitles into a chat window, and this
 * file exists to stop each of them:
 *
 *   1. Lines come back in the wrong number, so every timestamp after the gap
 *      is wrong. -> Cues travel with explicit ids and are re-seated by id; a
 *      batch that loses ids is split and retried, never accepted as-is.
 *   2. Names drift between episodes. -> A per-series glossary is pinned into
 *      every request.
 *   3. It takes forever. -> Batches run concurrently with backoff-aware
 *      retries instead of one line at a time.
 */

import { generate, GeminiError, type JsonSchema } from './gemini'
import { protect, restore, scrub, tokensIntact } from './markup'
import type { Cue, Subtitle } from './subtitle'

export interface GlossaryEntry {
  source: string
  target: string
  note?: string
}

export interface TranslateOptions {
  apiKey: string
  model: string
  targetLanguage: string
  /** Free-form register guidance, e.g. "কথ্য বাংলা, তুই-তোকারি". */
  tone: string
  glossary: GlossaryEntry[]
  /** Shown to the model as background, e.g. "Boruto: Naruto Next Generations". */
  seriesName?: string
  batchSize: number
  concurrency: number
  contextLines: number
  fastMode: boolean
  signal?: AbortSignal
}

export interface CueProgress {
  done: number
  total: number
  failed: number
}

const RESPONSE_SCHEMA: JsonSchema = {
  type: 'ARRAY',
  items: {
    type: 'OBJECT',
    properties: {
      id: { type: 'INTEGER', description: 'The id copied verbatim from the input line.' },
      t: { type: 'STRING', description: 'The translated text for that line.' },
    },
    required: ['id', 't'],
  },
}

const MAX_ATTEMPTS = 4

/* --------------------------------------------------------------- prompts */

export function buildSystemPrompt(opts: TranslateOptions): string {
  const glossary = opts.glossary.length
    ? opts.glossary
        .map((g) => `- ${g.source} => ${g.target}${g.note ? `  (${g.note})` : ''}`)
        .join('\n')
    : '(none supplied)'

  return [
    `You are a professional subtitle translator. You translate into ${opts.targetLanguage}.`,
    opts.seriesName ? `The material is from: ${opts.seriesName}.` : '',
    '',
    'INPUT: a JSON array of objects {"id": number, "s": string}.',
    'OUTPUT: a JSON array of objects {"id": number, "t": string}.',
    '',
    'Hard rules — breaking any of these corrupts the subtitle file:',
    '1. Return EXACTLY one object per input object. Never merge, split, drop, reorder or invent lines.',
    '2. Copy each "id" back verbatim. The id is how the line is re-attached to its timestamp.',
    '3. Preserve every placeholder character sequence (U+E000 digits U+E001) exactly as received, in the same order. They stand for styling tags.',
    '4. Translate only. Never add notes, romanisation in brackets, explanations, or quotation marks that were not there.',
    '5. Keep the internal line break structure of each line (a "\\n" inside a line stays a "\\n").',
    '6. If a line is only a sound effect, a name card, or untranslatable, return the closest natural equivalent rather than leaving it in the source language.',
    '',
    'Style:',
    `- Register: ${opts.tone || 'natural, conversational, the way people actually speak'}.`,
    '- Subtitles are read in about two seconds. Prefer short, idiomatic phrasing over literal word-order.',
    '- Keep the emotional temperature of the original: shouting stays urgent, muttering stays flat.',
    '- Keep interjections and stammers as equivalent interjections, not as descriptions of them.',
    '- Use the target language script. Only keep proper nouns in Latin script when the glossary says so.',
    '',
    'Glossary — these translations are mandatory and must be identical every time:',
    glossary,
  ]
    .filter(Boolean)
    .join('\n')
}

function buildUserPrompt(
  batch: Array<{ id: number; s: string }>,
  context: Array<{ s: string; t: string }>,
): string {
  const parts: string[] = []
  if (context.length) {
    parts.push(
      'Lines immediately before this batch, already translated. They are for continuity only — do NOT translate or return them:',
      context.map((c) => `${c.s}  =>  ${c.t}`).join('\n'),
      '',
    )
  }
  parts.push('Translate every line below and return the JSON array:')
  parts.push(JSON.stringify(batch))
  return parts.join('\n')
}

/* ------------------------------------------------------------ one batch */

interface BatchItem {
  cue: Cue
  protectedText: string
  tokens: string[]
}

async function translateBatch(
  items: BatchItem[],
  context: Array<{ s: string; t: string }>,
  system: string,
  opts: TranslateOptions,
  depth = 0,
): Promise<void> {
  const payload = items.map((it) => ({ id: it.cue.id, s: it.protectedText }))
  let raw: string

  let lastError: unknown
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    try {
      raw = await generate({
        apiKey: opts.apiKey,
        model: opts.model,
        system,
        user: buildUserPrompt(payload, context),
        schema: RESPONSE_SCHEMA,
        temperature: 0.3,
        thinkingBudget: opts.fastMode ? 0 : undefined,
        signal: opts.signal,
      })
      lastError = undefined
      break
    } catch (err) {
      lastError = err
      if (opts.signal?.aborted) throw err
      const retryable = err instanceof GeminiError ? err.retryable : false
      if (!retryable || attempt === MAX_ATTEMPTS - 1) break
      const backoff =
        err instanceof GeminiError && err.retryAfterMs
          ? err.retryAfterMs
          : 800 * Math.pow(2, attempt) + Math.random() * 400
      await sleep(backoff, opts.signal)
    }
  }
  if (lastError) throw lastError

  const map = parseResponse(raw!)

  const missing: BatchItem[] = []
  for (const item of items) {
    const value = map.get(item.cue.id)
    if (value === undefined || value.trim() === '') {
      missing.push(item)
      continue
    }
    // A translation that kept its styling tokens gets them back verbatim. One
    // that dropped them is still perfectly usable text, so we take it rather
    // than burning a retry — but an ASS line whose override block carried the
    // on-screen position must keep that block or it will render in the wrong
    // place, so we re-attach a lost leading token.
    let out = restore(value, item.tokens)
    if (item.tokens.length && !tokensIntact(value, item.tokens)) {
      const leadingToken = leadingTokenOf(item)
      if (leadingToken && !out.startsWith(leadingToken)) out = leadingToken + out
    }
    item.cue.translated = scrub(out).trim()
  }

  if (!missing.length) return

  // The model skipped ids. Halve the batch and try the stragglers again rather
  // than accepting a hole, which would desynchronise everything after it.
  if (depth >= 3 || missing.length === items.length && items.length === 1) {
    for (const item of missing) {
      item.cue.translated = undefined
    }
    return
  }

  const mid = Math.ceil(missing.length / 2)
  const halves = missing.length > 1 ? [missing.slice(0, mid), missing.slice(mid)] : [missing]
  for (const half of halves) {
    await translateBatch(half, context, system, opts, depth + 1)
  }
}

/** The styling token a line opened with, if it opened with one. */
function leadingTokenOf(item: BatchItem): string | undefined {
  const match = item.protectedText.match(
    new RegExp('^' + String.fromCharCode(0xe000) + '(\\d+)' + String.fromCharCode(0xe001)),
  )
  if (!match) return undefined
  return item.tokens[Number(match[1])]
}

function parseResponse(raw: string): Map<number, string> {
  const map = new Map<number, string>()
  let text = raw.trim()
  // responseSchema usually guarantees clean JSON, but fenced output still slips
  // through occasionally.
  if (text.startsWith('```')) text = text.replace(/^```[a-z]*\n?/i, '').replace(/```$/, '').trim()

  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    const start = text.indexOf('[')
    const end = text.lastIndexOf(']')
    if (start === -1 || end <= start) return map
    try {
      parsed = JSON.parse(text.slice(start, end + 1))
    } catch {
      return map
    }
  }

  if (!Array.isArray(parsed)) return map
  for (const row of parsed) {
    if (!row || typeof row !== 'object') continue
    const id = Number((row as { id?: unknown }).id)
    const t = (row as { t?: unknown }).t
    if (!Number.isFinite(id) || typeof t !== 'string') continue
    map.set(id, t)
  }
  return map
}

/* ------------------------------------------------------------ whole file */

export async function translateSubtitle(
  sub: Subtitle,
  opts: TranslateOptions,
  onProgress?: (p: CueProgress) => void,
): Promise<CueProgress> {
  const system = buildSystemPrompt(opts)

  const items: BatchItem[] = sub.cues.map((cue) => {
    const p = protect(cue.text)
    return { cue, protectedText: p.text, tokens: p.tokens }
  })

  const batches: BatchItem[][] = []
  for (let i = 0; i < items.length; i += opts.batchSize) {
    batches.push(items.slice(i, i + opts.batchSize))
  }

  let done = 0
  const report = () => {
    const failed = items.filter((it) => it.cue.translated === undefined).length
    onProgress?.({ done, total: items.length, failed: done === items.length ? failed : 0 })
  }
  report()

  let next = 0
  const worker = async () => {
    while (next < batches.length) {
      if (opts.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
      const index = next++
      const batch = batches[index]
      // Context comes from the preceding batch. Under concurrency it may not be
      // translated yet, in which case we fall back to source-only context.
      const before = items.slice(Math.max(0, index * opts.batchSize - opts.contextLines), index * opts.batchSize)
      const context = before
        .filter((it) => it.cue.translated)
        .slice(-opts.contextLines)
        .map((it) => ({ s: it.cue.text, t: it.cue.translated! }))

      await translateBatch(batch, context, system, opts)
      done += batch.length
      report()
    }
  }

  const pool = Array.from({ length: Math.max(1, Math.min(opts.concurrency, batches.length)) }, worker)
  await Promise.all(pool)

  const failed = items.filter((it) => it.cue.translated === undefined).length
  const result = { done: items.length, total: items.length, failed }
  onProgress?.(result)
  return result
}

/* ------------------------------------------------------------- glossary */

const GLOSSARY_SCHEMA: JsonSchema = {
  type: 'ARRAY',
  items: {
    type: 'OBJECT',
    properties: {
      source: { type: 'STRING', description: 'The term as it appears in the source.' },
      target: { type: 'STRING', description: 'How it should be written in the target language.' },
      note: { type: 'STRING', description: 'Very short note: who or what this is.' },
    },
    required: ['source', 'target'],
  },
}

/**
 * Reads a sample of the file and proposes the recurring proper nouns — the
 * character names, techniques and places that must not drift between episodes.
 */
export async function suggestGlossary(
  sub: Subtitle,
  opts: Pick<TranslateOptions, 'apiKey' | 'model' | 'targetLanguage' | 'seriesName' | 'signal'>,
): Promise<GlossaryEntry[]> {
  const sample = sub.cues.slice(0, 400).map((c) => scrub(protect(c.text).text)).join('\n')
  if (!sample.trim()) return []

  const raw = await generate({
    apiKey: opts.apiKey,
    model: opts.model,
    system: [
      `You build translation glossaries for subtitles going into ${opts.targetLanguage}.`,
      opts.seriesName ? `Material: ${opts.seriesName}.` : '',
      'From the sample, list only recurring proper nouns: character names, place names, named techniques, organisations, and honorific suffixes that matter.',
      'Skip ordinary vocabulary, skip anything appearing once in passing, and cap the list at 40 entries.',
      `For each, give the spelling to use in ${opts.targetLanguage}, chosen so it reads naturally and stays identical across every episode.`,
    ]
      .filter(Boolean)
      .join('\n'),
    user: 'Subtitle sample:\n\n' + sample,
    schema: GLOSSARY_SCHEMA,
    temperature: 0.1,
    thinkingBudget: 0,
    signal: opts.signal,
  })

  try {
    const parsed = JSON.parse(raw) as GlossaryEntry[]
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((e) => e && typeof e.source === 'string' && typeof e.target === 'string' && e.source.trim())
      .map((e) => ({ source: e.source.trim(), target: e.target.trim(), note: e.note?.trim() }))
  } catch {
    return []
  }
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    signal?.addEventListener(
      'abort',
      () => {
        clearTimeout(timer)
        reject(new DOMException('Aborted', 'AbortError'))
      },
      { once: true },
    )
  })
}
