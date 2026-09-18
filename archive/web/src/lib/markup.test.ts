import { strict as assert } from 'node:assert'
import { describe, it } from 'node:test'
import { protect, restore, scrub, tokensIntact } from './markup.ts'

describe('markup protection', () => {
  it('hides ASS override blocks from the model and puts them back', () => {
    const p = protect('{\\an8}Watch out!')
    assert.ok(!p.text.includes('{'))
    assert.equal(p.tokens[0], '{\\an8}')
    assert.equal(restore(p.text, p.tokens), '{\\an8}Watch out!')
  })

  it('hides html-style tags', () => {
    const p = protect('<i>quietly</i>')
    assert.equal(p.tokens.length, 2)
    assert.equal(restore(p.text, p.tokens), '<i>quietly</i>')
  })

  it('hides ASS line breaks', () => {
    const p = protect('first line\\Nsecond line')
    assert.ok(!p.text.includes('\\N'))
    assert.equal(restore(p.text, p.tokens), 'first line\\Nsecond line')
  })

  it('leaves plain text completely alone', () => {
    const p = protect('Just words, nothing special.')
    assert.equal(p.text, 'Just words, nothing special.')
    assert.equal(p.tokens.length, 0)
    assert.equal(restore(p.text, p.tokens), 'Just words, nothing special.')
  })

  it('reattaches tokens after the text around them changed', () => {
    const p = protect('<i>Run!</i>')
    // What a translation looks like: markers kept, words replaced.
    const translated = p.text.replace('Run!', 'দৌড়াও!')
    assert.ok(tokensIntact(translated, p.tokens))
    assert.equal(restore(translated, p.tokens), '<i>দৌড়াও!</i>')
  })

  it('reports when the model dropped the markers', () => {
    const p = protect('{\\an8}Hello')
    assert.equal(tokensIntact('হ্যালো', p.tokens), false)
  })

  it('scrubs stray markers so control characters never reach the player', () => {
    const p = protect('{\\an8}Hello')
    const mangled = p.text.replace('Hello', 'হ্যালো').replace(/.$/, '')
    assert.ok(!scrub(mangled).includes(String.fromCharCode(0xe000)))
    assert.ok(!scrub(mangled).includes(String.fromCharCode(0xe001)))
  })

  it('keeps several tokens in order', () => {
    const p = protect('{\\i1}Hey{\\i0} — <b>you</b>')
    const translated = p.text.replace('Hey', 'এই').replace('you', 'তুমি')
    assert.equal(restore(translated, p.tokens), '{\\i1}এই{\\i0} — <b>তুমি</b>')
  })
})
