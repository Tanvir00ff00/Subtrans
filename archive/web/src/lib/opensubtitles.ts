/**
 * OpenSubtitles REST API client, browser-side.
 *
 * Verified from a page origin: the API sends CORS headers, so no proxy is
 * needed. Two things the docs will not tell you and that shape this file:
 *
 *   - Browsers forbid setting `User-Agent`, which the docs list as required.
 *     In practice the key is what identifies you; if a request is refused the
 *     error is surfaced verbatim rather than swallowed, so the cause is visible.
 *   - Downloads are quota'd per day. `requestDownload` returns what is left,
 *     and the UI shows it before a bulk run starts rather than after.
 */

const BASE = 'https://api.opensubtitles.com/api/v1'

export class OpenSubtitlesError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly quotaExhausted = false,
  ) {
    super(message)
    this.name = 'OpenSubtitlesError'
  }
}

export interface Credentials {
  apiKey: string
  /** Optional JWT from an OpenSubtitles login; raises the download quota. */
  token?: string
}

export interface Show {
  featureId: number
  title: string
  year?: string
  seasons?: number
  imdbId?: string
}

export interface SubtitleFile {
  fileId: number
  fileName: string
  release: string
  language: string
  season?: number
  episode?: number
  episodeTitle?: string
  downloads: number
  hearingImpaired: boolean
  fromTrusted: boolean
}

export interface DownloadTicket {
  link: string
  fileName: string
  /** Downloads left today, as reported by the API. */
  remaining: number
  resetTime?: string
}

/* ---------------------------------------------------------------- request */

async function call<T>(
  path: string,
  creds: Credentials,
  init: RequestInit = {},
): Promise<T> {
  const headers: Record<string, string> = {
    'Api-Key': creds.apiKey,
    Accept: 'application/json',
    ...(init.body ? { 'Content-Type': 'application/json' } : {}),
    ...(creds.token ? { Authorization: `Bearer ${creds.token}` } : {}),
  }

  let res: Response
  try {
    res = await fetch(BASE + path, { ...init, headers })
  } catch {
    throw new OpenSubtitlesError('OpenSubtitles-এ পৌঁছানো গেল না — ইন্টারনেট দেখো', 0)
  }

  if (!res.ok) {
    const raw = await res.text().catch(() => '')
    let message = raw.slice(0, 200)
    try {
      const parsed = JSON.parse(raw) as { message?: string; errors?: string[] }
      message = parsed.message ?? parsed.errors?.join(', ') ?? message
    } catch {
      /* keep the raw body */
    }
    throw new OpenSubtitlesError(explain(res.status, message), res.status, res.status === 406)
  }

  return (await res.json()) as T
}

function explain(status: number, message: string): string {
  if (status === 401) return 'টোকেনটা গ্রহণ করা হয়নি — সেটিংসে গিয়ে ঠিক করো।'
  if (status === 403) return `API key গ্রহণ করা হয়নি (${message})। সেটিংসে key-টা আবার দেখে নাও।`
  if (status === 406) return 'আজকের ডাউনলোড কোটা শেষ। কাল আবার চেষ্টা করো, বা VIP অ্যাকাউন্ট নাও।'
  if (status === 429) return 'একটু বেশি দ্রুত চাওয়া হয়েছে — কিছুক্ষণ পরে আবার চেষ্টা করো।'
  return message || `HTTP ${status}`
}

/* ----------------------------------------------------------------- search */

interface FeatureResponse {
  data?: Array<{
    id?: string
    attributes?: {
      title?: string
      year?: string
      feature_type?: string
      imdb_id?: string | number
      feature_id?: string | number
      seasons_count?: number
    }
  }>
}

/** Finds TV series matching a name, e.g. "Boruto". */
export async function searchShows(query: string, creds: Credentials): Promise<Show[]> {
  const url = `/features?query=${encodeURIComponent(query)}&type=tv`
  const data = await call<FeatureResponse>(url, creds)

  const shows: Show[] = []
  for (const row of data.data ?? []) {
    const a = row.attributes
    if (!a) continue
    const featureId = Number(a.feature_id ?? row.id)
    if (!Number.isFinite(featureId)) continue
    shows.push({
      featureId,
      title: a.title ?? 'অজানা',
      year: a.year,
      seasons: a.seasons_count,
      imdbId: a.imdb_id ? String(a.imdb_id) : undefined,
    })
  }
  return shows
}

interface SubtitleResponse {
  total_pages?: number
  page?: number
  data?: Array<{
    attributes?: {
      language?: string
      download_count?: number
      hearing_impaired?: boolean
      from_trusted?: boolean
      release?: string
      files?: Array<{ file_id?: number; file_name?: string }>
      feature_details?: {
        season_number?: number
        episode_number?: number
        title?: string
      }
    }
  }>
}

export interface SeasonQuery {
  featureId: number
  season?: number
  language: string
  /** Skip subtitles written for the deaf and hard of hearing. */
  excludeHearingImpaired?: boolean
  onPage?: (page: number, totalPages: number) => void
}

/**
 * Lists every subtitle for a season. The API pages at 100 a time, so a long
 * running series takes a handful of round trips.
 */
export async function listSeasonSubtitles(
  q: SeasonQuery,
  creds: Credentials,
): Promise<SubtitleFile[]> {
  const found: SubtitleFile[] = []
  let page = 1
  let totalPages = 1

  while (page <= totalPages && page <= 20) {
    const params = new URLSearchParams({
      parent_feature_id: String(q.featureId),
      languages: q.language,
      per_page: '100',
      page: String(page),
      order_by: 'download_count',
      order_direction: 'desc',
    })
    if (q.season !== undefined) params.set('season_number', String(q.season))

    const data = await call<SubtitleResponse>('/subtitles?' + params.toString(), creds)
    totalPages = data.total_pages ?? 1
    q.onPage?.(page, totalPages)

    for (const row of data.data ?? []) {
      const a = row.attributes
      const file = a?.files?.[0]
      if (!a || !file?.file_id) continue
      if (q.excludeHearingImpaired && a.hearing_impaired) continue
      found.push({
        fileId: file.file_id,
        fileName: file.file_name ?? a.release ?? `subtitle-${file.file_id}.srt`,
        release: a.release ?? '',
        language: a.language ?? q.language,
        season: a.feature_details?.season_number,
        episode: a.feature_details?.episode_number,
        episodeTitle: a.feature_details?.title,
        downloads: a.download_count ?? 0,
        hearingImpaired: Boolean(a.hearing_impaired),
        fromTrusted: Boolean(a.from_trusted),
      })
    }

    page += 1
  }

  return found
}

/**
 * Collapses many candidates per episode down to the single best one: trusted
 * uploads first, then whatever the most people have downloaded.
 */
export function pickBestPerEpisode(files: SubtitleFile[]): Map<number, SubtitleFile> {
  const best = new Map<number, SubtitleFile>()
  for (const file of files) {
    if (file.episode === undefined) continue
    const current = best.get(file.episode)
    if (!current || score(file) > score(current)) best.set(file.episode, file)
  }
  return best
}

function score(f: SubtitleFile): number {
  return (f.fromTrusted ? 1_000_000 : 0) + f.downloads - (f.hearingImpaired ? 500 : 0)
}

/* --------------------------------------------------------------- download */

interface DownloadResponse {
  link?: string
  file_name?: string
  remaining?: number
  requests?: number
  reset_time?: string
  message?: string
}

/** Spends one download from the daily quota and returns a short-lived link. */
export async function requestDownload(
  fileId: number,
  creds: Credentials,
): Promise<DownloadTicket> {
  const data = await call<DownloadResponse>('/download', creds, {
    method: 'POST',
    body: JSON.stringify({ file_id: fileId }),
  })
  if (!data.link) {
    throw new OpenSubtitlesError(data.message ?? 'ডাউনলোড লিংক পাওয়া গেল না', 200)
  }
  return {
    link: data.link,
    fileName: data.file_name ?? `subtitle-${fileId}.srt`,
    remaining: data.remaining ?? 0,
    resetTime: data.reset_time,
  }
}

/**
 * Reads the subtitle text from the ticket link.
 *
 * The link lives on a different host than the API, and whether it allows a
 * cross-origin read is not something the docs promise. If it refuses, the
 * caller is told plainly so it can fall back to handing the user the link.
 */
export async function fetchSubtitleText(link: string): Promise<string> {
  let res: Response
  try {
    res = await fetch(link)
  } catch {
    throw new OpenSubtitlesError('CORS_BLOCKED', 0)
  }
  if (!res.ok) throw new OpenSubtitlesError(`ফাইল নামানো গেল না (HTTP ${res.status})`, res.status)
  return await res.text()
}

/** How many downloads the key has left today, without spending one. */
export async function fetchQuota(creds: Credentials): Promise<number | undefined> {
  try {
    const data = await call<{ data?: { remaining_downloads?: number } }>('/infos/user', creds)
    return data.data?.remaining_downloads
  } catch {
    // /infos/user needs a logged-in token; an anonymous key simply cannot ask.
    return undefined
  }
}
