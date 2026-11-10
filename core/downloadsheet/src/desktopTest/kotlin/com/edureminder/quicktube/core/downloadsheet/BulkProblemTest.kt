package com.edureminder.quicktube.core.downloadsheet

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkProblemTest {
    @Test
    fun deletedAndPrivateVideosAreNotFound() {
        assertEquals(BulkStatus.NOT_FOUND, BulkDownloader.problem("This video is unavailable").first)
        assertEquals(BulkStatus.NOT_FOUND, BulkDownloader.problem("Video has been removed by the uploader").first)
        assertEquals("Private video: only its owner can see it", BulkDownloader.problem("This is a private video. Please sign in").second)
        assertEquals(BulkStatus.NOT_FOUND, BulkDownloader.problem("JSON response is too short").first)
        assertEquals(BulkStatus.NOT_FOUND, BulkDownloader.problem("This video was removed or is private. It can't be downloaded.").first)
    }

    @Test
    fun otherProblemsAreFailures() {
        assertEquals(BulkStatus.FAILED, BulkDownloader.problem("Live streams can't be downloaded until they end.").first)
        assertEquals("No connection: try again", BulkDownloader.problem("Unable to resolve host \"www.youtube.com\"").second)
        assertEquals(BulkStatus.FAILED, BulkDownloader.problem(null).first)
        assertEquals(BulkStatus.FAILED, BulkDownloader.problem("No video found. Try opening the page in the QuickTube browser and play the video.").first)
    }
}
