package com.subtrans.app.net

import com.subtrans.app.subtitle.FileLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing is tested against responses captured from the live service, not
 * invented ones, so the shapes here are the shapes the app will actually meet.
 */
class GestdownTest {

    /** Trimmed from a real `/shows/search/breaking%20bad`. */
    private val showsJson = """
        {"shows":[
          {"id":"31ffb6ce-c000-4079-8912-b3f72057baed","name":"Breaking Bad",
           "nbSeasons":5,"seasons":[1,2,3,4,5],"tvDbId":81189,"tmdbId":1396,"slug":"breaking-bad"}
        ]}
    """.trimIndent()

    /** A real `/shows/search/naruto` — note the gaps in the season list. */
    private val gappySeasonsJson = """
        {"shows":[
          {"id":"e10af3ea-3e45-42a7-9fdd-48f15a011366","name":"Naruto Shippuden",
           "nbSeasons":3,"seasons":[17,3,8],"tvDbId":79824,"tmdbId":31910}
        ]}
    """.trimIndent()

    /** Trimmed from a real `/shows/{id}/1/English`. */
    private val seasonJson = """
        {"episodes":[
          {"season":1,"number":1,"title":"Pilot","show":"Breaking Bad",
           "discovered":"2022-05-17T02:28:20.083997Z",
           "subtitles":[
             {"subtitleId":"69cf7d79-052c-4f12-a57d-995d77de43ad","version":"0tv",
              "completed":true,"hearingImpaired":false,"corrected":true,"hd":false,
              "downloadUri":"/subtitles/download/69cf7d79-052c-4f12-a57d-995d77de43ad",
              "language":"English","downloadCount":427,"source":"Addic7ed","release":null},
             {"subtitleId":"bfb59770-e56e-4bd2-afb1-6ef62cff3848","version":"DVDRip ORPHEUS",
              "completed":false,"hearingImpaired":true,"corrected":true,"hd":false,
              "downloadUri":"/subtitles/download/bfb59770-e56e-4bd2-afb1-6ef62cff3848",
              "language":"English","downloadCount":101,"source":"Addic7ed","release":null}
           ]},
          {"season":1,"number":2,"title":"Cat's in the Bag...","show":"Breaking Bad",
           "subtitles":[
             {"subtitleId":"aaaa1111-0000-0000-0000-000000000001","version":"WEB",
              "completed":true,"hearingImpaired":false,"downloadCount":50,"source":"Addic7ed"}
           ]}
        ]}
    """.trimIndent()

    /* ------------------------------------------------------------- shows */

    @Test
    fun `reads a show out of a search response`() {
        val shows = parseShows(showsJson)
        assertEquals(1, shows.size)
        assertEquals("Breaking Bad", shows[0].title)
        assertEquals("31ffb6ce-c000-4079-8912-b3f72057baed", shows[0].id)
        assertEquals(listOf(1, 2, 3, 4, 5), shows[0].seasons)
    }

    /**
     * The real trap in this API: a long-running series can be held as seasons
     * 3, 8 and 17 with nothing before them. Defaulting to season 1 would show
     * an empty list for a series that is perfectly well covered, so the exact
     * numbers have to survive parsing, in order.
     */
    @Test
    fun `keeps the real season numbers, sorted, gaps and all`() {
        val shows = parseShows(gappySeasonsJson)
        assertEquals(listOf(3, 8, 17), shows[0].seasons)
        assertFalse(1 in shows[0].seasons)
    }

    @Test
    fun `an unknown series reads as nothing found, not as a crash`() {
        // The service answers 404 with a plain-text body; the client turns
        // that into an empty string rather than an error the user cannot act on.
        assertTrue(parseShows("").isEmpty())
        assertTrue(parseShows("Couldn't find show: boruto").isEmpty())
        assertTrue(parseShows("{\"shows\":[]}").isEmpty())
    }

    @Test
    fun `a malformed response is empty rather than fatal`() {
        assertTrue(parseShows("{ not json").isEmpty())
        assertTrue(parseSeason("<html>502</html>").isEmpty())
    }

    /* ---------------------------------------------------------- subtitles */

    @Test
    fun `reads every subtitle of every episode`() {
        val entries = parseSeason(seasonJson)
        assertEquals(3, entries.size)
        assertEquals(listOf(1, 1, 2), entries.map { it.episode })
    }

    @Test
    fun `carries the flags the picker sorts on`() {
        val entries = parseSeason(seasonJson)
        val first = entries[0]
        assertEquals(427, first.downloads)
        assertTrue(first.trusted)
        assertFalse(first.hearingImpaired)

        val second = entries[1]
        assertFalse(second.trusted)
        assertTrue(second.hearingImpaired)
    }

    @Test
    fun `picks the completed upload over the more obscure one`() {
        val best = bestPerEpisode(parseSeason(seasonJson))
        assertEquals(2, best.size)
        assertEquals("69cf7d79-052c-4f12-a57d-995d77de43ad", best[1]?.id)
    }

    /* ---------------------------------------------------------- file names */

    @Test
    fun `builds a name the app's own episode reader can parse back`() {
        val name = buildFileName("Breaking Bad", 1, 5, "Gray Matter")
        assertEquals("Breaking Bad - S01E05 - Gray Matter.srt", name)
        assertEquals(FileLabel.Episode(1, 5), FileLabel.episodeOf(name))
    }

    @Test
    fun `strips what a file name cannot carry`() {
        val name = buildFileName("Cat's: in/the\\Bag?", 1, 2, "A|B")
        assertFalse(name.contains(':'))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('?'))
        assertFalse(name.contains('|'))
        assertTrue(name.endsWith(".srt"))
    }

    @Test
    fun `copes with an episode that has no numbers or title`() {
        val name = buildFileName("", null, null, null)
        assertEquals("Subtitle.srt", name)
    }

    /* ---------------------------------------------------------- languages */

    @Test
    fun `language tags become the full names this service expects`() {
        assertEquals("English", languageName("en"))
        assertEquals("Bengali", languageName("bn"))
        assertEquals("English", languageName("en-US"))
        // Anything it does not carry falls back rather than failing.
        assertEquals("English", languageName("xx"))
    }

    /* ------------------------------------------------------------- source */

    @Test
    fun `this source never asks the user for anything`() {
        val source = Gestdown()
        assertFalse(source.needsKey)
        assertTrue(source.ready)
        assertEquals(null, source.setupHint)
    }

    @Test
    fun `OpenSubtitles reports itself unusable until a key is given`() {
        val without = OpenSubtitlesSource("")
        assertTrue(without.needsKey)
        assertFalse(without.ready)
        assertTrue(without.setupHint!!.isNotBlank())

        val with = OpenSubtitlesSource("some-key")
        assertTrue(with.ready)
        assertEquals(null, with.setupHint)
    }
}
