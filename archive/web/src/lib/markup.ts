/**
 * Inline markup protection.
 *
 * Subtitle text carries styling the model must not touch: `<i>` tags in SRT,
 * `{\an8\fad(200,200)}` override blocks and `\N` breaks in ASS, `<v Speaker>`
 * in VTT. We swap each one for an opaque token before translating and put it
 * back afterwards, so styling survives even if the model ignores instructions.
 */

const PATTERNS: RegExp[] = [
  /\{[^{}]*\}/g,      // ASS override blocks
  /<[^<>]+>/g,        // HTML-ish tags (SRT/VTT)
  /\\[Nnh]/g,         // ASS hard/soft line breaks and hard spaces
  /\{\\[^}]*$/g,      // an unterminated override block
]

// Private Use Area characters: they never occur in real subtitle text and
// survive a JSON round-trip intact.
const OPEN = String.fromCharCode(0xe000)
const CLOSE = String.fromCharCode(0xe001)

export interface Protected {
  text: string
  tokens: string[]
}

export function protect(text: string): Protected {
  const tokens: string[] = []
  let out = text
  for (const pattern of PATTERNS) {
    out = out.replace(pattern, (match) => {
      tokens.push(match)
      return OPEN + (tokens.length - 1) + CLOSE
    })
  }
  return { text: out, tokens }
}

export function restore(text: string, tokens: string[]): string {
  if (!tokens.length) return text
  return text.replace(
    new RegExp(OPEN + '(\\d+)' + CLOSE, 'g'),
    (whole, n: string) => tokens[Number(n)] ?? whole,
  )
}

/**
 * Some models quietly drop or mangle the tokens. Anything left over that looks
 * like a stray marker is stripped so the viewer never sees control characters.
 */
export function scrub(text: string): string {
  return text.replace(new RegExp('[' + OPEN + CLOSE + ']', 'g'), '')
}

/** True when the translation kept every token it was handed. */
export function tokensIntact(text: string, tokens: string[]): boolean {
  for (let i = 0; i < tokens.length; i++) {
    if (!text.includes(OPEN + i + CLOSE)) return false
  }
  return true
}
