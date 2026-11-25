package com.luna.storynote.e2e.fixtures

import com.luna.storynote.PlatformAudioPlayer
import com.luna.storynote.PlatformEnvironment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking

object ContractProbe {
    private val PACKAGE_CANDIDATES = listOf(
        "com.luna.storynote",
        "com.luna.storynote.model",
        "com.luna.storynote.storage",
        "com.luna.storynote.literacy",
        "com.luna.storynote.agent",
        "com.luna.storynote.ai",
        "com.luna.storynote.audio",
        "com.luna.storynote.platform",
    )

    fun resolveClassOrNull(simpleName: String, preferredSubpackages: List<String> = emptyList()): Class<*>? {
        val searchOrder = (preferredSubpackages.map {
            if (it.isBlank()) "com.luna.storynote" else "com.luna.storynote.$it"
        } + PACKAGE_CANDIDATES).distinct()

        for (pkg in searchOrder) {
            val fqcn = "$pkg.$simpleName"
            try {
                return Class.forName(fqcn)
            } catch (_: ClassNotFoundException) {
            }
        }
        return null
    }

    fun requireClass(simpleName: String, vararg preferredSubpackages: String): Class<*> =
        resolveClassOrNull(simpleName, preferredSubpackages.toList())
            ?: throw AssertionError(
                "Missing contracted symbol '$simpleName' (searched packages: ${PACKAGE_CANDIDATES.joinToString()})",
            )

    fun hasClass(simpleName: String, vararg preferredSubpackages: String): Boolean =
        resolveClassOrNull(simpleName, preferredSubpackages.toList()) != null

    fun singletonOrNull(cls: Class<*>): Any? =
        try {
            cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        } catch (_: NoSuchFieldException) {
            null
        }

    fun construct(cls: Class<*>, vararg args: Any?): Any {
        val ctors = cls.declaredConstructors
        val match = ctors.firstOrNull { ctor ->
            ctor.parameterCount == args.size && argsMatch(ctor.parameterTypes, args)
        } ?: throw AssertionError(
            "No matching constructor on ${cls.simpleName} for ${args.size} args (${args.map { it?.javaClass?.simpleName }})",
        )
        match.isAccessible = true
        return try {
            match.newInstance(*args)
        } catch (ite: InvocationTargetException) {
            throw ite.targetException
        }
    }

    fun requireMethod(cls: Class<*>, methodName: String, paramCount: Int? = null): Method =
        (cls.methods + cls.declaredMethods).distinct().firstOrNull { m ->
            m.name == methodName && (paramCount == null || m.parameterCount == paramCount)
        } ?: throw AssertionError("Missing contracted method '${cls.simpleName}.$methodName'")

    fun invoke(target: Any?, cls: Class<*>, methodName: String, vararg args: Any?): Any? {
        val receiver = target ?: singletonOrNull(cls)
        val candidates = (cls.methods + cls.declaredMethods).distinct()
            .filter { it.name == methodName && it.parameterCount == args.size }
        val method = candidates.firstOrNull { argsMatch(it.parameterTypes, args) }
            ?: candidates.firstOrNull()
            ?: throw AssertionError(
                "Missing method '${cls.simpleName}.$methodName' with ${args.size} args",
            )
        method.isAccessible = true
        return try {
            if (Modifier.isStatic(method.modifiers)) {
                method.invoke(null, *args)
            } else {
                val instance = receiver ?: throw AssertionError(
                    "Method '${cls.simpleName}.$methodName' requires receiver or object INSTANCE",
                )
                method.invoke(instance, *args)
            }
        } catch (ite: InvocationTargetException) {
            throw ite.targetException
        }
    }

    fun readProperty(target: Any, propName: String): Any? {
        val cap = propName.replaceFirstChar { it.uppercaseChar() }
        val getter = target::class.java.methods.firstOrNull {
            (it.name == "get$cap" || it.name == propName) && it.parameterCount == 0
        }
        if (getter != null) {
            getter.isAccessible = true
            return getter.invoke(target)
        }
        var cur: Class<*>? = target::class.java
        while (cur != null) {
            try {
                val f = cur.getDeclaredField(propName)
                f.isAccessible = true
                return f.get(target)
            } catch (_: NoSuchFieldException) {
                cur = cur.superclass
            }
        }
        throw AssertionError("Property '$propName' not found on ${target::class.java.simpleName}")
    }

    fun findRepoRoot(): File {
        var cur: File? = File(System.getProperty("user.dir")).canonicalFile
        while (cur != null) {
            if (File(cur, "settings.gradle.kts").isFile && File(cur, "app/src").isDirectory) return cur
            cur = cur.parentFile
        }
        throw AssertionError("StoryNote repo root not found from ${System.getProperty("user.dir")}")
    }

    fun callReflect(target: Any?, cls: Class<*>, names: List<String>, vararg args: Any?): Any? {
        val methods = (cls.methods + cls.declaredMethods).distinct()
            .filter { !it.isSynthetic && !it.isBridge && it.name in names }
        val regCandidates = methods.filter { it.parameterCount == args.size }
        val reg = regCandidates.firstOrNull { argsMatch(it.parameterTypes, args) } ?: regCandidates.firstOrNull()
        if (reg != null) return invokeResolvedMethod(target, cls, reg, args)

        val suspCandidates = methods.filter {
            it.parameterCount == args.size + 1 && Continuation::class.java.isAssignableFrom(it.parameterTypes.last())
        }
        val susp = suspCandidates.firstOrNull { argsMatch(it.parameterTypes.copyOfRange(0, args.size), args) }
            ?: suspCandidates.firstOrNull()
            ?: throw AssertionError("Missing ${names.joinToString("/")} on ${cls.simpleName} for ${args.size} args")
        return invokeResolvedMethod(target, cls, susp, args)
    }

    internal fun invokeResolvedMethod(
        target: Any?,
        cls: Class<*>,
        method: Method,
        args: Array<out Any?>,
    ): Any? {
        method.isAccessible = true
        val recv = if (Modifier.isStatic(method.modifiers)) null else {
            target ?: singletonOrNull(cls)
                ?: throw AssertionError("Method '${cls.simpleName}.${method.name}' requires receiver or INSTANCE")
        }
        val isSusp = method.parameterTypes.lastOrNull()?.let { Continuation::class.java.isAssignableFrom(it) } == true
        if (!isSusp) {
            return try {
                method.invoke(recv, *args)
            } catch (ite: InvocationTargetException) {
                throw ite.targetException
            } catch (e: ReflectiveOperationException) {
                throw AssertionError("Failed invoking ${cls.simpleName}.${method.name}: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw AssertionError("Argument mismatch invoking ${cls.simpleName}.${method.name}: ${e.message}", e)
            }
        }
        return runBlocking {
            suspendCoroutine<Any?> { cont ->
                try {
                    val res = method.invoke(recv, *arrayOf(*args, cont))
                    if (res !== COROUTINE_SUSPENDED) cont.resumeWith(Result.success(res))
                } catch (ite: InvocationTargetException) {
                    cont.resumeWith(Result.failure(ite.targetException))
                } catch (t: Throwable) {
                    cont.resumeWith(
                        Result.failure(AssertionError("Failed suspend call ${cls.simpleName}.${method.name}: ${t.message}", t)),
                    )
                }
            }
        }
    }

    fun resolveToolBus(env: PlatformEnvironment, controller: Any? = null, repo: Any? = null): Any =
        AgentContractProbe.resolveToolBus(env, controller, repo)

    fun dispatchTool(bus: Any, roleName: String, toolName: String, params: Map<String, Any?>): Any? =
        AgentContractProbe.dispatchTool(bus, roleName, toolName, params)

    fun isToolSuccess(res: Any?): Boolean = AgentContractProbe.isToolSuccess(res)

    fun isToolAllowed(roleName: String, toolName: String): Boolean =
        AgentContractProbe.isToolAllowed(roleName, toolName)

    fun saveAiConfig(env: PlatformEnvironment, cfg: Any) =
        AgentContractProbe.saveAiConfig(env, cfg)

    fun saveBookBible(
        env: PlatformEnvironment,
        bookId: String,
        traits: List<String>,
        scenes: List<String>,
        plot: String,
    ) = AgentContractProbe.saveBookBible(env, bookId, traits, scenes, plot)

    fun invokeAiAgent(
        clsName: String,
        methodNames: List<String>,
        env: PlatformEnvironment,
        controller: Any?,
        cfg: Any,
        bookId: String,
        pageIndex: Int = 0,
        clauseId: String? = null,
        instruction: String? = null,
    ): Any? = AgentContractProbe.invokeAiAgent(
        clsName, methodNames, env, controller, cfg, bookId, pageIndex, clauseId, instruction,
    )

    fun invokeExpectingGatewayFailure(block: () -> Unit) =
        AgentContractProbe.invokeExpectingGatewayFailure(block)

    fun readActiveIllustrations(bookJson: JSONObject): JSONObject? =
        bookJson.optJSONObject("active_illustrations") ?: bookJson.optJSONObject("active_illustration_ids")

    // --- M1–M4 Contract Verification Probes ---

    fun verifyPlatformM1Extensions() {
        requireMethod(PlatformAudioPlayer::class.java, "playRange", paramCount = 4)
        requireMethod(PlatformEnvironment::class.java, "getPrivateConfigDir", paramCount = 0)
        requireMethod(PlatformEnvironment::class.java, "stopSpeaking", paramCount = 0)
        requireMethod(PlatformEnvironment::class.java, "loadDefaultAiConfig", paramCount = 0)
    }

    fun verifyBookEntityContracts() {
        val entities = listOf(
            "AudioTake",
            "AudioSpan",
            "Clause",
            "SoftAnnotation",
            "BookIllustration",
            "TimeMachineSnapshot",
            "BookBible",
        )
        for (name in entities) {
            requireClass(name, "model")
        }
        requireClass("TimeMachineStore", "storage")
        requireClass("BookBibleStore", "storage")
        requireClass("AiConfigStore", "storage")
    }

    fun verifyToolBusContracts(): Map<String, Any?> {
        val toolBusCls = requireClass("ToolBus", "agent")
        val whitelistsCls = requireClass("ToolWhitelists", "agent")
        val roleCls = requireClass("AgentTaskRole", "agent")
        val toolIdCls = resolveClassOrNull("ToolId", listOf("agent"))
        val requestCls = requireClass("ToolRequest", "agent")
        val resultCls = requireClass("ToolResult", "agent")

        val roles = roleCls.enumConstants?.map { it.toString() }.orEmpty()
        for (reqRole in listOf(
            "USER_DIRECT",
            "SILENT_COPY_EDITOR",
            "VOICE_REVISION",
            "ILLUSTRATION_COMPILER",
            "CORNER_VOICE_SPARK",
        )) {
            if (!roles.contains(reqRole)) {
                throw AssertionError("AgentTaskRole missing '$reqRole'; found: $roles")
            }
        }

        val toolIds = toolIdCls?.enumConstants?.map { it.toString() }.orEmpty()
        return mapOf(
            "toolBus" to toolBusCls,
            "whitelists" to whitelistsCls,
            "roles" to roles,
            "toolIds" to toolIds,
            "request" to requestCls,
            "result" to resultCls,
        )
    }

    fun verifyAiAgentContracts() {
        requireClass("AiGatewayClient", "ai")
        requireClass("SilentCopyEditorAgent", "ai")
        requireClass("VoiceRevisionAgent", "ai")
        requireClass("IllustrationAgent", "ai")
        requireClass("VoiceSparkAgent", "ai")
    }

    fun segmentWithJieba(text: String): List<String> {
        val cls = requireClass("JiebaWordSegmenter", "literacy")
        val method = (cls.methods + cls.declaredMethods).distinct().firstOrNull { m ->
            m.parameterCount == 1 &&
                m.parameterTypes[0] == String::class.java &&
                List::class.java.isAssignableFrom(m.returnType)
        } ?: throw AssertionError(
            "JiebaWordSegmenter must expose a 1-arg String -> List<*> segmentation method",
        )
        val raw = invoke(null, cls, method.name, text) as? List<*>
            ?: throw AssertionError("JiebaWordSegmenter.${method.name} returned null")
        return raw.map { item ->
            when (item) {
                is String -> item
                null -> ""
                else -> {
                    val wordProp = item::class.java.methods.firstOrNull {
                        (it.name == "getWord" || it.name == "getText") && it.parameterCount == 0
                    }
                    wordProp?.invoke(item)?.toString() ?: item.toString()
                }
            }
        }.filter { it.isNotEmpty() }
    }

    // --- Disk JSON Inspection Helpers ---

    fun bookDir(storageRoot: File, bookId: String): File =
        File(File(storageRoot, "books"), bookId)

    fun readBookJson(storageRoot: File, bookId: String): JSONObject {
        val file = File(bookDir(storageRoot, bookId), "book.json")
        if (!file.isFile) throw AssertionError("Missing book.json at ${file.absolutePath}")
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    fun readBookBibleJson(storageRoot: File, bookId: String): JSONObject {
        val file = File(bookDir(storageRoot, bookId), "book_bible.json")
        if (!file.isFile) throw AssertionError("Missing book_bible.json at ${file.absolutePath}")
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    fun readAiSettingsJson(configDir: File): JSONObject {
        val file = File(configDir, "ai_settings.json")
        if (!file.isFile) throw AssertionError("Missing ai_settings.json at ${file.absolutePath}")
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    fun readWordBoxJson(storageRoot: File): JSONArray {
        val file = File(storageRoot, "word_box.json")
        if (!file.isFile) throw AssertionError("Missing word_box.json at ${file.absolutePath}")
        return JSONArray(file.readText(Charsets.UTF_8))
    }

    fun listHistoryFiles(storageRoot: File, bookId: String): List<File> {
        val dir = File(bookDir(storageRoot, bookId), "history")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
    }

    fun readHistorySnapshots(storageRoot: File, bookId: String): List<JSONObject> {
        val files = listHistoryFiles(storageRoot, bookId)
        val snapshots = mutableListOf<JSONObject>()
        for (f in files) {
            if (!f.name.endsWith(".json") && !f.name.endsWith(".jsonl")) continue
            val text = f.readText(Charsets.UTF_8).trim()
            if (text.isEmpty()) continue
            when {
                text.startsWith("[") -> {
                    val arr = JSONArray(text)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { snapshots.add(it) }
                    }
                }
                text.startsWith("{") && f.name.endsWith(".json") -> {
                    val obj = JSONObject(text)
                    val nested = obj.optJSONArray("snapshots")
                    if (nested != null) {
                        for (i in 0 until nested.length()) {
                            nested.optJSONObject(i)?.let { snapshots.add(it) }
                        }
                    } else {
                        snapshots.add(obj)
                    }
                }
                else -> {
                    for (line in text.lineSequence()) {
                        val t = line.trim()
                        if (t.startsWith("{")) snapshots.add(JSONObject(t))
                    }
                }
            }
        }
        return snapshots
    }

    private fun argsMatch(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (paramTypes.size != args.size) return false
        for (i in paramTypes.indices) {
            val expected = box(paramTypes[i])
            val actual = args[i]
            if (actual == null) {
                if (paramTypes[i].isPrimitive) return false
            } else if (!expected.isAssignableFrom(actual.javaClass)) {
                return false
            }
        }
        return true
    }

    private fun box(cls: Class<*>): Class<*> = when (cls) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> cls
    }
}
