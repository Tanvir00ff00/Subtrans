import { useEffect, useState } from 'react'
import type { GlossaryMap } from '../lib/store'
import type { GlossaryEntry } from '../lib/translate'

interface Props {
  glossaries: GlossaryMap
  activeSeries: string
  onChange: (series: string, entries: GlossaryEntry[]) => void
  onRemove: (series: string) => void
}

export default function GlossaryPanel({ glossaries, activeSeries, onChange, onRemove }: Props) {
  const names = Object.keys(glossaries).sort((a, b) => a.localeCompare(b))
  const [selected, setSelected] = useState(activeSeries || names[0] || '')

  useEffect(() => {
    if (!selected && names.length) setSelected(names[0])
  }, [names, selected])

  const entries = glossaries[selected] ?? []

  const update = (index: number, patch: Partial<GlossaryEntry>) => {
    const next = entries.map((e, i) => (i === index ? { ...e, ...patch } : e))
    onChange(selected, next)
  }

  const remove = (index: number) => onChange(selected, entries.filter((_, i) => i !== index))

  const add = () => {
    const name = selected || activeSeries || 'Default'
    if (!selected) setSelected(name)
    onChange(name, [...(glossaries[name] ?? []), { source: '', target: '' }])
  }

  if (!names.length && !activeSeries) {
    return (
      <Empty>
        এখনো কোনো গ্লসারি নেই। অনুবাদ ট্যাবে একটা ফাইল দিয়ে "গ্লসারি বানাও" চাপলে চরিত্রের নাম আর
        বিশেষ শব্দগুলো নিজে থেকেই এখানে চলে আসবে।
      </Empty>
    )
  }

  return (
    <div className="space-y-4">
      <p className="text-[12px] leading-relaxed text-white/45">
        এখানকার প্রতিটা শব্দ প্রতিটা রিকোয়েস্টের সাথে পাঠানো হয়, তাই ২০০ এপিসোড জুড়ে একটা নাম
        একইরকম বানানে থাকে। হাতে ঠিক করে নিলে পরের সব এপিসোড সেটাই মেনে চলবে।
      </p>

      {names.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          {names.map((name) => (
            <button
              key={name}
              onClick={() => setSelected(name)}
              className={
                'rounded-full border px-3.5 py-1.5 text-[12px] transition ' +
                (selected === name
                  ? 'border-violet-400/50 bg-violet-400/12 text-white'
                  : 'border-white/10 bg-white/[0.03] text-white/60 hover:text-white')
              }
            >
              {name}
              <span className="ml-1.5 text-white/35">{glossaries[name].length}</span>
            </button>
          ))}
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-white/8">
        <div className="grid grid-cols-[1fr_1fr_1fr_32px] gap-2 border-b border-white/8 bg-white/[0.03] px-3 py-2 text-[11px] font-medium uppercase tracking-wide text-white/40">
          <span>মূল শব্দ</span>
          <span>যা লেখা হবে</span>
          <span>নোট</span>
          <span />
        </div>

        {entries.length === 0 && (
          <p className="px-3 py-6 text-center text-[13px] text-white/35">এই সিরিজের তালিকা খালি।</p>
        )}

        <ul className="divide-y divide-white/6">
          {entries.map((entry, i) => (
            <li key={i} className="grid grid-cols-[1fr_1fr_1fr_32px] items-center gap-2 px-3 py-1.5">
              <Cell value={entry.source} onChange={(v) => update(i, { source: v })} placeholder="Naruto" />
              <Cell value={entry.target} onChange={(v) => update(i, { target: v })} placeholder="নারুতো" />
              <Cell value={entry.note ?? ''} onChange={(v) => update(i, { note: v })} placeholder="চরিত্র" muted />
              <button
                onClick={() => remove(i)}
                title="মুছে ফেলো"
                className="justify-self-center rounded px-1.5 py-0.5 text-white/30 transition hover:bg-red-500/15 hover:text-red-300"
              >
                ×
              </button>
            </li>
          ))}
        </ul>

        <div className="flex items-center justify-between border-t border-white/8 bg-white/[0.02] px-3 py-2">
          <button onClick={add} className="text-[12px] font-medium text-violet-300 hover:text-violet-200">
            + নতুন শব্দ
          </button>
          {selected && entries.length > 0 && (
            <button
              onClick={() => {
                if (confirm(`"${selected}"-এর পুরো গ্লসারি মুছে যাবে। চালিয়ে যাবে?`)) {
                  onRemove(selected)
                  setSelected('')
                }
              }}
              className="text-[12px] text-white/35 hover:text-red-300"
            >
              এই তালিকা মুছে ফেলো
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function Cell({
  value,
  onChange,
  placeholder,
  muted,
}: {
  value: string
  onChange: (v: string) => void
  placeholder: string
  muted?: boolean
}) {
  return (
    <input
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      className={
        'w-full rounded-md border border-transparent bg-transparent px-2 py-1.5 text-[13px] outline-none transition placeholder:text-white/20 hover:border-white/10 focus:border-violet-400/50 focus:bg-white/5 ' +
        (muted ? 'text-white/50' : '')
      }
    />
  )
}

function Empty({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-dashed border-white/12 px-6 py-10 text-center text-[13px] leading-relaxed text-white/45">
      {children}
    </div>
  )
}
