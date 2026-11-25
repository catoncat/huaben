package com.luna.storynote.e2e.fixtures

import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.BookBible
import com.luna.storynote.storage.AiConfigStore
import com.luna.storynote.storage.BookBibleStore
import com.luna.storynote.storage.StoryBookRepository
import com.luna.storynote.storage.TimeMachineStore
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

internal object AgentContractProbe {
    private fun norm(s: String): String = s.lowercase().replace("_", "")

    private fun resolveEnum(cls: Class<*>, name: String): Any? {
        val target = norm(name)
        return cls.enumConstants?.firstOrNull { c ->
            norm(c.toString()) == target ||
                runCatching { ContractProbe.readProperty(c, "toolName")?.toString()?.let { norm(it) == target } == true }
                    .getOrDefault(false) ||
                runCatching { ContractProbe.readProperty(c, "id")?.toString()?.let { norm(it) == target } == true }
                    .getOrDefault(false)
        }
    }

    fun resolveToolBus(env: PlatformEnvironment, controller: Any? = null, repo: Any? = null): Any {
        if (controller != null) {
            try {
                ContractProbe.readProperty(controller, "toolBus")?.let { return it }
            } catch (_: AssertionError) {
            }
        }
        val toolBusCls = ContractProbe.requireClass("ToolBus", "agent")
        val effectiveRepo = repo
            ?: controller?.let { runCatching { ContractProbe.readProperty(it, "repo") }.getOrNull() }
            ?: StoryBookRepository(env.storageRoot)
        val booksDir = File(env.storageRoot, "books")
        val pool = listOf(effectiveRepo, TimeMachineStore(booksDir), BookBibleStore(booksDir), env, env.storageRoot)
        return constructFromPool(toolBusCls, pool)
    }

    fun dispatchTool(bus: Any, roleName: String, toolName: String, params: Map<String, Any?>): Any? {
        val roleCls = ContractProbe.requireClass("AgentTaskRole", "agent")
        val role = resolveEnum(roleCls, roleName)
            ?: throw AssertionError("Unknown AgentTaskRole '$roleName'")
        val toolIdCls = ContractProbe.resolveClassOrNull("ToolId", listOf("agent"))
        val toolVal: Any = toolIdCls?.let {
            resolveEnum(it, toolName) ?: throw AssertionError("Unknown ToolId '$toolName'")
        } ?: toolName
        val reqCls = ContractProbe.requireClass("ToolRequest", "agent")
        val req = buildToolRequest(reqCls, roleCls, role, toolIdCls, toolVal, toolName, params)
        val methods = (bus::class.java.methods + bus::class.java.declaredMethods).distinct()
            .filter { !it.isSynthetic && !it.isBridge && it.name in setOf("dispatch", "execute", "invoke") }
        val twoArg = methods.firstOrNull {
            it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(roleCls) && it.parameterTypes[1].isAssignableFrom(reqCls)
        }
        val oneArg = methods.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(reqCls) }
        val chosen = twoArg ?: oneArg
            ?: throw AssertionError("ToolBus missing dispatch/execute/invoke method accepting ToolRequest")
        chosen.isAccessible = true
        return try {
            if (chosen.parameterCount == 2) chosen.invoke(bus, role, req) else chosen.invoke(bus, req)
        } catch (ite: InvocationTargetException) {
            throw ite.targetException
        } catch (e: ReflectiveOperationException) {
            throw AssertionError("Failed invoking ${bus::class.java.simpleName}.${chosen.name}: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw AssertionError("Argument mismatch invoking ${bus::class.java.simpleName}.${chosen.name}: ${e.message}", e)
        }
    }

    fun isToolSuccess(res: Any?): Boolean {
        if (res == null) return false
        if (res is Boolean) return res
        val n = res::class.java.simpleName.lowercase()
        if ("fail" in n || "deny" in n || "denied" in n || "error" in n) return false
        for (p in listOf("isSuccess", "success", "ok", "allowed")) {
            val v = runCatching { ContractProbe.readProperty(res, p) as? Boolean }.getOrNull()
            if (v != null) return v
        }
        return "success" in n
    }

    fun isToolAllowed(roleName: String, toolName: String): Boolean {
        val wlCls = ContractProbe.requireClass("ToolWhitelists", "agent")
        val roleCls = ContractProbe.requireClass("AgentTaskRole", "agent")
        val role = resolveEnum(roleCls, roleName) ?: throw AssertionError("Missing AgentTaskRole '$roleName'")
        val toolIdCls = ContractProbe.resolveClassOrNull("ToolId", listOf("agent"))
        val toolArg: Any = toolIdCls?.let {
            resolveEnum(it, toolName) ?: throw AssertionError("Missing ToolId '$toolName'")
        } ?: toolName
        val wl = ContractProbe.singletonOrNull(wlCls)
        val ms = (wlCls.methods + wlCls.declaredMethods).distinct().filter { !it.isSynthetic && !it.isBridge }
        ms.firstOrNull { it.name in listOf("isAllowed", "allows", "canUse", "isToolAllowed") && it.parameterCount == 2 }?.let { m ->
            m.isAccessible = true
            val recv = if (Modifier.isStatic(m.modifiers)) null else wl
            return try {
                m.invoke(recv, role, toolArg) as Boolean
            } catch (ite: InvocationTargetException) {
                throw ite.targetException
            }
        }
        val setM = ms.firstOrNull { it.name in listOf("allowedTools", "toolsFor", "allowedFor", "getAllowedTools") && it.parameterCount == 1 }
            ?: throw AssertionError("ToolWhitelists missing isAllowed/allowedTools")
        setM.isAccessible = true
        val recv = if (Modifier.isStatic(setM.modifiers)) null else wl
        val allowed = try {
            setM.invoke(recv, role) as Collection<*>
        } catch (ite: InvocationTargetException) {
            throw ite.targetException
        }
        return allowed.any { it != null && (norm(it.toString()) == norm(toolName) || it == toolArg) }
    }

    fun saveAiConfig(env: PlatformEnvironment, cfg: Any) {
        val typed = cfg as? AiGatewayConfig
            ?: throw AssertionError("Expected AiGatewayConfig, got ${cfg::class.java.name}")
        AiConfigStore(env.privateConfigDir).write(typed)
    }

    fun saveBookBible(
        env: PlatformEnvironment,
        bookId: String,
        traits: List<String>,
        scenes: List<String>,
        plot: String,
    ) {
        BookBibleStore(File(env.storageRoot, "books")).write(
            BookBible(
                bookId = bookId,
                protagonistTraits = traits,
                keyScenesAndProps = scenes,
                plotSummary = plot,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

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
    ): Any? {
        val cls = ContractProbe.requireClass(clsName, "ai")
        val aiClientCls = ContractProbe.requireClass("AiGatewayClient", "ai")
        val aiClient = ContractProbe.singletonOrNull(aiClientCls) ?: constructWithNullableFallback(aiClientCls)
        val toolBus = resolveToolBus(env, controller)
        val repo = controller?.let { runCatching { ContractProbe.readProperty(it, "repo") }.getOrNull() }
            ?: StoryBookRepository(env.storageRoot)
        val book = ContractProbe.callReflect(repo, repo.javaClass, listOf("getBook"), bookId)
            ?: throw AssertionError("Book '$bookId' not found when invoking $clsName")
        val agent = ContractProbe.singletonOrNull(cls)
            ?: constructFromPool(cls, listOf(aiClient, toolBus, repo, env, cfg))

        val methods = (cls.methods + cls.declaredMethods).distinct().filter { !it.isSynthetic && !it.isBridge }
        var bestMethod: Method? = null
        var bestArgs: Array<Any?> = emptyArray()
        var bestScore = Int.MIN_VALUE

        for (name in methodNames) {
            for (m in methods.filter { it.name == name }) {
                val raw = m.parameterTypes.toList()
                val isSusp = raw.lastOrNull()?.let { Continuation::class.java.isAssignableFrom(it) } == true
                val bindParams = if (isSusp) raw.dropLast(1) else raw
                val bound = bindAgentMethodArgs(bindParams, cfg, toolBus, repo, env, book, pageIndex, bookId, clauseId, instruction)
                    ?: continue
                val nonNullCount = bound.count { it != null }
                val score = (nonNullCount * 100) - bound.count { it == null }
                if (score > bestScore) {
                    bestScore = score
                    bestMethod = m
                    bestArgs = bound
                }
            }
            if (bestMethod != null) break
        }
        val method = bestMethod ?: throw AssertionError(
            "No bindable method ${methodNames.joinToString("/")} on ${cls.simpleName}",
        )
        return ContractProbe.invokeResolvedMethod(agent, cls, method, bestArgs)
    }

    fun invokeExpectingGatewayFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
        } catch (_: JSONException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun bindAgentMethodArgs(
        params: List<Class<*>>,
        cfg: Any,
        toolBus: Any,
        repo: Any,
        env: PlatformEnvironment,
        book: Any,
        pageIndex: Int,
        bookId: String,
        clauseId: String?,
        instruction: String?,
    ): Array<Any?>? {
        val hasBookParam = params.any { it.isAssignableFrom(book.javaClass) }
        val strSlots = if (hasBookParam) listOf(clauseId, instruction) else listOf(bookId, clauseId, instruction)
        var strIdx = 0
        val out = arrayOfNulls<Any?>(params.size)
        for (i in params.indices) {
            val p = params[i]
            out[i] = when {
                p.isAssignableFrom(cfg.javaClass) -> cfg
                p.isAssignableFrom(toolBus.javaClass) -> toolBus
                p.isAssignableFrom(repo.javaClass) -> repo
                p.isAssignableFrom(env.javaClass) -> env
                p.isAssignableFrom(book.javaClass) -> book
                p == Int::class.javaPrimitiveType || p == Int::class.javaObjectType -> pageIndex
                p == String::class.java -> {
                    if (strIdx >= strSlots.size) return null
                    strSlots[strIdx++]
                }
                else -> return null
            }
        }
        return out
    }

    private fun buildToolRequest(
        reqCls: Class<*>,
        roleCls: Class<*>,
        role: Any,
        toolIdCls: Class<*>?,
        toolVal: Any,
        toolName: String,
        params: Map<String, Any?>,
    ): Any {
        val ctors = reqCls.declaredConstructors
            .filter { !it.isSynthetic && it.parameterTypes.none { p -> p.name == "kotlin.jvm.internal.DefaultConstructorMarker" } }
            .sortedBy { it.parameterCount }
        for (ctor in ctors) {
            val pts = ctor.parameterTypes
            val strFallback = buildList {
                if (toolIdCls == null) add(toolName)
                params["bookId"]?.toString()?.let { add(it) }
                params["clauseId"]?.toString()?.let { add(it) }
            }
            var strIdx = 0
            var ok = true
            val args = pts.map { pt ->
                when {
                    pt == roleCls -> role
                    toolIdCls != null && pt == toolIdCls -> toolVal
                    Map::class.java.isAssignableFrom(pt) -> params
                    pt == String::class.java -> strFallback.getOrNull(strIdx++) ?: run { ok = false; null }
                    else -> run { ok = false; null }
                }
            }.toTypedArray()
            if (ok) {
                ctor.isAccessible = true
                return try {
                    ctor.newInstance(*args)
                } catch (ite: InvocationTargetException) {
                    throw ite.targetException
                } catch (e: ReflectiveOperationException) {
                    throw AssertionError("Failed constructing ToolRequest: ${e.message}", e)
                }
            }
        }
        throw AssertionError("No satisfiable ToolRequest constructor on ${reqCls.simpleName}")
    }

    private fun constructWithNullableFallback(cls: Class<*>): Any {
        val ctors = cls.declaredConstructors
            .filter { !it.isSynthetic && it.parameterTypes.none { p -> p.name == "kotlin.jvm.internal.DefaultConstructorMarker" } }
            .sortedBy { it.parameterCount }
        val zero = ctors.firstOrNull { it.parameterCount == 0 }
        val ctor = zero ?: ctors.firstOrNull()
            ?: throw AssertionError("No constructor on ${cls.simpleName}")
        ctor.isAccessible = true
        return try {
            ctor.newInstance(*Array(ctor.parameterCount) { null })
        } catch (ite: InvocationTargetException) {
            throw ite.targetException
        } catch (e: ReflectiveOperationException) {
            throw AssertionError("Failed constructing ${cls.simpleName}: ${e.message}", e)
        }
    }

    private fun constructFromPool(cls: Class<*>, pool: List<Any>): Any {
        val ctors = cls.declaredConstructors
            .filter { !it.isSynthetic && it.parameterTypes.none { p -> p.name == "kotlin.jvm.internal.DefaultConstructorMarker" } }
            .sortedByDescending { it.parameterCount }
        for (ctor in ctors) {
            val args = ctor.parameterTypes.map { pt -> pool.firstOrNull { pt.isAssignableFrom(it.javaClass) } }
            if (args.none { it == null }) {
                ctor.isAccessible = true
                return try {
                    ctor.newInstance(*args.toTypedArray())
                } catch (ite: InvocationTargetException) {
                    throw ite.targetException
                } catch (e: ReflectiveOperationException) {
                    throw AssertionError("Failed constructing ${cls.simpleName}: ${e.message}", e)
                }
            }
        }
        throw AssertionError("No satisfiable constructor on ${cls.simpleName}")
    }
}
