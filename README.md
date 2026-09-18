# SubTrans

An Android app that translates subtitle files into your own language. Translation
runs on the phone, offline, with no daily limit. AI is called only for the handful
of lines the engine is unsure about — twenty or thirty times across a whole series,
not once per line.

No account, no API key, nothing to sign up for.

## Why it is built this way

An episode carries around 300 lines of dialogue. A 293-episode series is roughly
90,000 lines. Sending each one to an AI would exhaust any free quota long before
the first season finished, and would take hours.

So the work is split. **ML Kit's on-device models** translate every line — once the
language pair is downloaded (30–40 MB, one time) it runs with no network, free, and
without limit. **AI only tunes**: it builds a glossary once, and repairs the lines
the engine flags.

## Failures this code exists to prevent

Most of this project is defensive. The bugs that matter in subtitle translation are
the silent ones — nothing errors, the file still parses, the line count is still
right, and the result is quietly ruined.

**Timing drift.** Timestamps never reach the translator. Every line is re-seated by
id, so no matter what the model does to the words, the timing cannot shift.

**Mangled names.** Character names and special terms are locked before translation
and written back with the agreed spelling afterwards, so they stay identical across
every episode.

**Re-translating finished work.** This really happened: a file named `..._English.srt`
contained perfectly good Bengali. Treated as English, it was translated again and
destroyed. Judgement is now made on the file's **script**, not its name, and such a
file is passed through untouched.

**Losing files silently.** Already-translated files used to be excluded from export,
so 100 files in gave 95 out — and the missing five were exactly the finished ones.
They are now exported unchanged, and every run reports a full count out loud.

**Encoding.** A UTF-16 or Windows-1252 file read as UTF-8 parses fine and counts
lines fine; only the text is garbage. Encoding is now detected from the bytes.

**ICU vs JVM regex.** Android compiles regexes with ICU, the desktop JVM does not.
One unescaped `}` passed every unit test on a laptop and crashed on a phone.
`RegexCompilesOnDeviceTest` guards that whole class of bug.

**Markup destroying the translation.** See below — this one was measured, and it was
the worst of them.

## The placeholder bug

The first design swapped every styling tag for a short token: `<i>` became `@0@`, on
the theory that a translation model ignores meaningless ASCII. Seven real episodes
disproved that in three separate ways:

1. ML Kit pads the token with spaces. `@0@` returns as `@ 0 @`, the restore pattern
   no longer matches, and the junk is written into the subtitle.
2. Sometimes the token stops the model translating at all — the English is echoed
   straight back, landing an untranslated line in the output file.
3. Sometimes the model degenerates into a row of bare `@`, losing the sentence.

The share of broken lines tracked the share of italic lines almost exactly:

| Episode | Italic lines | Broken lines |
|---|---|---|
| E269 | 1 | 1 |
| **E270** | **160 of 299** | **111** |
| E271 | 11 | 15 |
| E272 | 14 | 16 |
| E273 | 11 | 13 |
| E275 | 3 | 3 |

**The fix:** markup is no longer encoded for the model — it never reaches the model.
A line is cut into literal markup and translatable text, only the words are sent, and
the pieces are reassembled afterwards. There is nothing left to mangle.

Swept across all 2,474 cues of those seven files: **0 tags leaked, 0 lines broken,
and exactly 1.000 chunks per cue** — so the guarantee costs no extra model calls at
all. A sentence split across two italic display lines now reaches the model whole
rather than in halves, which also reads better.

## The phrase table

Some failures are the model's, not the plumbing's. A general translation model does
not know it is reading dialogue, so it gives a one-word line its commonest prose
meaning. Measured on the same episodes:

| Line | What the model returned |
|---|---|
| `Fine.` | জরিমানা — a monetary fine |
| `I'm fine.` | আমি জরিমানা করছি — "I am issuing a fine" |
| `Bye-bye!` | ঘুম! — sleep |
| `Huh?` | তাই না? — "isn't it?", the opposite of a question |
| `Damn it!` | এটা! — "this!" |

These are frequent — across six episodes `Huh?` appeared 17 times and `No way` 22 —
and easy to get right, because they are whole lines with one obvious spoken meaning.
So they are answered from a table instead of the model, which is both more accurate
and one fewer call.

The table is deliberately narrow: it matches **whole lines only**, so `Fine.` is
answered but `Fine, let's go` is left to the model. A glossary entry or replace rule
overrides it. It is a seed to be corrected, not an authority.

## Where subtitles come from

Three routes, cheapest first. **None of them requires an API key except the last.**

**ZIP or folder import.** No key, no limit, nested folders up to eight deep. For a
long series this is the only practical route, and it is the first button on the screen.

**Gestdown.** A public proxy in front of Addic7ed — search, season listing and file
download all verified working against the live service with no key, no account and
no daily allowance. Its limit is catalogue, not access: Addic7ed indexes Western
television thoroughly and anime barely.

**OpenSubtitles.** The widest catalogue, including anime, and the only source that
demands a key — a bare request returns `403 You cannot consume this service`, which
no amount of client-side work changes. It stays switched off and greyed out until a
key is supplied. A free key from opensubtitles.com enables it; the app never asks for
a password.

## Features

**Mixed languages.** One batch can hold English and Hindi files together. Each file's
language is detected separately and one model is loaded per language.

**Read and edit.** Open any file to see source and translation side by side, search
it, filter to the suspicious lines, and correct any line by hand.

**Tools.** Shift timing by ±seconds (for rescuing subtitles cut for a different
release), drop empty and repeated cues, detect language, and apply find-and-replace
rules.

**Export.** Individual files, a whole folder, or a ZIP that preserves the input's
folder structure. Names can be kept unchanged so a player picks the subtitle up
automatically, or written as bilingual files with the original line beneath each
translation.

Supported formats: `.srt` · `.vtt` · `.ass` / `.ssa`. In ASS files only the dialogue
text changes — styles, fonts and positioning are left exactly as they were.

## Speed

Measured on a real 7-episode batch of 2,900 lines:

| Approach | Model calls |
|---|---|
| One call per line | 2,900 |
| Batching | 949 |
| Batching + joined display lines | **297** |

Three things do the work. Lines with no letters (`♪`, `...`) never go to the model.
Identical lines are translated once. And several lines ride in one call — but **a
batch is rejected outright unless the line count comes back exactly right**, and is
then redone one line at a time. A slow correct answer beats a fast wrong one.

Where a subtitle breaks across two display lines is a layout decision, not a meaning
one, so the break is collapsed before translating. That cuts calls and hands the
model a whole sentence instead of half of one.

## Building

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

On-device tests (phone connected over USB):

```bash
./gradlew :app:connectedDebugAndroidTest
```

The device suite is separate because Android and the desktop JVM use different regex
engines, and because the only honest way to measure what a translation model does to
a token is to ask a real one.

## Releases

Pushing a tag beginning with `v` makes GitHub Actions run the tests, build the APK
and publish it to the Releases page.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Signing keys are not in the repository — `keystore.properties` locally, repository
secrets in CI (see `keystore.properties.example`). The build works without them; the
APK is simply unsigned.

> Lose the key and an installed app can never be updated again — Android refuses an
> update signed differently. Back up `release.jks`.

## Keys

**Everything works with no keys at all.** Both are optional and both are for extras:

- **Gemini** — enables AI polish and glossary building. Without it the app runs
  entirely offline. Free from [Google AI Studio](https://aistudio.google.com/apikey).
- **OpenSubtitles** — enables that one source. The other two sources need nothing.

Keys are stored on the device only and go straight to the service concerned. There is
no server in between.

## Layout

```
app/src/main/java/com/subtrans/app/
  subtitle/   SRT · VTT · ASS parsers, encoding, timing shift, name labels
  engine/     markup splitting, glossary lock, language guard, batching,
              ML Kit, phrase table, quality checks
  ai/         Gemini — glossary building and repair of flagged lines
  net/        subtitle sources, folder scan, ZIP import and export
  data/       settings and glossary storage
  ui/         Compose screens
archive/web/  the earlier web prototype the parser logic was ported from
```

## Status

200 JVM unit tests cover the parsers and the engine, plus a separate device suite.
Parsing is tested against responses captured from live services rather than invented
ones, and the markup fix was swept across 2,474 real cues.

The app builds and the logic is verified, but ML Kit cannot run off a device, so
**actual translation quality has been measured only on a phone, not in CI.** Treat
the quality claims here as the result of reading real output, not as a benchmark.

The interface is in Bengali.

## Licence

MIT — see [LICENSE](LICENSE).
