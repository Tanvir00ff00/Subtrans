import { strict as assert } from 'node:assert'
import { describe, it } from 'node:test'
import { detectFormat, outputName, parse, serialize } from './subtitle.ts'

const SRT = `1
00:00:01,000 --> 00:00:03,480
Morning already?

2
00:00:03,600 --> 00:00:06,120
<i>He had not slept at all.</i>
Not one minute.

3
00:00:06,300 --> 00:00:08,000
Let's go, then.
`

describe('srt', () => {
  it('reads every cue and keeps multi-line text together', () => {
    const sub = parse('ep01.srt', SRT)
    assert.equal(sub.format, 'srt')
    assert.equal(sub.cues.length, 3)
    assert.equal(sub.cues[1].text, '<i>He had not slept at all.</i>\nNot one minute.')
  })

  it('round-trips untouched text back to the same timings', () => {
    const sub = parse('ep01.srt', SRT)
    const out = serialize(sub)
    for (const cue of sub.cues) {
      assert.ok(out.includes((cue.meta as { timing: string }).timing))
    }
    assert.equal(parse('ep01.srt', out).cues.length, 3)
  })

  it('puts translations in the right slots and leaves timings alone', () => {
    const sub = parse('ep01.srt', SRT)
    sub.cues[0].translated = 'সকাল হয়ে গেল?'
    sub.cues[2].translated = 'চলো তাহলে।'
    const out = serialize(sub)
    assert.ok(out.includes('00:00:01,000 --> 00:00:03,480\nসকাল হয়ে গেল?'))
    assert.ok(out.includes('00:00:06,300 --> 00:00:08,000\nচলো তাহলে।'))
    // An untranslated cue keeps its source text rather than going blank.
    assert.ok(out.includes('Not one minute.'))
  })

  it('handles files that omit the cue numbers', () => {
    const noIndex = '00:00:01,000 --> 00:00:02,000\nFirst\n\n00:00:02,000 --> 00:00:03,000\nSecond\n'
    const sub = parse('x.srt', noIndex)
    assert.equal(sub.cues.length, 2)
    // Serialising renumbers them from one.
    assert.ok(serialize(sub).startsWith('1\n00:00:01,000'))
  })

  it('survives CRLF line endings and a byte order mark', () => {
    const sub = parse('x.srt', '﻿1\r\n00:00:01,000 --> 00:00:02,000\r\nHello\r\n')
    assert.equal(sub.cues.length, 1)
    assert.equal(sub.cues[0].text, 'Hello')
  })
})

const VTT = `WEBVTT

NOTE ripped from a stream

intro
00:00:01.000 --> 00:00:03.000
Where are we going?

00:00:03.200 --> 00:00:05.000
You will see.
`

describe('vtt', () => {
  it('keeps the header and note blocks out of the cue list', () => {
    const sub = parse('ep01.vtt', VTT)
    assert.equal(sub.format, 'vtt')
    assert.equal(sub.cues.length, 2)
    assert.equal(sub.cues[0].text, 'Where are we going?')
  })

  it('round-trips the header, the cue id and the timings', () => {
    const sub = parse('ep01.vtt', VTT)
    sub.cues[0].translated = 'আমরা কোথায় যাচ্ছি?'
    const out = serialize(sub)
    assert.ok(out.startsWith('WEBVTT'))
    assert.ok(out.includes('NOTE ripped from a stream'))
    assert.ok(out.includes('intro\n00:00:01.000 --> 00:00:03.000\nআমরা কোথায় যাচ্ছি?'))
  })
})

const ASS = `[Script Info]
Title: Sample
ScriptType: v4.00+

[V4+ Styles]
Format: Name, Fontname, Fontsize
Style: Default,Arial,48

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\an8}Wait, stop!
Dialogue: 0,0:00:03.20,0:00:05.00,Default,,0,0,0,,One, two, three.
Comment: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,not a real line
`

describe('ass', () => {
  it('picks up dialogue lines only, and text containing commas', () => {
    const sub = parse('ep01.ass', ASS)
    assert.equal(sub.format, 'ass')
    assert.equal(sub.cues.length, 2)
    assert.equal(sub.cues[0].text, '{\\an8}Wait, stop!')
    assert.equal(sub.cues[1].text, 'One, two, three.')
  })

  it('swaps only the text field and leaves the rest of the file byte-identical', () => {
    const sub = parse('ep01.ass', ASS)
    sub.cues[0].translated = '{\\an8}দাঁড়াও!'
    const out = serialize(sub)
    assert.ok(out.includes('[Script Info]'))
    assert.ok(out.includes('Style: Default,Arial,48'))
    assert.ok(out.includes('Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\an8}দাঁড়াও!'))
    // Untranslated dialogue and comments come through unchanged.
    assert.ok(out.includes('Dialogue: 0,0:00:03.20,0:00:05.00,Default,,0,0,0,,One, two, three.'))
    assert.ok(out.includes('Comment: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,not a real line'))
  })

  it('writes multi-line translations back as ASS line breaks', () => {
    const sub = parse('ep01.ass', ASS)
    sub.cues[1].translated = 'এক, দুই\nতিন।'
    assert.ok(serialize(sub).includes('এক, দুই\\Nতিন।'))
  })
})

describe('format detection', () => {
  it('trusts the extension first', () => {
    assert.equal(detectFormat('a.ASS', ''), 'ass')
    assert.equal(detectFormat('a.vtt', ''), 'vtt')
  })

  it('sniffs the content when the name is useless', () => {
    assert.equal(detectFormat('subtitle', 'WEBVTT\n\n'), 'vtt')
    assert.equal(detectFormat('subtitle', '[Script Info]\n'), 'ass')
    assert.equal(detectFormat('subtitle', '1\n00:00:01,000 --> 00:00:02,000\nhi'), 'srt')
  })
})

describe('outputName', () => {
  it('adds the language tag', () => {
    assert.equal(outputName('Boruto - 042.srt', 'bn'), 'Boruto - 042.bn.srt')
  })

  it('replaces an existing language tag instead of stacking one on', () => {
    assert.equal(outputName('Boruto - 042.en.srt', 'bn'), 'Boruto - 042.bn.srt')
    assert.equal(outputName('show.eng.ass', 'bn'), 'show.bn.ass')
  })
})
