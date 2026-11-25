package com.luna.storynote.e2e

import com.luna.storynote.BookStudioController
import com.luna.storynote.DesktopPlatformEnvironment
import com.luna.storynote.PlatformAudioPlayer
import com.luna.storynote.e2e.fixtures.*
import com.luna.storynote.storage.StoryBookRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Tier4RealWorldScenariosTest {
    @Rule @JvmField val tmp = TemporaryFolder()
    private val disposables = mutableListOf<() -> Unit>()

    @After fun tearDown() {
        disposables.reversed().forEach { runCatching { it() } }
        disposables.clear()
    }

    private fun newEnv() = FakePlatformEnvironment(tmp.newFolder("env"))
    private fun newController(env: FakePlatformEnvironment) = BookStudioController(env).also { disposables.add { it.dispose() } }
    private fun newServer() = FakeAiGatewayServer().also { disposables.add { it.close() } }
    private fun uiState(c: BookStudioController): Any = c.uiState.value
    private fun activeBookId(c: BookStudioController) = ContractProbe.readProperty(uiState(c), "activeBookId") as? String
    private fun booksList(c: BookStudioController) = ContractProbe.readProperty(uiState(c), "books") as List<*>
    private fun clausesOf(c: BookStudioController) = ContractProbe.readProperty(ContractProbe.readProperty(uiState(c), "activeBook")!!, "clauses") as List<*>

    private fun openedController(env: FakePlatformEnvironment, bookId: String, expectedBooks: Int = 1): BookStudioController =
        newController(env).also { c ->
            awaitUntil { booksList(c).size >= expectedBooks }
            c.openBook(bookId)
        }

    private fun awaitUntil(timeoutMs: Long = 4000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25L)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", cond())
    }

    private fun seedUtterance(env: FakePlatformEnvironment, repo: StoryBookRepository, bookId: String, title: String, rawText: String, wavMs: Int): Int {
        val takeId = "take_${System.nanoTime()}"
        val takeWav = env.writeDeterministicWav(File(env.storageRoot, "staging/$takeId.wav"), wavMs)
        val (_, inserted) = repo.insertUtteranceWithClauses(bookId, null, rawText, takeId, takeWav, 0L, wavMs.toLong(), wavMs.toLong(), false)
        repo.updateBookTitleAndCover(bookId, title, null, 0, 0)
        awaitUntil { (ContractProbe.readBookJson(env.storageRoot, bookId).optJSONArray("clauses")?.length() ?: 0) >= inserted.size }
        return inserted.size
    }

    private fun commitSpeechIntoBook(env: FakePlatformEnvironment, c: BookStudioController, bookId: String, title: String, rawText: String, wavMs: Int = 3200) {
        val count = seedUtterance(env, c.repo, bookId, title, rawText, wavMs)
        c.cycleCoverStyle(bookId)
        awaitUntil { clausesOf(c).size >= count }
    }

    private fun seedSpeechBook(env: FakePlatformEnvironment, title: String, rawText: String, wavMs: Int = 3200): String {
        val repo = StoryBookRepository(env.storageRoot)
        val bookId = repo.createBlankBook().id
        seedUtterance(env, repo, bookId, title, rawText, wavMs)
        return bookId
    }

    private fun commitSpeechBook(env: FakePlatformEnvironment, title: String, rawText: String, wavMs: Int = 3200): Pair<BookStudioController, String> {
        val bookId = seedSpeechBook(env, title, rawText, wavMs)
        return openedController(env, bookId) to bookId
    }

    private fun clauseIds(env: FakePlatformEnvironment, bookId: String): List<String> {
        val j = ContractProbe.readBookJson(env.storageRoot, bookId)
        val arr = j.optJSONArray("clauses") ?: j.optJSONArray("sentences") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
    }

    private fun mkConfig(base: String, key: String, textModel: String, imgBase: String = "", imgKey: String = "", imgModel: String = ""): Any =
        ContractProbe.construct(ContractProbe.requireClass("AiGatewayConfig", "model"), base, key, textModel, imgBase, imgKey, imgModel)

    private fun saveAi(env: FakePlatformEnvironment, srv: FakeAiGatewayServer, key: String, withImg: Boolean = true) {
        val cfg = if (withImg) mkConfig(srv.baseUrl, key, "auto/deepseek-v4.1-flash", srv.baseUrl, key, "codex/gpt-image-2")
        else mkConfig(srv.baseUrl, key, "auto/deepseek-v4.1-flash")
        ContractProbe.saveAiConfig(env, cfg)
    }

    private fun saveBible(env: FakePlatformEnvironment, bookId: String, traits: List<String>, scenes: List<String>, plot: String) =
        ContractProbe.saveBookBible(env, bookId, traits, scenes, plot)

    private fun dispatchTool(c: BookStudioController, roleName: String, toolName: String, params: Map<String, Any?>): Any? =
        ContractProbe.dispatchTool(ContractProbe.resolveToolBus(c.env, c), roleName, toolName, params)

    private fun findIllustrationNode(frame: RenderedFrame): CapturedSemanticsNode? =
        frame.semanticsNodes.firstOrNull { n ->
            n.testTag.orEmpty().contains("illustration", ignoreCase = true) ||
                n.contentDescriptions.any { it.contains("插图") || it.contains("illustration", ignoreCase = true) }
        }

    @Test
    fun scenario01_phoneChildCreatesAndReadsMultiClauseStory() {
        val env = newEnv(); val c = newController(env)
        val story = "从前有一只蓝色的小恐龙，他住在一座会喷棉花糖的火山上，每天早上都会去找小狐狸玩耍。"
        HeadlessUiHarness.openSession(c, 390, 844).use { s ->
            assertTrue(s.renderFrames(12).hasTag("new_book_button"))
            s.tapTag("new_book_button")
            awaitUntil { activeBookId(c) != null }
            val bookId = activeBookId(c)!!
            commitSpeechIntoBook(env, c, bookId, "恐龙火山", story, 3200)

            val bj = ContractProbe.readBookJson(env.storageRoot, bookId)
            val clauses = bj.getJSONArray("clauses")
            assertTrue("Expected >= 3 clauses, got ${clauses.length()}", clauses.length() >= 3)
            assertTrue("Expected AudioTake persisted", bj.getJSONArray("takes").length() >= 1)
            for (i in 0 until clauses.length()) {
                assertTrue(clauses.getJSONObject(i).getString("text").length in 5..18)
            }

            val bookFrame = s.renderFrames(12)
            assertTrue(bookFrame.hasText("小恐龙") && bookFrame.hasTag("read_along_button"))
            s.tapTag("read_along_button")
            awaitUntil { env.createdPlayers.any { it.playedCalls.isNotEmpty() } }
            s.tapTag("back_button")
            val backShelf = s.renderFrames(12)
            assertTrue(backShelf.hasTag("book_card_$bookId") && backShelf.boundsForTag("book_card_$bookId")!!.widthDp > 140f)
        }
    }

    @Test
    fun scenario02_childEditsPhraseAndRestoresViaTimeMachine() {
        val env = newEnv()
        val (c, bookId) = commitSpeechBook(env, "恐龙吃糖", "小恐龙吃棉花糖。小狐狸看星星。", 2400)
        val beforeClauses = ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("clauses")
        val neighborSpanBefore = beforeClauses.getJSONObject(1).getJSONObject("audio_span").toString()

        ContractProbe.invoke(c, c::class.java, "onCharacterTapped", clausesOf(c).first()!!, 1)
        HeadlessUiHarness.openSession(c, 390, 844).use { s ->
            val card = s.renderFrames(12)
            assertTrue(card.hasTag("word_sheet_tab_0") && card.hasTag("word_sheet_tab_1"))
            val insp = ContractProbe.readProperty(uiState(c), "inspectedWord")!!
            ContractProbe.invoke(c, c::class.java, "applyHomophoneReplacement", insp, "孔龙")
            awaitUntil { ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("clauses").getJSONObject(0).getString("text").contains("孔龙") }
            assertEquals(neighborSpanBefore, ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("clauses").getJSONObject(1).getJSONObject("audio_span").toString())

            s.tapTag("time_machine_button")
            val tm = s.renderFrames(10)
            assertTrue(tm.hasTag("time_machine_sheet"))
            val wavs = ContractProbe.bookDir(env.storageRoot, bookId).walkTopDown().filter { it.isFile && it.extension == "wav" }.toList()
            var played = false
            val onDone: () -> Unit = { played = true }
            ContractProbe.invoke(env.createAudioPlayer(), PlatformAudioPlayer::class.java, "playRange", wavs.first(), 0L, 1000L, onDone)
            awaitUntil { played }
            s.tapTag(tm.testTags.first { it.startsWith("time_machine_restore_") })
            awaitUntil { ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("clauses").getJSONObject(0).getString("text").contains("恐龙") }
            assertTrue(s.renderFrames(10).hasText("恐龙"))
        }
    }

    @Test
    fun scenario03_childBookmarksPhrasesAndUsesWordBoxOffline() {
        val env = newEnv(); val srv = newServer()
        val p1 = "从前有一只蓝色小恐龙，住在一座会喷棉花糖的火山上。每天早上他都吃云朵冰淇淋，然后去找朋友们玩耍。"
        val p2 = "突然一艘银色宇宙飞船降落在山脚下，聪明的小狐狸跳出来邀请他一起去银河探险。他们坐着飞船穿过漂亮的彩色星星。"
        val (c, _) = commitSpeechBook(env, "两页探险", "$p1$p2", 4500)
        val clauses = clausesOf(c); assertTrue("Expected multi-page book", clauses.size >= 4)

        HeadlessUiHarness.openSession(c, 390, 844).use { s ->
            ContractProbe.invoke(c, c::class.java, "onCharacterTapped", clauses.first()!!, (ContractProbe.readProperty(clauses.first()!!, "text") as String).indexOf('恐'))
            s.renderFrames(8); s.tapTag("word_sheet_collect")
            val lastClause = clauses.last()!!
            c.setCurrentPage(1)
            ContractProbe.invoke(c, c::class.java, "onCharacterTapped", lastClause, (ContractProbe.readProperty(lastClause, "text") as String).indexOf('狐').coerceAtLeast(0))
            s.renderFrames(8); s.tapTag("word_sheet_collect")
            awaitUntil { ContractProbe.readWordBoxJson(env.storageRoot).length() >= 2 }

            s.tapTag("back_button"); s.tapTag("word_box_button")
            val wb = s.renderFrames(12)
            assertTrue(wb.hasText("恐龙") && !wb.hasTag("word_box_theater_button"))
            val cards = ContractProbe.readProperty(uiState(c), "collectedWords") as List<*>
            ContractProbe.invoke(c, c::class.java, "playCollectedWordExample", cards.first()!!)
            assertTrue(env.spokenWords.contains("恐龙"))
            awaitUntil { env.createdPlayers.any { it.playedCalls.isNotEmpty() } }
            assertEquals("Word Box must never call AI", 0, srv.capturedRequests.size)
        }
    }

    @Test
    fun scenario04_legacyV3BookMigratesAndEvolvesSafely() {
        val env = newEnv()
        env.writeV3LegacyBook("v3_fox", "小狐狸树洞", listOf("从前有一只小狐狸。" to 1100, "它住在暖暖的树洞里。" to 1200))
        val c = newController(env)
        awaitUntil { booksList(c).isNotEmpty() }
        c.openBook("v3_fox")
        val migrated = ContractProbe.readBookJson(env.storageRoot, "v3_fox")
        assertTrue(migrated.has("clauses") && migrated.has("takes"))
        val ids = clauseIds(env, "v3_fox")
        HeadlessUiHarness.openSession(c, 390, 844).use { s ->
            s.tapTag("clause_dot_${ids[1]}"); s.tapTag("edit_erase_button")
            awaitUntil { clauseIds(env, "v3_fox").size == 1 }
            assertTrue("Legacy WAV must survive TimeMachine GC", File(ContractProbe.bookDir(env.storageRoot, "v3_fox"), "clips/s_1.wav").isFile)
            s.tapTag("time_machine_button")
            val tm = s.renderFrames(10)
            s.tapTag(tm.testTags.first { it.startsWith("time_machine_restore_") })
            awaitUntil { clauseIds(env, "v3_fox").size == 2 }
            assertTrue(s.renderFrames(10).hasText("树洞"))
        }
    }

    @Test
    fun scenario05_parentConfiguresAiViaLongPressGearAndTestsConnection() {
        val env = newEnv(); val srv = newServer(); val c = newController(env)
        HeadlessUiHarness.openSession(c, 390, 844).use { s ->
            s.tapTag("settings_gear")
            assertFalse("Short tap must not open parent settings", s.renderFrames(8).hasTag("parent_settings_sheet"))
            s.longPressTag("settings_gear")
            val sheet = s.renderFrames(10)
            assertTrue("Long press opens parent settings", sheet.hasTag("parent_settings_sheet"))
            assertTrue(sheet.hasTag("parent_test_connection") && sheet.hasTag("parent_save_button"))

            val cfg = mkConfig(srv.baseUrl, "sk-e2e-dummy-parent-01", "auto/deepseek-v4.1-flash", srv.baseUrl, "sk-e2e-dummy-parent-01", "codex/gpt-image-2")
            val cliCls = ContractProbe.requireClass("AiGatewayClient", "ai")
            ContractProbe.invoke(ContractProbe.construct(cliCls), cliCls, "testConnection", cfg)
            awaitUntil { srv.capturedRequests.any { it.path == "/v1/models" } }
            assertEquals("Bearer sk-e2e-dummy-parent-01", srv.capturedRequests.first { it.path == "/v1/models" }.headerIgnoreCase("Authorization"))
            s.tapTag("parent_save_button")
            saveAi(env, srv, "sk-e2e-dummy-parent-01", withImg = true)
        }
        val reloaded = newController(env)
        awaitUntil { ContractProbe.readProperty(ContractProbe.readProperty(uiState(reloaded), "aiConfig")!!, "isConfigured") == true }
        val summary = ContractProbe.invoke(ContractProbe.readProperty(uiState(reloaded), "aiConfig")!!, ContractProbe.requireClass("AiGatewayConfig", "model"), "redactedSummary").toString()
        assertFalse(summary.contains("sk-e2e-dummy-parent-01"))
    }

    @Test
    fun scenario06_silentCopyEditorAnnotatesAndUpdatesBookBible() {
        val env = newEnv(); val srv = newServer()
        val bookId = seedSpeechBook(env, "坐火山", "我座在棉花糖火山旁看星星。")
        val cid = clauseIds(env, bookId).first()
        saveAi(env, srv, "sk-e2e-dummy-copy06", withImg = false)
        val c = openedController(env, bookId)
        srv.enqueueChatToolCalls(listOf(
            "annotate_soft_suggestion" to JSONObject().put("clause_id", cid).put("char_index", 1).put("length", 1).put("suggested_word", "坐").put("corrected_pinyin", "zuò").put("reason", "同音字"),
            "update_book_bible" to JSONObject().put("protagonist_traits", JSONArray().put("爱看星星的孩子")).put("key_scenes_and_props", JSONArray().put("棉花糖火山")).put("plot_summary", "坐在火山旁看星星"),
        ))
        ContractProbe.requireClass("SilentCopyEditorAgent", "ai")
        dispatchTool(c, "SILENT_COPY_EDITOR", "ANNOTATE_SOFT_SUGGESTION", mapOf("bookId" to bookId, "clauseId" to cid, "charIndex" to 1, "length" to 1, "suggestedWord" to "坐", "correctedPinyin" to "zuò", "reason" to "同音字"))
        dispatchTool(c, "SILENT_COPY_EDITOR", "UPDATE_BOOK_BIBLE", mapOf("bookId" to bookId, "protagonistTraits" to listOf("爱看星星的孩子"), "keyScenesAndProps" to listOf("棉花糖火山"), "plotSummary" to "坐在火山旁看星星"))
        awaitUntil { File(ContractProbe.bookDir(env.storageRoot, bookId), "book_bible.json").isFile }
        assertTrue(ContractProbe.readBookBibleJson(env.storageRoot, bookId).toString().contains("棉花糖火山"))
        assertEquals("我座在棉花糖火山旁看星星。", ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("clauses").getJSONObject(0).getString("text"))

        val c2 = openedController(env, bookId)
        ContractProbe.invoke(c2, c2::class.java, "onCharacterTapped", clausesOf(c2).first()!!, 1)
        HeadlessUiHarness.openSession(c2, 390, 844).use { s ->
            val f = s.renderFrames(12)
            val idxSit = f.semanticsTexts.indexOf("坐"); val idxDo = f.semanticsTexts.indexOf("做")
            assertTrue(idxSit >= 0 && (idxDo < 0 || idxSit < idxDo))
        }
    }

    @Test
    fun scenario07_childUsesLocalizedVoiceRevisionAndRollsBack() {
        val env = newEnv(); val srv = newServer()
        val bookId = seedSpeechBook(env, "魔法泡泡", "小恐龙拿着红色的泡泡机。")
        val cid = clauseIds(env, bookId).first()
        saveAi(env, srv, "sk-e2e-dummy-rev07", withImg = false)
        val c = openedController(env, bookId)
        ContractProbe.requireClass("VoiceRevisionAgent", "ai")

        HeadlessUiHarness.openSession(c, 390, 844).use { s ->
            s.tapTag("clause_dot_$cid")
            assertTrue(s.renderFrames(8).hasTag("edit_voice_revise_button"))
            dispatchTool(c, "VOICE_REVISION", "REPLACE_SPAN", mapOf("bookId" to bookId, "clauseId" to cid, "startIndex" to 5, "endIndex" to 7, "replacement" to "金色"))
            awaitUntil { ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("clauses").getJSONObject(0).getString("text") == "小恐龙拿着金色的泡泡机。" }
            assertTrue(s.renderFrames(10).hasText("金色的泡泡机"))

            s.tapTag("time_machine_button")
            val tm = s.renderFrames(10)
            s.tapTag(tm.testTags.first { it.startsWith("time_machine_restore_") })
            awaitUntil { ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("clauses").getJSONObject(0).getString("text") == "小恐龙拿着红色的泡泡机。" }
            assertTrue(s.renderFrames(10).hasText("红色的泡泡机"))
        }
    }

    @Test
    fun scenario08_crossPageConsistentWatercolorIllustrationAndCover() {
        val env = newEnv(); val srv = newServer()
        val p1 = "从前有一只蓝色圆滚滚小恐龙，穿着黄色雨靴在彩虹森林里散步，寻找会唱歌的小花朵。"
        val p2 = "突然天空飘下温暖的糖果雨，小恐龙撑开大荷叶伞和小狐狸一起在雨中跳舞。"
        val bookId = seedSpeechBook(env, "雨靴小恐龙", "$p1$p2", 4200)
        saveBible(env, bookId, listOf("蓝色圆滚滚小恐龙", "黄色雨靴"), listOf("彩虹森林", "大荷叶伞"), "森林雨中跳舞")
        saveAi(env, srv, "sk-e2e-dummy-illu08", withImg = true)
        val c = openedController(env, bookId)

        HeadlessUiHarness.openSession(c, 560, 900).use { s ->
            s.tapTag("illustrate_page_button")
            awaitUntil { srv.capturedRequests.count { it.path == "/v1/images/generations" } >= 1 }
            c.setCurrentPage(1); s.renderFrames(6)
            s.tapTag("illustrate_page_button")
            awaitUntil { srv.capturedRequests.count { it.path == "/v1/images/generations" } >= 2 }
            val prompts = srv.capturedRequests.filter { it.path == "/v1/images/generations" }.map { JSONObject(it.body).getString("prompt") }
            prompts.forEach { pr ->
                assertTrue("Trait lock missing: $pr", pr.contains("黄色雨靴") && (pr.contains("水彩") || pr.contains("watercolor")))
                assertTrue("Guardrail missing: $pr", pr.contains("no text") || pr.contains("无文字"))
            }
            c.setCurrentPage(0); s.renderFrames(6); s.tapTag("illustrate_page_button")
            awaitUntil { ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("illustrations").length() >= 3 }
            val illus = ContractProbe.readBookJson(env.storageRoot, bookId).getJSONArray("illustrations")
            val firstP0Id = (0 until illus.length()).map { illus.getJSONObject(it) }.first { it.getInt("page_index") == 0 }.getString("id")
            dispatchTool(c, "USER_DIRECT", "SELECT_PAGE_ILLUSTRATION", mapOf("bookId" to bookId, "pageIndex" to 0, "illustrationId" to firstP0Id))
        }
        c.returnToShelf()
        val tabletShelf = HeadlessUiHarness.renderStudio(c, 560, 900)
        assertTrue(tabletShelf.hasTag("book_card_$bookId") && tabletShelf.boundsForTag("book_card_$bookId")!!.widthDp < 220f && tabletShelf.distinctColorBuckets() >= 12)
    }

    @Test
    fun scenario09_stuckChildUsesCornerVoiceSparkWithoutTextMutation() {
        val env = newEnv(); val srv = newServer()
        val bookId = seedSpeechBook(env, "彩虹桥", "小恐龙噗噗走到了彩虹桥前面。")
        saveBible(env, bookId, listOf("小恐龙噗噗"), listOf("彩虹桥"), "站在桥前")
        saveAi(env, srv, "sk-e2e-dummy-spark09", withImg = false)
        val c = openedController(env, bookId)
        srv.enqueueChatText("噗噗接下来想带谁一起走过彩虹桥呢？")
        val beforeBook = File(ContractProbe.bookDir(env.storageRoot, bookId), "book.json").readText()

        HeadlessUiHarness.openSession(c, 390, 844).use { s ->
            s.tapTag("spark_star_button")
            awaitUntil { env.spokenWords.any { it.contains("彩虹桥") } }
            assertFalse("Voice Spark must have zero chat box UI", s.renderFrames(8).hasTag("chat_input"))
        }
        assertTrue(srv.capturedRequests.any { it.path == "/v1/chat/completions" && it.body.contains("噗噗") })
        assertEquals(beforeBook, File(ContractProbe.bookDir(env.storageRoot, bookId), "book.json").readText())
    }

    @Test
    fun scenario10_tabletRotationSpreadAndRuntimePrefillAndSecurityAudit() {
        val env = newEnv()
        val bookId = seedSpeechBook(env, "插画平板", "小恐龙在云朵上睡觉。")
        val plainId = seedSpeechBook(env, "纯字平板", "小鸟在枝头唱歌。")
        File(ContractProbe.bookDir(env.storageRoot, bookId), "illustrations").apply { mkdirs() }.let {
            File(it, "p0.png").writeBytes(FakeAiGatewayServer.createPngBytes(96, 96))
        }
        ContractProbe.dispatchTool(ContractProbe.resolveToolBus(env), "USER_DIRECT", "ATTACH_PAGE_ILLUSTRATION", mapOf("bookId" to bookId, "pageIndex" to 0, "imageFileName" to "illustrations/p0.png", "promptUsed" to "wc"))
        val c = openedController(env, bookId, expectedBooks = 2)
        val cid = clauseIds(env, bookId).first()
        val port = HeadlessUiHarness.renderStudio(c, 560, 900)
        assertTrue(findIllustrationNode(port)!!.bounds.bottomDp <= port.boundsForTag("clause_row_$cid")!!.topDp + 20f)
        val land = HeadlessUiHarness.renderStudio(c, 900, 560)
        assertTrue(findIllustrationNode(land)!!.bounds.centerX < land.boundsForTag("clause_row_$cid")!!.centerX)

        c.openBook(plainId)
        assertNull(findIllustrationNode(HeadlessUiHarness.renderStudio(c, 900, 560)))

        val home = tmp.newFolder("pi-home10")
        File(home, ".pi/agent").apply { mkdirs() }.let { pi ->
            File(pi, "models.json").writeText(JSONObject().put("providers", JSONObject().put("magpie-railway", JSONObject().put("baseUrl", "http://127.0.0.1:9999").put("apiKey", "sk-e2e-dummy-home10"))).toString())
            File(pi, "settings.json").writeText(JSONObject().put("defaultModel", "auto/deepseek-v4.1-flash").toString())
        }
        val prev = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val dEnv = DesktopPlatformEnvironment(storageRoot = tmp.newFolder("ds10"), authCacheFile = File(home, "auth.json"))
            val pre = ContractProbe.invoke(dEnv, DesktopPlatformEnvironment::class.java, "loadDefaultAiConfig")!!
            assertEquals("http://127.0.0.1:9999", ContractProbe.readProperty(pre, "baseUrl"))
            assertEquals("sk-e2e-dummy-home10", ContractProbe.readProperty(pre, "apiKey"))
        } finally {
            System.setProperty("user.home", prev)
        }

        val repoRoot = ContractProbe.findRepoRoot()
        val androidSrc = File(repoRoot, "app/src/androidMain/kotlin/com/luna/storynote/PlatformServices.android.kt").readText()
        assertFalse(androidSrc.contains("magpie") || androidSrc.contains("railway") || androidSrc.contains("sk-"))
        val proc = ProcessBuilder("bash", File(repoRoot, "e2e/check_static_hygiene.sh").absolutePath).directory(repoRoot).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals("Static hygiene check failed:\n$out", 0, proc.waitFor())
    }
}
