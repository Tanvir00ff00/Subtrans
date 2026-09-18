import { useCallback, useMemo, useRef, useState } from 'react'
import JSZip from 'jszip'
import { outputName, parse, serialize, type Subtitle } from '../lib/subtitle'
import { suggestGlossary, translateSubtitle, type GlossaryEntry } from '../lib/translate'
import { guessSeries, type Settings } from '../lib/store'

type JobStatus = 'queued' | 'running' | 'done' | 'error' | 'stopped'

interface Job {
  id: string
  fileName: string
  sub?: Subtitle
  status: JobStatus
  done: number
  total: number
  failed: number
  error?: string
}

interface Props {
  settings: Settings
  series: string
  onSeriesChange: (name: string) => void
  glossary: GlossaryEntry[]
  onGlossaryChange: (series: string, entries: GlossaryEntry[]) => void
  onNeedSettings: () => void
}

const ACCEPTED = '.srt,.vtt,.ass,.ssa,.sub,.txt'

export default function Translator({
  settings,
  series,
  onSeriesChange,
  glossary,
  onGlossaryChange,
  onNeedSettings,
}: Props) {
  const [jobs, setJobs] = useState<Job[]>([])
  const [running, setRunning] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [notice, setNotice] = useState<string>()
  const [buildingGlossary, setBuildingGlossary] = useState(false)
  const abortRef = useRef<AbortController>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const configured = settings.apiKey.trim().length > 0
  const finished = jobs.filter((j) => j.status === 'done')
  const pending = jobs.filter((j) => j.status === 'queued' || j.status === 'stopped')

  const patch = useCallback((id: string, next: Partial<Job>) => {
    setJobs((prev) => prev.map((j) => (j.id === id ? { ...j, ...next } : j)))
  }, [])

  /* --------------------------------------------------------- adding files */

  const addFiles = useCallback(
    async (files: FileList | File[]) => {
      const list = Array.from(files)
      if (!list.length) return
      setNotice(undefined)

      const parsed = await Promise.all(
        list.map(async (file): Promise<Job> => {
          const id = `${file.name}:${file.size}:${file.lastModified}`
          try {
            const sub = parse(file.name, await readFile(file))
            if (!sub.cues.length) {
              return { id, fileName: file.name, status: 'error', done: 0, total: 0, failed: 0, error: 'কোনো সংলাপ পাওয়া যায়নি — ফাইলটা কি সত্যিই সাবটাইটেল?' }
            }
            return { id, fileName: file.name, sub, status: 'queued', done: 0, total: sub.cues.length, failed: 0 }
          } catch {
            return { id, fileName: file.name, status: 'error', done: 0, total: 0, failed: 0, error: 'ফাইলটা পড়া গেল না' }
          }
        }),
      )

      setJobs((prev) => {
        const seen = new Set(prev.map((j) => j.id))
        return [...prev, ...parsed.filter((j) => !seen.has(j.id))]
      })

      if (!series) {
        const first = parsed.find((j) => j.sub)
        if (first) onSeriesChange(guessSeries(first.fileName))
      }
    },
    [series, onSeriesChange],
  )

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault()
      setDragging(false)
      void addFiles(e.dataTransfer.files)
    },
    [addFiles],
  )

  /* ------------------------------------------------------------- running */

  const start = useCallback(async () => {
    if (!configured) return onNeedSettings()
    const queue = jobs.filter((j) => j.status === 'queued' || j.status === 'stopped')
    if (!queue.length) return

    const controller = new AbortController()
    abortRef.current = controller
    setRunning(true)
    setNotice(undefined)

    const opts = {
      apiKey: settings.apiKey,
      model: settings.model,
      targetLanguage: settings.targetLanguage,
      tone: settings.tone,
      glossary,
      seriesName: series || undefined,
      batchSize: settings.batchSize,
      concurrency: settings.concurrency,
      contextLines: settings.contextLines,
      fastMode: settings.fastMode,
      signal: controller.signal,
    }

    for (const job of queue) {
      if (controller.signal.aborted) break
      if (!job.sub) continue
      patch(job.id, { status: 'running', done: 0, failed: 0 })
      try {
        const result = await translateSubtitle(job.sub, opts, (p) =>
          patch(job.id, { done: p.done, total: p.total, failed: p.failed }),
        )
        patch(job.id, { status: 'done', done: result.total, total: result.total, failed: result.failed })
      } catch (err) {
        if (controller.signal.aborted) {
          patch(job.id, { status: 'stopped' })
          break
        }
        patch(job.id, { status: 'error', error: messageOf(err) })
        // A bad key or an exhausted quota will fail every remaining file the
        // same way, so stop instead of burning through the whole queue.
        if (isFatal(err)) {
          setNotice(messageOf(err))
          break
        }
      }
    }

    setRunning(false)
    abortRef.current = null
  }, [configured, jobs, settings, glossary, series, patch, onNeedSettings])

  const stop = useCallback(() => {
    abortRef.current?.abort()
    setRunning(false)
    setJobs((prev) => prev.map((j) => (j.status === 'running' ? { ...j, status: 'stopped' } : j)))
  }, [])

  /* ------------------------------------------------------------ glossary */

  const buildGlossary = useCallback(async () => {
    if (!configured) return onNeedSettings()
    const source = jobs.find((j) => j.sub)
    if (!source?.sub) return
    setBuildingGlossary(true)
    setNotice(undefined)
    try {
      const entries = await suggestGlossary(source.sub, {
        apiKey: settings.apiKey,
        model: settings.model,
        targetLanguage: settings.targetLanguage,
        seriesName: series || undefined,
      })
      if (!entries.length) {
        setNotice('গ্লসারিতে রাখার মতো কিছু পাওয়া গেল না।')
      } else {
        const name = series || guessSeries(source.fileName)
        if (!series) onSeriesChange(name)
        // Keep anything the user already edited by hand.
        const existing = new Map(glossary.map((g) => [g.source.toLowerCase(), g]))
        for (const e of entries) if (!existing.has(e.source.toLowerCase())) existing.set(e.source.toLowerCase(), e)
        onGlossaryChange(name, [...existing.values()])
        setNotice(`${entries.length}টি শব্দ গ্লসারিতে যোগ হলো — "গ্লসারি" ট্যাবে গিয়ে দেখে নাও।`)
      }
    } catch (err) {
      setNotice(messageOf(err))
    } finally {
      setBuildingGlossary(false)
    }
  }, [configured, jobs, settings, series, glossary, onGlossaryChange, onSeriesChange, onNeedSettings])

  /* ----------------------------------------------------------- downloads */

  const downloadOne = useCallback(
    (job: Job) => {
      if (!job.sub) return
      const blob = new Blob([serialize(job.sub)], { type: 'text/plain;charset=utf-8' })
      triggerDownload(blob, outputName(job.fileName, settings.languageTag))
    },
    [settings.languageTag],
  )

  const downloadZip = useCallback(async () => {
    const zip = new JSZip()
    for (const job of finished) {
      if (!job.sub) continue
      zip.file(outputName(job.fileName, settings.languageTag), serialize(job.sub))
    }
    const blob = await zip.generateAsync({ type: 'blob', compression: 'DEFLATE' })
    const stem = (series || 'subtitles').replace(/[\\/:*?"<>|]/g, '')
    triggerDownload(blob, `${stem}.${settings.languageTag}.zip`)
  }, [finished, series, settings.languageTag])

  const totals = useMemo(() => {
    const lines = jobs.reduce((n, j) => n + j.total, 0)
    const translated = jobs.reduce((n, j) => n + j.done, 0)
    const failed = jobs.reduce((n, j) => n + j.failed, 0)
    return { lines, translated, failed }
  }, [jobs])

  /* ---------------------------------------------------------------- view */

  return (
    <div className="space-y-4">
      {!configured && (
        <button
          onClick={onNeedSettings}
          className="flex w-full items-center gap-3 rounded-xl border border-amber-400/25 bg-amber-400/8 px-4 py-3 text-left text-sm text-amber-200/90 transition hover:bg-amber-400/12"
        >
          <span className="text-base">→</span>
          শুরু করার আগে সেটিংসে গিয়ে Gemini API key বসাও। ফ্রি key নেওয়া যায় Google AI Studio থেকে।
        </button>
      )}

      <div className="grid gap-3 sm:grid-cols-[1fr_auto]">
        <label className="block">
          <span className="mb-1.5 block text-[11px] font-medium uppercase tracking-wide text-white/40">
            সিরিজের নাম — গ্লসারি এই নামে সেভ হয়
          </span>
          <input
            value={series}
            onChange={(e) => onSeriesChange(e.target.value)}
            placeholder="যেমন: Boruto"
            className="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm outline-none transition placeholder:text-white/25 focus:border-violet-400/50 focus:bg-white/8"
          />
        </label>
        <div className="flex items-end gap-2">
          <button
            onClick={buildGlossary}
            disabled={buildingGlossary || running || !jobs.some((j) => j.sub)}
            title={
              jobs.some((j) => j.sub)
                ? 'একটা এপিসোড পড়ে চরিত্র ও বিশেষ শব্দের তালিকা বানাবে'
                : 'আগে একটা সাবটাইটেল ফাইল দাও'
            }
            className="h-[38px] rounded-lg border border-white/10 bg-white/5 px-3.5 text-[13px] font-medium transition hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-35"
          >
            {buildingGlossary ? 'পড়ছি…' : 'গ্লসারি বানাও'}
          </button>
          {running ? (
            <button
              onClick={stop}
              className="h-[38px] rounded-lg bg-red-500/15 px-4 text-[13px] font-semibold text-red-300 transition hover:bg-red-500/25"
            >
              থামাও
            </button>
          ) : (
            <button
              onClick={start}
              disabled={!pending.length}
              title={
                pending.length
                  ? `${pending.length}টি ফাইল অনুবাদ করবে`
                  : jobs.length
                    ? 'বাকি সব ফাইল অনুবাদ হয়ে গেছে'
                    : 'আগে একটা সাবটাইটেল ফাইল দাও'
              }
              className="h-[38px] rounded-lg bg-gradient-to-br from-violet-500 to-indigo-600 px-5 text-[13px] font-semibold text-white shadow-lg shadow-violet-900/30 transition hover:brightness-110 disabled:cursor-not-allowed disabled:from-white/10 disabled:to-white/10 disabled:text-white/35 disabled:shadow-none"
            >
              অনুবাদ শুরু {pending.length > 0 && `(${pending.length})`}
            </button>
          )}
        </div>
      </div>

      {jobs.length === 0 && (
        <p className="text-[12px] text-white/40">
          বাটন দুটো এখন বন্ধ — নিচে একটা সাবটাইটেল ফাইল ছেড়ে দিলেই চালু হয়ে যাবে।
        </p>
      )}

      {glossary.length > 0 && (
        <p className="text-[12px] text-white/40">
          এই সিরিজের গ্লসারিতে {glossary.length}টি শব্দ আছে — প্রতিটা রিকোয়েস্টে পাঠানো হবে।
        </p>
      )}

      <div
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        onClick={() => inputRef.current?.click()}
        className={
          'cursor-pointer rounded-xl border-2 border-dashed px-6 py-9 text-center transition ' +
          (dragging
            ? 'border-violet-400/60 bg-violet-400/8'
            : 'border-white/12 bg-white/[0.02] hover:border-white/25 hover:bg-white/[0.04]')
        }
      >
        <div className="text-sm font-medium">সাবটাইটেল ফাইল এখানে ছেড়ে দাও</div>
        <div className="mt-1 text-[12px] text-white/40">
          .srt · .vtt · .ass — একসাথে পুরো সিজন দিতে পারো
        </div>
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={ACCEPTED}
          className="hidden"
          onChange={(e) => {
            void addFiles(e.target.files ?? [])
            e.target.value = ''
          }}
        />
      </div>

      {notice && (
        <div className="rounded-lg border border-white/10 bg-white/5 px-4 py-2.5 text-[13px] text-white/70">
          {notice}
        </div>
      )}

      {jobs.length > 0 && (
        <div className="overflow-hidden rounded-xl border border-white/8">
          <div className="flex items-center justify-between gap-3 border-b border-white/8 bg-white/[0.03] px-4 py-2.5 text-[12px] text-white/50">
            <span>
              {jobs.length}টি ফাইল · {totals.translated.toLocaleString('bn-BD')}/
              {totals.lines.toLocaleString('bn-BD')} লাইন
              {totals.failed > 0 && <span className="text-amber-300/80"> · {totals.failed} লাইন বাদ পড়েছে</span>}
            </span>
            <div className="flex items-center gap-3">
              {finished.length > 1 && (
                <button onClick={() => void downloadZip()} className="font-medium text-violet-300 hover:text-violet-200">
                  সব ZIP করে নামাও
                </button>
              )}
              {!running && (
                <button onClick={() => setJobs([])} className="text-white/40 hover:text-white/70">
                  তালিকা খালি করো
                </button>
              )}
            </div>
          </div>

          <ul className="divide-y divide-white/6">
            {jobs.map((job) => (
              <JobRow key={job.id} job={job} onDownload={() => downloadOne(job)} />
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ row */

function JobRow({ job, onDownload }: { job: Job; onDownload: () => void }) {
  const pct = job.total ? Math.round((job.done / job.total) * 100) : 0
  return (
    <li className="px-4 py-3">
      <div className="flex items-center gap-3">
        <StatusDot status={job.status} />
        <span className="min-w-0 flex-1 truncate text-[13px]" title={job.fileName}>
          {job.fileName}
        </span>
        {job.status === 'done' ? (
          <button
            onClick={onDownload}
            className="shrink-0 rounded-md bg-white/8 px-2.5 py-1 text-[12px] font-medium transition hover:bg-white/14"
          >
            নামাও
          </button>
        ) : (
          <span className="shrink-0 text-[12px] tabular-nums text-white/35">
            {job.status === 'running' ? `${pct}%` : `${job.total || '—'} লাইন`}
          </span>
        )}
      </div>

      {job.status === 'running' && (
        <div className="mt-2 h-1 overflow-hidden rounded-full bg-white/8">
          <div
            className="h-full rounded-full bg-gradient-to-r from-violet-500 to-indigo-400 transition-all duration-300"
            style={{ width: `${pct}%` }}
          />
        </div>
      )}

      {job.error && <p className="mt-1.5 text-[12px] text-red-300/80">{job.error}</p>}
      {job.status === 'done' && job.failed > 0 && (
        <p className="mt-1.5 text-[12px] text-amber-300/70">
          {job.failed}টি লাইন অনুবাদ হয়নি — ওগুলো মূল ভাষাতেই রাখা হয়েছে, টাইমিং ঠিক আছে।
        </p>
      )}
    </li>
  )
}

function StatusDot({ status }: { status: JobStatus }) {
  const map: Record<JobStatus, string> = {
    queued: 'bg-white/25',
    running: 'bg-violet-400 animate-pulse',
    done: 'bg-emerald-400',
    error: 'bg-red-400',
    stopped: 'bg-amber-400',
  }
  return <span className={'h-2 w-2 shrink-0 rounded-full ' + map[status]} />
}

/* -------------------------------------------------------------- helpers */

function readFile(file: File): Promise<string> {
  return file.text()
}

function triggerDownload(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function messageOf(err: unknown): string {
  if (err && typeof err === 'object' && 'message' in err) return String((err as Error).message)
  return 'অজানা সমস্যা'
}

function isFatal(err: unknown): boolean {
  const status = err && typeof err === 'object' && 'status' in err ? Number((err as { status: unknown }).status) : 0
  return status === 400 || status === 401 || status === 403 || status === 404 || status === 429
}
