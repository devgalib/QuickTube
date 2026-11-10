package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.model.Chapter
import com.edureminder.quicktube.core.model.SkipSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {
    @Test
    fun descriptionTimesBecomeChapters() {
        val d = "Thanks for watching!<br>0:00 Intro<br>1:15 - Unboxing<br>(12:30) Camera test<br>1:02:03 Verdict<br>Follow me"
        assertEquals(
            listOf(Chapter("Intro", 0), Chapter("Unboxing", 75_000), Chapter("Camera test", 750_000), Chapter("Verdict", 3_723_000)),
            Chapters.fromDescription(d, 4_000_000),
        )
        // Not starting at 0:00, too few, or out of order: not chapters.
        assertTrue(Chapters.fromDescription("1:00 a<br>2:00 b<br>3:00 c", 0).isEmpty())
        assertTrue(Chapters.fromDescription("0:00 a<br>2:00 b", 0).isEmpty())
        assertTrue(Chapters.fromDescription("0:00 a<br>3:00 b<br>2:00 c", 0).isEmpty())
    }

    @Test
    fun sponsorBlockAnswerForThisVideoOnly() {
        assertEquals(4, SponsorBlock.hashPrefix("dQw4w9WgXcQ").length)
        val json = """[{"videoID":"other","segments":[{"segment":[1,9],"category":"sponsor"}]},
            {"videoID":"abc","segments":[{"segment":[30.5,60.0],"category":"selfpromo"},{"segment":[0,12],"category":"intro"},{"segment":[5,5.2],"category":"sponsor"}]}]"""
        assertEquals(listOf(SkipSegment(0, 12_000, "intro"), SkipSegment(30_500, 60_000, "selfpromo")), SponsorBlock.parse(json, "abc"))
        assertTrue(SponsorBlock.parse("not json", "abc").isEmpty())
        assertEquals("Skipped self-promotion", SponsorBlock.label("selfpromo"))
    }
}
