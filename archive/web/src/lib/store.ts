/**
 * Everything that must outlive a page reload. All of it stays on this device:
 * localStorage only, no backend, no telemetry.
 */

import type { GlossaryEntry } from './translate'

const SETTINGS_KEY = 'subtrans.settings.v1'
const GLOSSARY_KEY = 'subtrans.glossaries.v1'

export interface Settings {
  apiKey: string
  model: string
  targetLanguage: string
  languageTag: string
  tone: string
  batchSize: number
  concurrency: number
  contextLines: number
  fastMode: boolean
  /** OpenSubtitles API key — only needed for the search tab. */
  osApiKey: string
  /** Optional OpenSubtitles JWT; raises the daily download quota. */
  osToken: string
  /** Language to fetch subtitles in before translating. */
  sourceLanguage: string
}

export const DEFAULT_SETTINGS: Settings = {
  apiKey: '',
  model: 'gemini-2.5-flash',
  targetLanguage: 'Bengali (বাংলা)',
  languageTag: 'bn',
  tone: 'স্বাভাবিক কথ্য বাংলা, চরিত্রের বয়স ও সম্পর্ক অনুযায়ী তুমি/তুই/আপনি',
  batchSize: 45,
  concurrency: 4,
  contextLines: 3,
  fastMode: true,
  osApiKey: '',
  osToken: '',
  sourceLanguage: 'en',
}

export function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY)
    if (!raw) return { ...DEFAULT_SETTINGS }
    return { ...DEFAULT_SETTINGS, ...(JSON.parse(raw) as Partial<Settings>) }
  } catch {
    return { ...DEFAULT_SETTINGS }
  }
}

export function saveSettings(settings: Settings): void {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings))
  } catch {
    /* private window or storage disabled — the app still works, just forgets */
  }
}

export type GlossaryMap = Record<string, GlossaryEntry[]>

export function loadGlossaries(): GlossaryMap {
  try {
    const raw = localStorage.getItem(GLOSSARY_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as GlossaryMap
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

export function saveGlossaries(map: GlossaryMap): void {
  try {
    localStorage.setItem(GLOSSARY_KEY, JSON.stringify(map))
  } catch {
    /* ignore */
  }
}

/**
 * Guesses the series a file belongs to so its glossary is picked up
 * automatically: `[SubsPlease] Boruto - 042 (1080p).en.srt` -> `Boruto`.
 */
export function guessSeries(fileName: string): string {
  let name = fileName.replace(/\.[a-z0-9]{1,4}$/i, '')
  name = name.replace(/^\[[^\]]*\]\s*/, '')          // release group tag
  name = name.replace(/[._]+/g, ' ')
  // Cut at the first episode marker we recognise.
  const cut = name.search(
    /\s*(-\s*\d{1,4}\b|\bS\d{1,2}\s*E\d{1,3}\b|\bE\d{1,3}\b|\bEp(isode)?\.?\s*\d{1,4}\b|\b\d{3,4}p\b|\(\d{4}\))/i,
  )
  if (cut > 0) name = name.slice(0, cut)
  return name.trim().replace(/\s{2,}/g, ' ') || 'Default'
}
