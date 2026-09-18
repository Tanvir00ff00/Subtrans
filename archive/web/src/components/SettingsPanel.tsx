import { useState } from 'react'
import { fetchModels, MODELS, verifyKey, type ModelInfo } from '../lib/gemini'
import type { Settings } from '../lib/store'

interface Props {
  settings: Settings
  onChange: (next: Settings) => void
}

const LANGUAGES: Array<{ name: string; tag: string }> = [
  { name: 'Bengali (বাংলা)', tag: 'bn' },
  { name: 'Hindi (हिन्दी)', tag: 'hi' },
  { name: 'English', tag: 'en' },
  { name: 'Urdu (اردو)', tag: 'ur' },
  { name: 'Arabic (العربية)', tag: 'ar' },
  { name: 'Indonesian', tag: 'id' },
  { name: 'Spanish (Español)', tag: 'es' },
  { name: 'Turkish (Türkçe)', tag: 'tr' },
]

type TestState = { kind: 'idle' } | { kind: 'busy' } | { kind: 'ok' } | { kind: 'fail'; message: string }

export default function SettingsPanel({ settings, onChange }: Props) {
  const [showKey, setShowKey] = useState(false)
  const [test, setTest] = useState<TestState>({ kind: 'idle' })
  const [models, setModels] = useState<ModelInfo[]>(MODELS)
  const [listed, setListed] = useState(false)
  const [listState, setListState] = useState<TestState>({ kind: 'idle' })

  const set = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    onChange({ ...settings, [key]: value })

  const runTest = async () => {
    setTest({ kind: 'busy' })
    try {
      await verifyKey(settings.apiKey.trim(), settings.model)
      setTest({ kind: 'ok' })
    } catch (err) {
      setTest({ kind: 'fail', message: err instanceof Error ? err.message : 'কাজ করল না' })
    }
  }

  const loadModels = async () => {
    setListState({ kind: 'busy' })
    try {
      const list = await fetchModels(settings.apiKey.trim())
      if (!list.length) {
        setListState({ kind: 'fail', message: 'এই key দিয়ে কোনো টেক্সট মডেল পাওয়া গেল না।' })
        return
      }
      setModels(list)
      setListed(true)
      setListState({ kind: 'ok' })
    } catch (err) {
      setListState({ kind: 'fail', message: err instanceof Error ? err.message : 'তালিকা আনা গেল না' })
    }
  }

  const known = models.some((m) => m.id === settings.model)

  return (
    <div className="space-y-6">
      <Section
        title="Gemini API key"
        hint="Google AI Studio-তে গিয়ে ফ্রি key বানানো যায়। key শুধু এই ব্রাউজারে সেভ হয়, সরাসরি Google-এ যায়, মাঝখানে কোনো সার্ভার নেই।"
      >
        <div className="flex gap-2">
          <div className="relative flex-1">
            <input
              type={showKey ? 'text' : 'password'}
              value={settings.apiKey}
              onChange={(e) => {
                set('apiKey', e.target.value)
                setTest({ kind: 'idle' })
              }}
              placeholder="AIza…"
              spellCheck={false}
              autoComplete="off"
              className="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 pr-16 font-mono text-[13px] outline-none transition placeholder:text-white/25 focus:border-violet-400/50"
            />
            <button
              onClick={() => setShowKey((v) => !v)}
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded px-2 py-1 text-[11px] text-white/40 hover:text-white/80"
            >
              {showKey ? 'লুকাও' : 'দেখাও'}
            </button>
          </div>
          <button
            onClick={() => void runTest()}
            disabled={!settings.apiKey.trim() || test.kind === 'busy'}
            className="shrink-0 rounded-lg border border-white/10 bg-white/5 px-3.5 text-[13px] font-medium transition hover:bg-white/10 disabled:opacity-35"
          >
            {test.kind === 'busy' ? 'দেখছি…' : 'পরীক্ষা করো'}
          </button>
        </div>
        {test.kind === 'ok' && <p className="text-[12px] text-emerald-300">key কাজ করছে।</p>}
        {test.kind === 'fail' && <p className="text-[12px] text-red-300/85">{test.message}</p>}
        <a
          href="https://aistudio.google.com/apikey"
          target="_blank"
          rel="noreferrer noopener"
          className="inline-block text-[12px] text-violet-300 hover:text-violet-200"
        >
          key বানাতে Google AI Studio খোলো →
        </a>
      </Section>

      <Section
        title="মডেল"
        action={
          <button
            onClick={() => void loadModels()}
            disabled={!settings.apiKey.trim() || listState.kind === 'busy'}
            className="rounded-md border border-white/10 bg-white/5 px-2.5 py-1 text-[12px] font-medium transition hover:bg-white/10 disabled:opacity-35"
          >
            {listState.kind === 'busy' ? 'আনছি…' : 'তালিকা আনো'}
          </button>
        }
        hint={
          listed
            ? undefined
            : 'নিচেরগুলো আন্দাজে বসানো নাম। "তালিকা আনো" চাপলে তোমার key দিয়ে আসলে কোন মডেলগুলো চালানো যায়, সেটাই দেখাবে।'
        }
      >
        {listState.kind === 'fail' && (
          <p className="text-[12px] text-red-300/85">{listState.message}</p>
        )}
        {listed && (
          <p className="text-[12px] text-emerald-300">
            তোমার key-তে {models.length}টি মডেল পাওয়া গেছে।
          </p>
        )}
        {!known && (
          <p className="text-[12px] text-amber-300/85">
            এখন বাছা আছে <span className="font-mono">{settings.model}</span> — এটা তালিকায় নেই,
            তাই কাজ নাও করতে পারে।
          </p>
        )}

        <div
          className={
            'grid gap-2 sm:grid-cols-3 ' + (models.length > 9 ? 'max-h-72 overflow-y-auto pr-1' : '')
          }
        >
          {models.map((m) => (
            <button
              key={m.id}
              onClick={() => {
                set('model', m.id)
                setTest({ kind: 'idle' })
              }}
              title={m.id}
              className={
                'rounded-lg border px-3 py-2.5 text-left transition ' +
                (settings.model === m.id
                  ? 'border-violet-400/50 bg-violet-400/10'
                  : 'border-white/10 bg-white/[0.03] hover:bg-white/[0.06]')
              }
            >
              <div className="truncate text-[13px] font-medium">{m.label}</div>
              <div className="mt-0.5 line-clamp-2 text-[11px] leading-snug text-white/40">
                {m.hint ?? m.id}
              </div>
            </button>
          ))}
        </div>
      </Section>

      <Section title="কোন ভাষায় অনুবাদ হবে">
        <div className="flex flex-wrap gap-2">
          {LANGUAGES.map((l) => (
            <button
              key={l.tag}
              onClick={() => onChange({ ...settings, targetLanguage: l.name, languageTag: l.tag })}
              className={
                'rounded-full border px-3.5 py-1.5 text-[12px] transition ' +
                (settings.targetLanguage === l.name
                  ? 'border-violet-400/50 bg-violet-400/12 text-white'
                  : 'border-white/10 bg-white/[0.03] text-white/60 hover:text-white')
              }
            >
              {l.name}
            </button>
          ))}
        </div>
        <div className="grid gap-2 sm:grid-cols-[1fr_120px]">
          <input
            value={settings.targetLanguage}
            onChange={(e) => set('targetLanguage', e.target.value)}
            placeholder="বা নিজে লেখো"
            className="rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-[13px] outline-none focus:border-violet-400/50"
          />
          <input
            value={settings.languageTag}
            onChange={(e) => set('languageTag', e.target.value.replace(/[^a-z-]/gi, '').toLowerCase())}
            placeholder="bn"
            title="ফাইলের নামে যে ট্যাগ বসবে"
            className="rounded-lg border border-white/10 bg-white/5 px-3 py-2 font-mono text-[13px] outline-none focus:border-violet-400/50"
          />
        </div>
      </Section>

      <Section title="ভাষার ধরন" hint="মডেলকে বলা হবে ঠিক এই ঢঙে অনুবাদ করতে।">
        <textarea
          value={settings.tone}
          onChange={(e) => set('tone', e.target.value)}
          rows={3}
          className="w-full resize-y rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-[13px] leading-relaxed outline-none focus:border-violet-400/50"
        />
      </Section>

      <Section
        title="গতি ও নির্ভুলতা"
        hint="ব্যাচ বড় করলে কম রিকোয়েস্টে কাজ শেষ হয় কিন্তু লাইন হারানোর ঝুঁকি বাড়ে। কোটায় বাধা পেলে সমান্তরাল সংখ্যা কমিয়ে দাও।"
      >
        <Slider
          label="প্রতি ব্যাচে লাইন"
          value={settings.batchSize}
          min={10}
          max={100}
          step={5}
          onChange={(v) => set('batchSize', v)}
        />
        <Slider
          label="একসাথে কয়টা ব্যাচ"
          value={settings.concurrency}
          min={1}
          max={8}
          step={1}
          onChange={(v) => set('concurrency', v)}
        />
        <Slider
          label="কনটেক্সট লাইন"
          value={settings.contextLines}
          min={0}
          max={8}
          step={1}
          onChange={(v) => set('contextLines', v)}
        />
        <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-white/10 bg-white/[0.03] px-3 py-2.5">
          <input
            type="checkbox"
            checked={settings.fastMode}
            onChange={(e) => set('fastMode', e.target.checked)}
            className="mt-0.5 h-4 w-4 accent-violet-500"
          />
          <span className="text-[13px] leading-snug">
            দ্রুত মোড
            <span className="block text-[11px] text-white/40">
              মডেলের অভ্যন্তরীণ চিন্তার ধাপ বন্ধ থাকে — অনুবাদে কয়েকগুণ দ্রুত, মান প্রায় একই।
            </span>
          </span>
        </label>
      </Section>
    </div>
  )
}

function Section({
  title,
  hint,
  action,
  children,
}: {
  title: string
  hint?: string
  action?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <section className="space-y-2.5">
      <div className="flex items-start gap-3">
        <div className="flex-1">
          <h2 className="text-[13px] font-semibold">{title}</h2>
          {hint && <p className="mt-1 text-[12px] leading-relaxed text-white/40">{hint}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}

function Slider({
  label,
  value,
  min,
  max,
  step,
  onChange,
}: {
  label: string
  value: number
  min: number
  max: number
  step: number
  onChange: (v: number) => void
}) {
  return (
    <label className="flex items-center gap-3">
      <span className="w-40 shrink-0 text-[13px] text-white/60">{label}</span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="h-1 flex-1 accent-violet-500"
      />
      <span className="w-8 shrink-0 text-right text-[13px] tabular-nums text-white/70">{value}</span>
    </label>
  )
}
