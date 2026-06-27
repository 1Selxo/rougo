package com.selxo.rougo.windows

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class WindowsParityCoreTest {
    @Test
    fun urlExtractionNormalizesSupportedVideoLinks() {
        val urls = extractAllUrls("Watch https://youtu.be/abc123), then https://example.com")

        assertEquals("https://youtu.be/abc123", urls.first())
        assertTrue(isSupportedVideoLink(urls.first()))
        assertTrue(isYoutubeUrl(urls.first()))
    }

    @Test
    fun youtubeBrowserOpenableUrlIncludesPlaylistsLikeAndroid() {
        val playlistUrl = "https://www.youtube.com/playlist?list=PL123"

        assertEquals(playlistUrl, youtubeBrowserOpenableUrl(playlistUrl))
    }

    @Test
    fun providerDetectionCoversAndroidDownloadFirstSources() {
        assertTrue(isBilibiliUrl("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertTrue(isBilibiliUrl("https://b23.tv/abc123"))
        assertTrue(isNiconicoUrl("https://www.nicovideo.jp/watch/sm9"))
        assertTrue(isNiconicoUrl("https://nico.ms/sm9"))
    }

    @Test
    fun playlistImportPlanPreservesGroupAndChildFields() {
        var next = 0
        val plan = buildPlaylistImportPlan(
            playlistTitle = "Course",
            playlistUrl = "https://www.youtube.com/playlist?list=abc",
            entries = listOf(
                PlaylistImportEntry("Lesson 1", "https://youtu.be/1", durationMs = 10_000, index = 3),
                PlaylistImportEntry("Lesson 2", "https://youtu.be/2", durationMs = 20_000, index = 4)
            ),
            nextId = { "id-${next++}" }
        )

        assertEquals(LibraryItemKind.Folder, plan.group.itemKind)
        assertEquals("id-0", plan.group.id)
        assertEquals("https://www.youtube.com/playlist?list=abc", plan.group.sourceUrl)
        assertEquals("https://www.youtube.com/playlist?list=abc", plan.group.playlistSourceUrl)
        assertEquals("id-0", plan.children.first().parentId)
        assertEquals("https://www.youtube.com/playlist?list=abc", plan.children.first().playlistSourceUrl)
        assertEquals(3, plan.children.first().playlistItemIndex)
        assertEquals(20_000, plan.children[1].duration)
    }

    @Test
    fun playlistImportDataUsesAndroidTitleFallbacksAndDeduplicatesUrls() {
        val data = parseYoutubePlaylistImportData(
            JSONObject(
                """
                {
                  "playlist_title": "Fallback playlist",
                  "entries": [
                    {"fulltitle": "First full title", "webpage_url": "https://youtu.be/one"},
                    {"title": "Duplicate", "webpage_url": "https://youtu.be/one"},
                    {"id": "two2222"}
                  ]
                }
                """.trimIndent()
            )
        )

        assertEquals("Fallback playlist", data.title)
        assertEquals(listOf("https://youtu.be/one", "https://www.youtube.com/watch?v=two2222"), data.entries.map { it.sourceUrl })
        assertEquals("First full title", data.entries.first().title)
    }

    @Test
    fun librarySearchKeepsMatchingChildrenUnderTheirGroup() {
        val folder = buildLibraryFolder("Drama clips") { "folder-1" }
        val child = LibraryItem(
            id = "clip-1",
            title = "Target clip",
            mediaUri = "C:/clip.mp4",
            subtitleUri = null,
            progress = 0,
            duration = 0,
            isVideo = true,
            parentId = folder.id
        )

        val rows = libraryDisplayRows(
            listOf(folder, child),
            searchQuery = "target",
            selectedFilter = "All",
            sortMode = "Recent",
            collapsedFolderIds = setOf(folder.id)
        )

        assertEquals(listOf(folder.id, child.id), rows.map {
            when (it) {
                is LibraryDisplayRow.PlaylistGroup -> it.item.id
                is LibraryDisplayRow.Media -> it.item.id
            }
        })
        assertTrue((rows.first() as LibraryDisplayRow.PlaylistGroup).isExpanded)
    }

    @Test
    fun subtitleParserHandlesSrtAndLookup() {
        val dir = createTempDirectory("rougo-subtitles").toFile()
        val file = File(dir, "sample.srt")
        file.writeText(
            """
            1
            00:00:01,000 --> 00:00:02,500
            Hello world

            2
            00:00:03,000 --> 00:00:04,000
            Second line
            """.trimIndent()
        )

        val cues = parseSimpleSubtitles(file.absolutePath)

        assertEquals(2, cues.size)
        assertEquals("Hello world", findSubtitleCue(cues, 1_250)?.text)
        assertEquals("Second line", findSubtitleCue(cues, 3_500)?.text)
    }

    @Test
    fun subtitleParserReadsUtf8JapaneseLikeAndroid() {
        val dir = createTempDirectory("rougo-subtitles-ja").toFile()
        val file = File(dir, "sample-ja.srt")
        file.writeText(
            """
            1
            00:00:01,000 --> 00:00:02,500
            あの、最近ね、思うんですけれども。
            """.trimIndent(),
            Charsets.UTF_8
        )

        val cues = parseSimpleSubtitles(file.absolutePath)

        assertEquals("あの、最近ね、思うんですけれども。", findSubtitleCue(cues, 1_250)?.text)
    }

    @Test
    fun subtitleParserHandlesFileUris() {
        val dir = createTempDirectory("rougo-subtitle-uri").toFile()
        val file = File(dir, "uri sample.srt")
        file.writeText(
            """
            1
            00:00:01,000 --> 00:00:02,500
            File URI subtitle
            """.trimIndent()
        )

        val cues = parseSimpleSubtitles(file.toURI().toString())

        assertEquals("File URI subtitle", findSubtitleCue(cues, 1_500)?.text)
    }

    @Test
    fun subtitleDelayUsesAndroidDirection() {
        val cues = listOf(
            SubtitleCue(1_000, 1_500, "First"),
            SubtitleCue(1_750, 2_250, "Second")
        )

        assertEquals(
            "First",
            subtitleTextForPlayback(
                cues = cues,
                currentPosMs = 1_750,
                subtitleDelayMs = 250,
                isSubtitlesVisible = true,
                isParsingSubtitles = false,
                hasSubtitleFile = true
            )
        )
    }

    @Test
    fun libraryManagerRoundTripsAndroidCompatibleJsonFields() {
        val file = File(createTempDirectory("rougo-library").toFile(), "rougo_library.json")
        val manager = LibraryManager(file)
        val item = LibraryItem(
            id = "abc",
            title = "Media",
            mediaUri = "C:/media/test.mp4",
            subtitleUri = "C:/media/test.srt",
            progress = 123,
            duration = 456,
            isVideo = true,
            recordings = listOf(ShadowRecording(id = "rec", filePath = "C:/rec.m4a", startTime = 1, endTime = 2)),
            sourceUrl = "https://youtu.be/abc",
            formatId = "22",
            artist = "Artist",
            album = "Album",
            itemKind = LibraryItemKind.Media,
            parentId = "folder",
            playlistItemIndex = 7
        )

        manager.saveItem(item)
        val restored = manager.getItems().single()

        assertEquals(item.copy(recordings = item.recordings), restored)
    }

    @Test
    fun playbackPersistenceSavesStableSourceUrlForStreams() {
        val sourceUrl = "https://www.youtube.com/watch?v=source"
        val recording = ShadowRecording(filePath = "C:/recordings/voice.m4a", startTime = 1_000L, endTime = 3_000L)
        val item = LibraryItem(
            id = "item-1",
            title = "Source",
            mediaUri = "https://rr1---sn.example/videoplayback?expire=123",
            subtitleUri = null,
            progress = 0L,
            duration = 0L,
            isVideo = true,
            sourceUrl = sourceUrl
        )

        val decision = decidePlaybackStorageItem(
            item = item,
            progress = 2_000L,
            duration = 10_000L,
            recordings = listOf(recording),
            actualMediaUri = item.mediaUri,
            hasDownloadedLocalCopy = false
        )

        assertEquals(sourceUrl, decision.item.mediaUri)
        assertEquals(listOf(recording), decision.item.recordings)
        assertFalse(decision.blockedRecordingMediaUri)
    }

    @Test
    fun playbackPersistenceDoesNotLetRecordingPathReplaceSourceMedia() {
        val recording = ShadowRecording(filePath = "C:/recordings/voice.m4a", startTime = 1_000L, endTime = 3_000L)
        val item = LibraryItem(
            id = "item-1",
            title = "Source",
            mediaUri = "file:///C:/movies/source.mp4",
            subtitleUri = null,
            progress = 0L,
            duration = 0L,
            isVideo = true
        )

        val decision = decidePlaybackStorageItem(
            item = item,
            progress = 2_000L,
            duration = 10_000L,
            recordings = listOf(recording),
            actualMediaUri = "file:///C:/recordings/voice.m4a",
            hasDownloadedLocalCopy = false
        )

        assertEquals("file:///C:/movies/source.mp4", decision.item.mediaUri)
        assertTrue(decision.blockedRecordingMediaUri)
    }

    @Test
    fun themePreferencesPreserveAndroidLegacyLightModeFallback() {
        val prefs = JsonPrefs(File(createTempDirectory("rougo-prefs").toFile(), "app_prefs.json"))

        prefs.putBoolean(PREF_LIGHT_MODE, true)

        assertEquals(THEME_LIGHT, readThemeModePreference(prefs))

        writeThemeModePreference(prefs, THEME_DARK)

        assertEquals(THEME_DARK, readThemeModePreference(prefs))
        assertEquals(false, prefs.getBoolean(PREF_LIGHT_MODE, true))
    }

    @Test
    fun youtubeSourcePageUriForcesStreamResolutionBeforePlayback() {
        val sourceUrl = "https://www.youtube.com/watch?v=abc123"
        val unresolvedItem = LibraryItem(
            id = "yt",
            title = "Video",
            mediaUri = sourceUrl,
            subtitleUri = null,
            progress = 0,
            duration = 0,
            isVideo = true,
            sourceUrl = sourceUrl
        )
        val resolvedItem = unresolvedItem.copy(mediaUri = "https://rr1---sn.example/videoplayback?expire=4102444800")

        assertEquals(null, initialPlayableMediaUri(unresolvedItem))
        assertEquals(resolvedItem.mediaUri, initialPlayableMediaUri(resolvedItem))
    }

    @Test
    fun expiredYoutubeDirectStreamUriIsSkippedBeforePlayback() {
        val sourceUrl = "https://www.youtube.com/watch?v=abc123"
        val expiredStreamUrl = "https://rr1---sn.example/videoplayback?expire=100&itag=22"
        val freshStreamUrl = "https://rr1---sn.example/videoplayback?expire=4102444800&itag=22"
        val baseItem = LibraryItem(
            id = "yt",
            title = "Video",
            mediaUri = expiredStreamUrl,
            subtitleUri = null,
            progress = 0,
            duration = 0,
            isVideo = true,
            sourceUrl = sourceUrl
        )

        assertTrue(isExpiredYoutubeDirectStreamUrl(expiredStreamUrl))
        assertEquals(null, initialPlayableMediaUri(baseItem))
        assertEquals(freshStreamUrl, initialPlayableMediaUri(baseItem.copy(mediaUri = freshStreamUrl)))
    }

    @Test
    fun legacyYoutubeVideoOnlyFormatIsIgnoredForPlayback() {
        val sourceUrl = "https://www.youtube.com/watch?v=rwX0IYU5AA8"
        val legacyItem = LibraryItem(
            id = "yt-video-only",
            title = "Silent old item",
            mediaUri = "https://rr.example/videoplayback?itag=136",
            subtitleUri = null,
            progress = 9_000,
            duration = 303_000,
            isVideo = true,
            sourceUrl = sourceUrl,
            formatId = "136"
        )

        val normalized = legacyItem.withoutLegacyYoutubeVideoOnlyFormat()

        assertEquals(true, legacyItem.hasLegacyYoutubeVideoOnlyFormat())
        assertEquals(null, normalized.formatId)
        assertEquals(sourceUrl, normalized.mediaUri)
        assertEquals(null, initialPlayableMediaUri(legacyItem))
    }

    @Test
    fun youtubeFormatSelectionAvoidsVideoOnlyStreamsForVideoPlayback() {
        val videoOnly = YoutubeStreamFormat(
            formatId = "video-only",
            formatNote = "720p",
            ext = "mp4",
            vcodec = "avc1.64001f",
            acodec = "none",
            height = 720,
            tbr = 900,
            url = "https://example.com/video-only.mp4",
            manifestUrl = null,
            protocol = "https"
        )
        val combined = videoOnly.copy(
            formatId = "combined",
            formatNote = "360p",
            acodec = "mp4a.40.2",
            height = 360,
            tbr = 600,
            url = "https://example.com/combined.mp4"
        )

        assertEquals(combined, selectPreferredYoutubeFormat(listOf(videoOnly, combined), "720"))
    }

    @Test
    fun youtubeSubtitleCandidatesTryManualAndAutoFallbacks() {
        val choices = listOf(
            YoutubeSubtitleChoice("Japanese", "ja", false),
            YoutubeSubtitleChoice("Japanese auto", "ja", true),
            YoutubeSubtitleChoice("English", "en", false)
        )

        assertEquals(
            listOf("ja:false", "ja:true"),
            preferredYoutubeSubtitleChoices(choices, "ja").map { "${it.languageCode}:${it.isAutoGenerated}" }
        )
    }

    @Test
    fun youtubeSubtitleListOutputParsesManualAndAutomaticCaptions() {
        val output = """
            [info] Available subtitles for abc:
            Language Name        Formats
            ja       Japanese    vtt, ttml
            en       English     vtt
            [info] Available automatic captions for abc:
            Language Name        Formats
            ja       Japanese    vtt, ttml
            zh-Hant  Chinese     vtt
        """.trimIndent()

        assertEquals(
            listOf("ja:false", "en:false", "ja:true", "zh-Hant:true"),
            parseYoutubeSubtitleListOutput(output).map { "${it.languageCode}:${it.isAutoGenerated}" }
        )
    }

    @Test
    fun dictionaryTapExtractsWordsAndCjkChunks() {
        val cjkChunk = "\u6f22\u5b57\u304b\u306a"
        assertEquals("regularly", extractDictionaryLookupText("She speaks regularly.", 13, "en"))
        assertEquals(cjkChunk, extractDictionaryLookupText(cjkChunk, 0, "ja"))
        assertEquals("hilft schon viel mit", extractDictionaryLookupText("Er hilft schon viel mit.", 4, "de"))
    }

    @Test
    fun dictionaryTapCandidatesIncludeCjkWordsStartingBeforeTheTap() {
        val text = "\u751f\u6d3b\u3057\u3066\u3044\u307e\u3059"
        val candidates = subtitleLookupSelectionCandidates(text, text.indexOf('\u6d3b'), "ja")

        assertTrue(candidates.any { it.startIndex == 0 && it.query.startsWith("\u751f\u6d3b") })
        assertEquals("\u6d3b\u3057\u3066\u3044\u307e\u3059", candidates.first().query)
    }

    @Test
    fun pitchDetectionAcceptsQuietVoicedToneButRejectsNoise() {
        val sampleRate = 44_100
        val sine = ShortArray(sampleRate / 2) { index ->
            (sin(2.0 * PI * 220.0 * index / sampleRate) * 950.0).roundToInt().toShort()
        }
        val noiseRandom = Random(7)
        val noise = ShortArray(sampleRate / 2) {
            noiseRandom.nextInt(-950, 951).toShort()
        }

        val pitch = estimatePitch(sine, sampleRate)

        assertNotNull(pitch)
        assertTrue(pitch in 214f..226f)
        assertEquals(null, estimatePitch(noise, sampleRate))
    }

    @Test
    fun windowsNoiseCancellationUsesBundledRnnoiseModelWhenAvailable() {
        val model = File(createTempDirectory("rougo-rnnoise-model").toFile(), "std.rnnn").apply {
            writeText("model")
        }
        val args = recordingFfmpegArgs(
            ffmpeg = "ffmpeg",
            inputFile = File("C:/input.wav"),
            targetFile = File("C:/output.m4a"),
            noiseCancellationEnabled = true,
            rnnoiseModelFile = model
        )
        val filter = args[args.indexOf("-af") + 1]

        assertTrue(filter.startsWith("arnndn=m="))
        assertTrue(filter.contains("std.rnnn"))
        assertTrue(filter.contains("""\\\:"""))
        assertTrue(args.contains("48000"))
        assertTrue(args.contains("160k"))
    }

    @Test
    fun windowsNoiseCancellationDisabledDoesNotAddAudioFilter() {
        val args = recordingFfmpegArgs(
            ffmpeg = "ffmpeg",
            inputFile = File("C:/input.wav"),
            targetFile = File("C:/output.m4a"),
            noiseCancellationEnabled = false,
            rnnoiseModelFile = File("C:/models/std.rnnn")
        )

        assertFalse(args.contains("-af"))
    }
}
