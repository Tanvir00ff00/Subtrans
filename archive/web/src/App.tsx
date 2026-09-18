import { useCallback, useEffect, useMemo, useState } from 'react'
import Translator from './components/Translator'
import GlossaryPanel from './components/GlossaryPanel'
import SettingsPanel from './components/SettingsPanel'
import {
  DEFAULT_SETTINGS,
  loadGlossaries,
  loadSettings,
  saveGlossaries,
  saveSettings,
  type GlossaryMap,
  type Settings,
} from './lib/store'
import type { GlossaryEntry } from './lib/translate'

type Tab = 'translate' | 'glossary' | 'settings'

const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'translate', label: 'অনুবাদ' },
  { id: 'glossary', label: 'গ্লসারি' },
  { id: 'settings', label: 'সেটিংস' },
]

export default function App() {
  const [tab, setTab] = useState<Tab>('translate')
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS)
  const [glossaries, setGlossaries] = useState<GlossaryMap>({})
  const [series, setSeries] = useState('')

  useEffect(() => {
    setSettings(loadSettings())
    setGlossaries(loadGlossaries())
  }, [])

  const updateSettings = useCallback((next: Settings) => {
    setSettings(next)
    saveSettings(next)
  }, [])

  const setGlossaryFor = useCallback((name: string, entries: GlossaryEntry[]) => {
    setGlossaries((prev) => {
      const next = { ...prev, [name]: entries }
      saveGlossaries(next)
      return next
    })
  }, [])

  const removeGlossary = useCallback((name: string) => {
    setGlossaries((prev) => {
      const next = { ...prev }
      delete next[name]
      saveGlossaries(next)
      return next
    })
  }, [])

  const activeGlossary = useMemo(
    () => (series ? glossaries[series] ?? [] : []),
    [glossaries, series],
  )

  const configured = settings.apiKey.trim().length > 0

  return (
    <div className="min-h-full bg-[#0a0a0f]">
      <header className="sticky top-0 z-20 border-b border-white/8 bg-[#0a0a0f]/85 backdrop-blur">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-4 px-5 py-3">
          <div className="flex items-center gap-2.5">
            <div className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-violet-500 to-indigo-600 text-sm font-bold text-white">
              ক
            </div>
            <div className="leading-tight">
              <div className="text-sm font-semibold tracking-tight">SubTrans</div>
              <div className="text-[11px] text-white/40">সাবটাইটেল ট্রান্সলেটর</div>
            </div>
          </div>

          <nav className="ml-auto flex items-center gap-1 rounded-lg bg-white/5 p-1">
            {TABS.map((t) => (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                className={
                  'rounded-md px-3 py-1.5 text-[13px] font-medium transition ' +
                  (tab === t.id
                    ? 'bg-white/10 text-white shadow-sm'
                    : 'text-white/50 hover:text-white/80')
                }
              >
                {t.label}
                {t.id === 'settings' && !configured && (
                  <span className="ml-1.5 inline-block h-1.5 w-1.5 rounded-full bg-amber-400 align-middle" />
                )}
              </button>
            ))}
          </nav>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-5 py-6 bn">
        {tab === 'translate' && (
          <Translator
            settings={settings}
            series={series}
            onSeriesChange={setSeries}
            glossary={activeGlossary}
            onGlossaryChange={setGlossaryFor}
            onNeedSettings={() => setTab('settings')}
          />
        )}
        {tab === 'glossary' && (
          <GlossaryPanel
            glossaries={glossaries}
            activeSeries={series}
            onChange={setGlossaryFor}
            onRemove={removeGlossary}
          />
        )}
        {tab === 'settings' && <SettingsPanel settings={settings} onChange={updateSettings} />}
      </main>

      <footer className="mx-auto max-w-5xl px-5 pb-10 text-[11px] leading-relaxed text-white/30">
        পুরোটা তোমার ব্রাউজারেই চলে। API key আর গ্লসারি এই ডিভাইসেই থাকে, কোনো সার্ভারে যায় না।
      </footer>
    </div>
  )
}
