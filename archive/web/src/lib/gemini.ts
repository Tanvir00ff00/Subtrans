/**
 * Minimal Gemini client that runs straight from the browser.
 *
 * The key lives in this user's localStorage and is sent only to
 * generativelanguage.googleapis.com — there is no backend in this app.
 */

const ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/models'

export interface ModelInfo {
  id: string
  label: string
  hint?: string
}

/**
 * A starting list only. Google adds and retires models faster than a hard-coded
 * list can track, so the settings screen can replace this with the real list
 * from the user's own key — see `fetchModels`.
 */
export const MODELS: ModelInfo[] = [
  { id: 'gemini-3.5-flash-lite', label: 'Gemini 3.5 Flash-Lite', hint: 'নতুন লাইট মডেল — দ্রুত ও সস্তা' },
  { id: 'gemini-3.1-flash-lite', label: 'Gemini 3.1 Flash-Lite', hint: 'নতুন লাইট মডেল — দ্রুত ও সস্তা' },
  { id: 'gemini-2.5-flash', label: 'Gemini 2.5 Flash', hint: 'ভারসাম্য — দৈনন্দিন কাজের জন্য সেরা' },
  { id: 'gemini-2.5-flash-lite', label: 'Gemini 2.5 Flash-Lite', hint: 'সবচেয়ে দ্রুত ও সস্তা' },
  { id: 'gemini-2.5-pro', label: 'Gemini 2.5 Pro', hint: 'সর্বোচ্চ মান, ধীর, কোটা কম' },
]

export interface JsonSchema {
  type: string
  items?: JsonSchema
  properties?: Record<string, JsonSchema>
  required?: string[]
  description?: string
}

export interface GenerateOptions {
  apiKey: string
  model: string
  system: string
  user: string
  schema?: JsonSchema
  temperature?: number
  /** 0 disables the model's internal reasoning pass — much faster for translation. */
  thinkingBudget?: number
  signal?: AbortSignal
}

export class GeminiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly retryable: boolean,
    readonly retryAfterMs?: number,
  ) {
    super(message)
    this.name = 'GeminiError'
  }
}

interface GeminiResponse {
  candidates?: Array<{
    content?: { parts?: Array<{ text?: string }> }
    finishReason?: string
  }>
  promptFeedback?: { blockReason?: string }
  error?: { message?: string; status?: string }
}

function buildBody(opts: GenerateOptions, withThinking: boolean): Record<string, unknown> {
  return {
    systemInstruction: { parts: [{ text: opts.system }] },
    contents: [{ role: 'user', parts: [{ text: opts.user }] }],
    generationConfig: {
      temperature: opts.temperature ?? 0.3,
      ...(opts.schema
        ? { responseMimeType: 'application/json', responseSchema: opts.schema }
        : {}),
      ...(withThinking && opts.thinkingBudget !== undefined
        ? { thinkingConfig: { thinkingBudget: opts.thinkingBudget } }
        : {}),
    },
    // Translation of fiction routinely trips the default filters (violence in
    // an action anime, for instance). Keep them at the most permissive setting
    // the API allows so a single line cannot stall a whole season.
    safetySettings: [
      'HARM_CATEGORY_HARASSMENT',
      'HARM_CATEGORY_HATE_SPEECH',
      'HARM_CATEGORY_SEXUALLY_EXPLICIT',
      'HARM_CATEGORY_DANGEROUS_CONTENT',
    ].map((category) => ({ category, threshold: 'BLOCK_ONLY_HIGH' })),
  }
}

async function post(opts: GenerateOptions, body: unknown): Promise<Response> {
  try {
    return await fetch(`${ENDPOINT}/${encodeURIComponent(opts.model)}:generateContent`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': opts.apiKey },
      body: JSON.stringify(body),
      signal: opts.signal,
    })
  } catch (err) {
    if (opts.signal?.aborted) throw err
    throw new GeminiError('নেটওয়ার্কে পৌঁছানো গেল না', 0, true)
  }
}

export async function generate(opts: GenerateOptions): Promise<string> {
  const wantsThinkingControl = opts.thinkingBudget !== undefined
  let res = await post(opts, buildBody(opts, wantsThinkingControl))

  // `thinkingConfig` is not accepted by every model: Pro models refuse to turn
  // reasoning off, and newer families control it through a different field
  // entirely. Both come back as a 400, so rather than maintaining a table of
  // which model takes which knob, drop the knob once and try again.
  if (!res.ok && res.status === 400 && wantsThinkingControl) {
    res = await post(opts, buildBody(opts, false))
  }

  if (!res.ok) {
    const detail = await res.text().catch(() => '')
    let message = detail.slice(0, 300)
    try {
      const parsed = JSON.parse(detail) as GeminiResponse
      if (parsed.error?.message) message = parsed.error.message
    } catch {
      /* keep the raw body */
    }
    if (res.status === 404) {
      message = `"${opts.model}" নামে কোনো মডেল পাওয়া যায়নি। সেটিংসে "তালিকা আনো" চেপে দেখো তোমার key-তে কোনগুলো আছে।`
    }
    const retryAfter = Number(res.headers.get('retry-after'))
    throw new GeminiError(
      message || `HTTP ${res.status}`,
      res.status,
      res.status === 429 || res.status === 500 || res.status === 502 || res.status === 503,
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : undefined,
    )
  }

  const data = (await res.json()) as GeminiResponse
  if (data.promptFeedback?.blockReason) {
    throw new GeminiError(`রিকোয়েস্ট ব্লক হয়েছে (${data.promptFeedback.blockReason})`, 200, false)
  }

  const candidate = data.candidates?.[0]
  const text = candidate?.content?.parts?.map((p) => p.text ?? '').join('') ?? ''
  if (!text) {
    const reason = candidate?.finishReason ?? 'EMPTY'
    // MAX_TOKENS means the batch was too large; the caller can split and retry.
    throw new GeminiError(`মডেল খালি উত্তর দিয়েছে (${reason})`, 200, reason === 'MAX_TOKENS')
  }
  return text
}

/** Cheap credential check used by the settings screen. */
export async function verifyKey(apiKey: string, model: string): Promise<void> {
  await generate({
    apiKey,
    model,
    system: 'Reply with the single word OK.',
    user: 'ping',
    temperature: 0,
    thinkingBudget: 0,
  })
}

/* ------------------------------------------------------------ discovery */

interface ListedModel {
  name?: string
  displayName?: string
  description?: string
  supportedGenerationMethods?: string[]
  supportedActions?: string[]
}

/**
 * Asks the API which models this key can actually call, so the model list is
 * never a guess. Only text models that support generateContent are returned.
 */
export async function fetchModels(apiKey: string, signal?: AbortSignal): Promise<ModelInfo[]> {
  const found: ModelInfo[] = []
  let pageToken = ''

  for (let page = 0; page < 10; page++) {
    const url = new URL(ENDPOINT)
    url.searchParams.set('pageSize', '200')
    if (pageToken) url.searchParams.set('pageToken', pageToken)

    let res: Response
    try {
      res = await fetch(url, { headers: { 'x-goog-api-key': apiKey }, signal })
    } catch {
      throw new GeminiError('নেটওয়ার্কে পৌঁছানো গেল না', 0, true)
    }
    if (!res.ok) {
      const detail = await res.text().catch(() => '')
      let message = detail.slice(0, 200)
      try {
        const parsed = JSON.parse(detail) as GeminiResponse
        if (parsed.error?.message) message = parsed.error.message
      } catch {
        /* keep the raw body */
      }
      throw new GeminiError(message || `HTTP ${res.status}`, res.status, false)
    }

    const data = (await res.json()) as { models?: ListedModel[]; nextPageToken?: string }
    for (const m of data.models ?? []) {
      const id = (m.name ?? '').replace(/^models\//, '')
      if (!id) continue
      const methods = m.supportedGenerationMethods ?? m.supportedActions ?? []
      if (methods.length && !methods.includes('generateContent')) continue
      // Embedding and image models cannot translate anything.
      if (/embedding|aqa|imagen|veo|tts|image|audio|native-audio/i.test(id)) continue
      found.push({ id, label: m.displayName?.trim() || id, hint: shortHint(m.description) })
    }

    pageToken = data.nextPageToken ?? ''
    if (!pageToken) break
  }

  // Newest families first: gemini-3.x above gemini-2.5, stable above preview.
  return found.sort((a, b) => {
    const preview = (s: string) => (/preview|exp|latest/i.test(s) ? 1 : 0)
    if (preview(a.id) !== preview(b.id)) return preview(a.id) - preview(b.id)
    return b.id.localeCompare(a.id, 'en', { numeric: true })
  })
}

function shortHint(description?: string): string | undefined {
  if (!description) return undefined
  const first = description.split(/(?<=\.)\s/)[0].trim()
  return first.length > 90 ? first.slice(0, 87) + '…' : first
}
