package com.edureminder.quicktube

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edureminder.quicktube.core.extractor.HlsClient
import com.edureminder.quicktube.core.model.StreamProtocol
import com.edureminder.quicktube.core.ytdlp.YtDlpExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Site-support check: for each site, extract formats with the bundled engine and download the first bytes
 * (or first HLS segment) of the chosen stream. Results are logged as "SITECHECK|site|PASS/FAIL|detail".
 * URLs are yt-dlp's own example URLs; some are old and may have been taken down.
 * Run: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.edureminder.quicktube.SiteSupportTest
 */
@RunWith(AndroidJUnit4::class)
class SiteSupportTest {
    private val sites = listOf(
        "vimeo" to "http://vimeo.com/56015672#at=0",
        "dailymotion" to "http://www.dailymotion.com/video/x5kesuj_office-christmas-party-review-jason-bateman-olivia-munn-t-j-miller_news",
        "twitter" to "https://twitter.com/i/cards/tfw/v1/623160978427936768",
        "reddit" to "https://www.reddit.com/r/videos/comments/6rrwyj/that_small_heart_attack/",
        "soundcloud" to "https://news.sophos.com/en-us/2023/08/10/s3-ep147-what-if-you-type-in-your-password-during-a-meeting/",
        "bandcamp" to "http://youtube-dl.bandcamp.com/track/youtube-dl-test-song",
        "twitch" to "http://www.twitch.tv/riotgames/v/6528877?t=5m10s",
        "ted" to "https://www.ted.com/talks/candace_parker_how_to_break_down_barriers_and_not_accept_limits",
        "bilibili" to "https://www.bilibili.com/video/BV13x41117TL",
        "streamable" to "https://streamable.com/dnd1",
        "imgur" to "https://imgur.com/A61SaA1",
        "tumblr" to "http://tatianamaslanydaily.tumblr.com/post/54196191430/orphan-black-dvd-extra-behind-the-scenes",
        "facebook" to "https://www.facebook.com/radiokicksfm/videos/3676516585958356/",
        "tiktok" to "https://www.tiktok.com/@leenabhushan/video/6748451240264420610",
        "vk" to "https://vk.com/videos-77521?z=video-77521_162222515%2Fclub77521",
        "rumble" to "https://rumble.com/embed/v5pv5f",
        "bitchute" to "https://www.bitchute.com/video/UGlrF9o9b-Q/",
        "archiveorg" to "http://archive.org/details/XD300-23_68HighlightsAResearchCntAugHumanIntellect",
        "cnn" to "https://www.cnn.com/2024/05/31/sport/video/jadon-sancho-borussia-dortmund-champions-league-exclusive-spt-intl",
        "espn" to "http://espn.go.com/video/clip?id=10365079",
        "ninegag" to "https://9gag.com/gag/ae5Ag7B",
        "coub" to "http://coub.com/view/5u5n1",
        "kick" to "https://kick.com/coringa",
        "peertube" to "https://framatube.org/videos/watch/9c9de5e8-0a1e-484a-b099-e80766180a6d",
        "flickr" to "http://www.flickr.com/photos/forestwander-nature-pictures/5645318632/in/photostream/",
        "likee" to "https://likee.video/@huynh_hong_quan_/video/7093444807096327263",
        "snapchat" to "https://www.snapchat.com/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYYWtidGhudGZpAX1TKn0JAX1TKnXJAAAAAA",
        "linkedin" to "https://www.linkedin.com/posts/mishalkhawaja_sendinblueviews-toronto-digitalmarketing-ugcPost-6850898786781339649-mM20",
        "pinterest" to "https://pinterest.com/pin/dive-into-serenity-blue-lagoon-pedi-nails-for-a-tranquil-and-refreshing-spa-experience-video-in-2024--2885187256207927",
        "niconico" to "https://www.nicovideo.jp/watch/1173108780",
        "mixcloud" to "http://www.mixcloud.com/dholbach/cryptkeeper/",
        "audiomack" to "http://www.audiomack.com/song/roosh-williams/extraordinary",
        "vevo" to "http://www.vevo.com/watch/hurts/somebody-to-die-for/GB1101300280",
        "dropbox" to "https://www.dropbox.com/s/nelirfsxnmcfbfh/youtube-dl%20test%20video%20%27%C3%A4%22BaW_jenozKc.mp4?dl=0",
        "googledrive" to "https://drive.google.com/file/d/0ByeS4oOUV-49Zzh4R1J6R09zazQ/edit?pli=1",
        "box" to "https://mlssoccer.app.box.com/s/0evd2o3e08l60lr4ygukepvnkord1o1x/file/510727257538",
        "loom" to "https://www.loom.com/community/e1229802a8694a09909e8ba0fbb6d073-pg",
        "jwplatform" to "http://content.jwplatform.com/players/nPripu9l-ALJ3XQCI.js",
        "brightcove" to "http://players.brightcove.net/929656772001/e41d32dc-ec74-459e-a845-6c69f7b724ea_default/index.html?videoId=4463358922001",
        "ustream" to "http://www.ustream.tv/recorded/20274954",
        "periscope" to "https://www.periscope.tv/LularoeHusbandMike/1mrGmgaXAVqxy",
        "gamespot" to "http://www.gamespot.com/videos/arma-3-community-guide-sitrep-i/2300-6410818/",
        "cbsnews" to "https://www.cbsnews.com/embed/video/?v=1.c9b5b61492913d6660db0b2f03579ef25e86307a#1Vb7b9s2EP5XBAHbT6Gt98PAMKTJ0se6LVjWYWtdGBR1stlIpEBSTtwi%2F%2FvuJNkNhmHdGxgM2NL57vjd6zt%2B8PngdN%2Fyg79qeGvhzN%2FLGrS%2F%2BuBLB531V28%2B%2BO7Qg7%2Fy97r2z3xZ42NW8yLhDbA0S0KWlHnIijwKWJBHZZnHBa8Cgbpdf%2F89NM9Hi9fXifhpr8sr%2FlP848tn%2BTdXycX25zh4cdX%2FvHl6PmmPqnWQv9w8Ed%2B9GjYRim07bFEqdG%2BZVHuwTm65A7bVRrYtR5lAyMox7pigF6W4k%2By91mjspGsJ%2BwVae4%2BsvdnaO1p73HkXs%2FVisUDTGm7R8IcdnOROeq%2B19qT1amhA1VJtPenoTUgrtfKc9m7Rq8dP7nnjwOB7wg7ADdNt7VX64DWAWlKhPtmDEq22g4GF99x6Dk9E8OSsankHXqPNKDxC%2FdK7MLKTircTDgsI3mmj4OBdSq64dy7fd1x577RU1rt4cvMtOaulFYOd%2FLewRWvDO9lIgXFpZSnkZmjbv5SxKTPoQXClFbpsf%2Fhbbpzs0IB3vb8KkyzJQ%2BywOAgCrMpgRrz%2BKk4fvb7kFbR4XJCu0gAdtNO7woCwZTu%2BBUs9bam%2Fds71drVerpeisgrubLjAB4nnOSkWQnfr5W6o1ku5Xpr1MgrCbL0M0vUyDtfLLK15WiYp47xKWSLyjFVpwVmVJSLIoCjSOFkv3W7oKsVliwZJcB9nwXpZ5GEQQwY8jNKqKCBrgjTLeFxgdCIpazojDgnRtn43J6kG7nZ6cAbxh0EeFFk4%2B1u867cY5u4344n%2FxXjCqAjucdTHgLKojNKmSfO8KRsOFY%2FzKEYCKEJBzv90QA9nfm9gL%2BHulaFqUkz9ULUYxl62B3U%2FRVNLA8IhggaPycOoBuwOCESciDQVSSUgiOMsROB%2FhKfwCKOzEk%2B4k6rWd4uuT%2FwTDz7K7t3d3WLO8ISD95jSPQbayBacthbz86XVgxHwhex5zawzgDOmtp%2F3GPcXn0VXHdSS029%2Fj99UC%2FwJUvyKQ%2FzKyixIEVlYJOn4RxxuaH43Ty9fbJ5OObykHH435XAzJTHeOF4hhEUXD8URe%2FQ%2FBT%2BMpf8d5GN02Ox%2FfiGsl7TA7POu1xZ5%2BbTzcAVKMe48mqcC21hkacVEVScM26liVVBnrKkC4CLKyzAvHu0lhEaTKMFwI3a4SN9MsrfYzdBLq2vkwRD1gVviLT8kY9h2CHH6Y%2Bix6609weFtey4ESp60WtyeWMy%2BsmBuhsoKIyuoT%2Bq2R%2FrW5qi3g%2FvzS2j40DoixDP8%2BKP0yUdpXJ4l6Vla%2Bg9vce%2BC4yM5YlUcbA%2F0jLKdpmTwvsdN5z88nAIe08%2F0HgxeG1iv%2B6Hlhjh7uiW0SDzYNI92L401uha3JKYk268UVRzdOzNQvAaJqoXzAc80dAV440NZ1WVVAAMRYQ2KrGJFmDUsq8saWSnjvIj8t78y%2FRa3JRnbHVfyFpfwoDiGpPgjzekyUiKNlU3OMlwuLMmzgvEojllYVE2Z1HhImvsnk%2BuhusTEoB21PAtSFodeFK3iYhXEH9WOG2%2FkOE833sfeG%2Ff5cfHtEFNXgYes0%2FXj7aGivUgJ9XpusCtoNcNYVVnJVrrDo0OmJAutHCpuZul4W9lLcfy7BnuLPT02%2ByXsCTk%2B9zhzswIN04YueNSK%2BPtM0jS88QdLqSLJDTLsuGZJNolm2yO0PXh3UPnz9Ix5bfIAqxPjvETQsDCEiPG4QbqNyhBZISxybLnZYCrW5H3Axp690%2F0BJdXtDZ5ITuM4xj3f4oUHGzc5JeJmZKpp%2FjwKh4wMV%2FV1yx3emLoR0MwbG4K%2F%2BZgVep3PnzXGDHZ6a3i%2Fk%2BJrONDN13%2Bnq6tBTYk4o7cLGhBtqCC4KwacGHpEVuoH5JNro%2FE6JfE6d5RydbiR76k%2BW5wioDHBIjw1euhHjUGRB0y5A97KoaPx6MlL%2BwgboUVtUFRI%2FLemgTpdtF59ii7pab08kuPcfWzs0l%2FRI5takWnFpka0zOgWRtYcuf9aIxZMxlwr6IiGpsb6j2DQUXPl%2FimXI599Ev7fWjoPD78A",
        "abcnews" to "http://abcnews.go.com/ThisWeek/video/week-exclusive-irans-foreign-minister-zarif-20411932",
        "nbc" to "http://www.nbc.com/the-tonight-show/video/jimmy-fallon-surprises-fans-at-ben-jerrys/2848237",
        "foxnews" to "https://video.foxnews.com/v/6320653836112",
        "reuters" to "http://www.reuters.com/video/2016/05/20/san-francisco-police-chief-resigns?videoId=368575562",
        "bloomberg" to "https://www.bloomberg.com/news/videos/2021-09-14/apple-unveils-the-new-iphone-13-stock-doesn-t-move-much-video",
        "aljazeera" to "https://balkans.aljazeera.net/videos/2021/11/6/pojedini-domovi-u-sarajevu-jos-pod-vodom-mjestanima-se-dostavlja-hrana",
        "dw" to "http://www.dw.com/en/no-hope-limited-options-for-refugees-in-idomeni/a-19111009",
        "arte" to "https://www.arte.tv/fr/videos/109067-000-A/la-loi-de-teheran/",
        "ndtv" to "https://khabar.ndtv.com/video/show/prime-time/prime-time-ill-system-and-poor-education-468818",
        "hotstar" to "https://www.hotstar.com/can-you-not-spread-rumours/1000076273",
        "mxplayer" to "https://www.mxplayer.in/show/watch-campus-beats/seasons/season-4-925794ae840c597b3c36f2d8f2f138b8",
        "sonyliv" to "https://www.sonyliv.com/shows/bachelors-delight-1700000113/achaari-cheese-toast-1000022678?watch=true",
        "bluesky" to "https://bsky.app/profile/blu3blue.bsky.social/post/3l4omssdl632g",
        "telegram" to "https://t.me/europa_press/613",
        "vidio" to "http://www.vidio.com/watch/165683-dj_ambred-booyah-live-2015",
        "rcti" to "https://www.rctiplus.com/programs/1259/kiko-untuk-lola/episode/22124/untuk-lola",
        "iqiyi" to "http://www.iqiyi.com/v_19rrojlavg.html",
        "youku" to "http://v.youku.com/v_show/id_XNjA1NzA2Njgw.html",
        "qqmusic" to "https://y.qq.com/n/ryqq/songDetail/004Ti8rT003TaZ",
        "xiaohongshu" to "https://www.xiaohongshu.com/explore/6411cf99000000001300b6d9",
        "acfun" to "https://www.acfun.cn/v/ac35457073",
        "huya" to "https://www.huya.com/572329",
        "afreecatv" to "https://vod.sooplive.com/player/192805325",
        "naver" to "http://tv.naver.com/v/81652",
        "kakao" to "http://tv.kakao.com/channel/2671005/cliplink/301965083",
        "wwe" to "https://www.wwe.com/videos/daniel-bryan-vs-andrade-cien-almas-smackdown-live-sept-4-2018",
        "nfl" to "https://www.nfl.com/videos/baker-mayfield-s-game-changing-plays-from-3-td-game-week-14",
        "nba" to "http://watch.nba.com/embed?id=659395",
        "mlb" to "https://www.mlb.com/mariners/video/ackleys-spectacular-catch/c-34698933",
        "fifa" to "https://www.fifa.com/fifaplus/en/watch/7on10qPcnyLajDDU3ntg6y",
        "olympics" to "https://olympics.com/fr/video/men-s-109kg-group-a-weightlifting-tokyo-2020-replays",
        "eurosport" to "https://www.eurosport.com/tennis/roland-garros/2022/highlights-rafael-nadal-brushes-aside-caper-ruud-to-win-record-extending-14th-french-open-title_vid1694147/video.shtml",
        "motorsport" to "http://www.motorsport.com/f1/video/main-gallery/red-bull-racing-2014-rules-explained/",
        "dtube" to "https://d.tube/#!/v/broncnutz/x380jtr1",
        "lbry" to "https://odysee.com/@gardeningincanada:b/plants-i-will-never-grow-again.-the:e",
        "minds" to "https://www.minds.com/media/100000000000086822",
        "gab" to "https://gab.com/SomeBitchIKnow/posts/107163961867310434",
        "parler" to "https://parler.com/feed/df79fdba-07cc-48fe-b085-3293897520d7",
        "truth" to "https://truthsocial.com/@realDonaldTrump/posts/108779000807761862",
        "gettr" to "https://www.gettr.com/post/pcf6uv838f",
        "rokfin" to "https://www.rokfin.com/post/57548/Mitt-Romneys-Crazy-Solution-To-Climate-Change",
        "vidlii" to "https://www.vidlii.com/watch?v=tJluaH4BJ3v",
        "dlive" to "https://dlive.tv/p/pdp+3mTzOl4WR",
        "floatplane" to "https://www.floatplane.com/post/2Yf3UedF7C",
        "nebula" to "https://nebula.tv/videos/that-time-disney-remade-beauty-and-the-beast",
        "curiositystream" to "http://app.curiositystream.com/video/2",
        "patreon" to "http://www.patreon.com/creation?hid=754133",
        "substack" to "https://haleynahman.substack.com/p/i-made-a-vlog?s=r",
        "wordpress" to "https://pianoadventures.com/products/piano-adventures-level-1-lesson-book-enhanced-cd/",
        "blogger" to "https://www.blogger.com/video.g?token=AD6v5dzEe9hfcARr5Hlq1WTkYy6t-fXH3BBahVhGvVHe5szdEUBEloSEDSTA8-b111089KbfWuBvTN7fnbxMtymsHhXAXwVvyzHH4Qch2cfLQdGxKQrrEuFpC1amSl_9GuLWODjPgw",
        "canalplus" to "https://www.mycanal.fr/d17-emissions/lolywood/p/1397061",
        "rai" to "https://www.raiplay.it/video/2014/04/Report-del-07042014-cb27157f-9dd0-4aee-b788-b1f67643a391.html",
        "mediaset" to "https://mediasetinfinity.mediaset.it/video/matrix/puntata-del-25-maggio_F309013801000501",
        "rtve" to "http://www.rtve.es/alacarta/videos/la-aventura-del-saber/aventuraentornosilla/3088905/",
        "rtp" to "http://www.rtp.pt/play/p405/e174042/paixoes-cruzadas",
        "ceskatelevize" to "http://www.ceskatelevize.cz/ivysilani/10441294653-hyde-park-civilizace/215411058090502/bonus/20641-bonus-01-en",
        "ard" to "https://www.ardmediathek.de/video/filme-im-mdr/liebe-auf-vier-pfoten/mdr-fernsehen/Y3JpZDovL21kci5kZS9zZW5kdW5nLzI4MjA0MC80MjIwOTEtNDAyNTM0",
        "zdf" to "https://www.zdf.de/gesellschaft/aktenzeichen-xy-ungeloest",
        "orf" to "https://radiothek.orf.at/ooe/20220801/OGMO",
        "svt" to "https://www.svtplay.se/video/eXYgwZb/sverige-och-kriget/1-utbrottet",
        "nrk" to "https://tv.nrk.no/program/MDDP12000117",
        "ruv" to "https://www.ruv.is/sjonvarp/spila/ithrottir/30657/9jcnd4",
        "err" to "https://arhiiv.err.ee/video/kontsertpalad",
        "lrt" to "https://www.lrt.lt/mediateka/tiesiogiai/lrt-opus",
        "lsm" to "https://latvijasradio.lsm.lv/lv/embed/?theme=black&size=16x9&showCaptions=0&id=183522",
        "tvn24" to "http://www.tvn24.pl/wiadomosci-z-kraju,3/oredzie-artura-andrusa,702428.html",
        "novaplay" to "https://play.nova.bg/video/ochakvaite/season-0/ochakvaite-2022-07-22-sybudi-se-sat/606627",
        "rtlnl" to "https://www.rtl.lu/tele/de-journal-vun-der-tele/v/3266757.html",
        "npo" to "http://www.npo.nl/nieuwsuur/22-06-2014/VPWON_1220719",
        "vrt" to "https://www.vrt.be/vrtnws/nl/2019/05/15/beelden-van-binnenkant-notre-dame-een-maand-na-de-brand/",
        "rts" to "http://www.rts.ch/archives/tv/divers/3449373-les-enfants-terribles.html",
        "dailymotion2" to "https://www.dailymotion.com/video/x8fmi9x",
        "vimeo2" to "https://vimeo.com/76979871",
        "archive2" to "https://archive.org/details/BigBuckBunny_124",
        "peertube2" to "https://peertube.tv/w/9c9de5e8-0a1e-484a-b099-e80766180a6d",
    )

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    @Test
    fun checkSites(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = YtDlpExtractor(ctx).also { it.init() }
        Log.i("SITECHECK", "SITECHECK|ENGINE|before|${engine.version()}")
        Log.i("SITECHECK", "SITECHECK|ENGINE|after|${engine.update()}")
        val hls = HlsClient(http)
        val gate = Semaphore(4)
        val out = java.io.File(ctx.getExternalFilesDir(null), "sitecheck.txt").apply { writeText("") }
        val results = sites.map { (name, url) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val r = withTimeoutOrNull(90_000) { check(engine, hls, url) } ?: ("FAIL" to "timeout")
                    Log.i("SITECHECK", "SITECHECK|$name|${r.first}|${r.second}")
                    synchronized(out) { out.appendText("$name|${r.first}|${r.second}\n") }
                    r.first
                }
            }
        }.awaitAll()
        Log.i("SITECHECK", "SITECHECK|TOTAL|${results.count { it == "PASS" }}/${results.size}|done")
        out.appendText("TOTAL|${results.count { it == "PASS" }}/${results.size}\n")
    }

    private suspend fun check(engine: YtDlpExtractor, hls: HlsClient, url: String): Pair<String, String> {
        val page = engine.extract(url) ?: return "FAIL" to "no formats (site changed, dead link, geo/login)"
        val opt = page.options.lastOrNull { it.kind.name == "VIDEO" } ?: page.options.first()
        return runCatching {
            val target = if (opt.protocol == StreamProtocol.HLS) hls.loadMedia(opt.url, page.headers).let { m -> m.init?.url ?: m.segments.first().url } else opt.url
            val req = Request.Builder().url(target).header("Range", "bytes=0-65535").apply { page.headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(req).execute().use { r ->
                val n = r.body.bytes().size
                if (r.isSuccessful && n > 1000) "PASS" to "${page.options.size} formats, ${opt.title}, ${opt.protocol}, got ${n}B"
                else "FAIL" to "stream HTTP ${r.code} (${n}B)"
            }
        }.getOrElse { "FAIL" to "stream error: ${it.message}" }
    }
}
