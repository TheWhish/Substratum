package xyz.thewhish.substratum

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.util.jar.JarFile

private const val MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;"
private const val AT = "Lorg/spongepowered/asm/mixin/injection/At;"
private const val MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;"
private const val ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;"
private const val INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;"
private val INJECTORS = setOf(
    "Lorg/spongepowered/asm/mixin/injection/Inject;",
    "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
    "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
    "Lorg/spongepowered/asm/mixin/injection/Redirect;",
    "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
    MODIFY_VARIABLE,
    "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
    "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
    "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
    "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
)

private class Game(paths: List<String>) {
    private val jars = paths.map(::JarFile)
    private val cache = HashMap<String, ClassNode?>()

    fun node(name: String): ClassNode? = cache.getOrPut(name) {
        jars.firstNotNullOfOrNull { jar -> jar.getJarEntry("$name.class")?.let { jar.getInputStream(it).use(::read) } }
    }

    fun owns(name: String): Boolean = name.startsWith("net/minecraft/") || name.startsWith("com/mojang/")

    fun hasMethod(owner: String, name: String, desc: String?): Boolean = lineage(owner).any { node ->
        node.methods.any { it.name == name && (desc == null || it.desc == desc) }
    }

    fun hasField(owner: String, name: String, desc: String?): Boolean = lineage(owner).any { node ->
        node.fields.any { it.name == name && (desc == null || it.desc == desc) }
    }

    private fun lineage(owner: String): Sequence<ClassNode> = sequence {
        val queue = ArrayDeque(listOf(owner))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val node = node(queue.removeFirst()) ?: continue
            if (!seen.add(node.name)) continue
            yield(node)
            node.superName?.let(queue::add)
            queue.addAll(node.interfaces)
        }
    }
}

fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <mod jar> <minecraft jars...>" }
    val game = Game(args.drop(1))
    val problems = ArrayList<String>()
    var checked = 0
    JarFile(args[0]).use { mod ->
        for (entry in mod.entries()) {
            if (!entry.name.endsWith(".class") || "/mixin/" !in entry.name) continue
            val node = mod.getInputStream(entry).use(::read)
            val mixin = annotations(node.invisibleAnnotations, node.visibleAnnotations).firstOrNull { it.desc == MIXIN } ?: continue
            if (value(mixin, "remap") == false) continue
            val targets = list(mixin, "value").map { (it as Type).internalName } + list(mixin, "targets").map { (it as String).replace('.', '/') }
            for (target in targets) {
                if (game.node(target) == null) problems += "${node.name}: target $target is missing"
            }
            for (method in node.methods) {
                for (annotation in annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                    val where = "${node.name}.${method.name}"
                    checked++
                    when (annotation.desc) {
                        in INJECTORS -> {
                            if (annotation.desc == MODIFY_VARIABLE && value(annotation, "name") != null && targets.any(game::owns)) {
                                problems += "$where: @ModifyVariable picks a local by name, production code has no local names"
                            }
                            for (selector in list(annotation, "method").map { it as String }) {
                                val (name, desc) = split(member(selector))
                                if (name != "<init>" && name != "<clinit>" && targets.none { game.hasMethod(it, name, desc) }) {
                                    problems += "$where: no target method $selector"
                                }
                            }
                            for (at in ats(annotation)) checkAt(game, where, at, problems)
                        }
                        ACCESSOR -> (value(annotation, "value") as String?)?.let { field ->
                            if (targets.none { game.hasField(it, field, null) }) problems += "$where: no target field $field"
                        }
                        INVOKER -> (value(annotation, "value") as String?)?.let { name ->
                            if (targets.none { game.hasMethod(it, name, null) }) problems += "$where: no target method $name"
                        }
                    }
                }
            }
        }
    }
    check(problems.isEmpty()) { "mixins do not match the game:\n" + problems.joinToString("\n") }
    println("mixins: $checked annotations match the production game")
}

private fun checkAt(game: Game, where: String, at: AnnotationNode, problems: MutableList<String>) {
    val target = value(at, "target") as? String? ?: return
    if (!target.startsWith("L") || ';' !in target) return
    val owner = target.substring(1, target.indexOf(';'))
    if (!game.owns(owner)) return
    val member = target.substringAfter(';')
    val found = if (':' in member) {
        game.hasField(owner, member.substringBefore(':'), member.substringAfter(':'))
    } else {
        val (name, desc) = split(member)
        game.hasMethod(owner, name, desc)
    }
    if (!found) problems += "$where: no @At target $target"
}

private fun member(selector: String): String {
    val open = selector.indexOf('(').let { if (it < 0) selector.length else it }
    val owner = selector.indexOf(';')
    return if (selector.startsWith("L") && owner in 0 until open) selector.substring(owner + 1) else selector
}

private fun split(selector: String): Pair<String, String?> {
    val open = selector.indexOf('(')
    return if (open < 0) selector to null else selector.substring(0, open) to selector.substring(open)
}

private fun ats(annotation: AnnotationNode): List<AnnotationNode> =
    listOf("at").flatMap { key ->
        when (val found = value(annotation, key)) {
            is AnnotationNode -> listOf(found)
            is List<*> -> found.filterIsInstance<AnnotationNode>()
            else -> emptyList()
        }
    }.filter { it.desc == AT }

private fun annotations(vararg groups: List<AnnotationNode>?): List<AnnotationNode> = groups.filterNotNull().flatten()

private fun value(annotation: AnnotationNode, key: String): Any? {
    val values = annotation.values ?: return null
    for (i in values.indices step 2) if (values[i] == key) return values[i + 1]
    return null
}

private fun list(annotation: AnnotationNode, key: String): List<Any> = when (val found = value(annotation, key)) {
    null -> emptyList()
    is List<*> -> found.filterNotNull()
    else -> listOf(found)
}

private fun read(stream: java.io.InputStream): ClassNode = ClassNode().also { ClassReader(stream).accept(it, ClassReader.SKIP_CODE) }
