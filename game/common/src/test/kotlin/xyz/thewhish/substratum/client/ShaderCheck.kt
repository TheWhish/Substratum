package xyz.thewhish.substratum.client

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL20
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.system.exitProcess

private const val TERRAIN = "assets/sodium/shaders/blocks/block_layer_opaque"
private val IMPORT = Regex("""#import <sodium:(.+)>""")
private val PASSES = listOf(
    "#define USE_VERTEX_COMPRESSION\n#define USE_FOG\n#define MAX_TEXTURE_LOD_BIAS 4.0\n",
    "#define USE_VERTEX_COMPRESSION\n#define USE_FOG\n#define USE_FRAGMENT_DISCARD\n#define MAX_TEXTURE_LOD_BIAS 4.0\n"
)

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <program shaders> <sodium jar>" }
    val programs = Path.of(args[0])
    check(GLFW.glfwInit()) { "GLFW failed to initialise" }
    GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
    val window = GLFW.glfwCreateWindow(16, 16, "shaderCheck", 0L, 0L)
    check(window != 0L) { "no OpenGL 3.3 core context" }
    GLFW.glfwMakeContextCurrent(window)
    GL.createCapabilities()
    val vertex = compile(GL20.GL_VERTEX_SHADER, programs.resolve("substratum_screen.vsh"))
    val fragments = Files.list(programs).use { files -> files.filter { it.name.endsWith(".fsh") }.sorted().toList() }
    val failed = fragments.count { !link(vertex, it) }
    val terrain = terrain(Path.of(args[1]))
    val renderer = GL20.glGetString(GL20.GL_RENDERER)
    GLFW.glfwTerminate()
    if (vertex == 0 || failed > 0 || !terrain) exitProcess(1)
    println("shaderCheck: ${fragments.size} programs and ${PASSES.size} lit Sodium terrain passes OK on $renderer")
}

private fun terrain(jar: Path): Boolean = JarFile(jar.toFile()).use { sodium ->
    fun read(name: String) = sodium.getInputStream(sodium.getJarEntry(name) ?: error("$name is missing from $jar")).use { String(it.readAllBytes()) }
    val patched = TerrainLight.rewrite(read("$TERRAIN.vsh"), read("$TERRAIN.fsh"))
    if (patched == null) {
        System.err.println("Sodium terrain shaders no longer match the light patch")
        return false
    }
    PASSES.all { defines ->
        fun expand(source: String) = source.replaceFirst("#version 330 core", "#version 330 core\n$defines")
            .replace(IMPORT) { read("assets/sodium/shaders/${it.groupValues[1]}") }
        linkSources("terrain", expand(patched.first), expand(patched.second))
    }
}

private fun linkSources(name: String, vertexSource: String, fragmentSource: String): Boolean {
    val vertex = compileSource(GL20.GL_VERTEX_SHADER, "$name.vsh", vertexSource)
    val fragment = compileSource(GL20.GL_FRAGMENT_SHADER, "$name.fsh", fragmentSource)
    if (vertex == 0 || fragment == 0) return false
    val program = GL20.glCreateProgram()
    GL20.glAttachShader(program, vertex)
    GL20.glAttachShader(program, fragment)
    GL20.glLinkProgram(program)
    if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL20.GL_TRUE) return true
    System.err.println("$name: ${GL20.glGetProgramInfoLog(program).trim()}")
    return false
}

private fun link(vertex: Int, file: Path): Boolean {
    val fragment = compile(GL20.GL_FRAGMENT_SHADER, file)
    if (fragment == 0) return false
    val program = GL20.glCreateProgram()
    GL20.glAttachShader(program, vertex)
    GL20.glAttachShader(program, fragment)
    GL20.glLinkProgram(program)
    if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL20.GL_TRUE) return true
    System.err.println("${file.name}: ${GL20.glGetProgramInfoLog(program).trim()}")
    return false
}

private fun compile(type: Int, file: Path): Int = compileSource(type, file.name, file.readText())

private fun compileSource(type: Int, name: String, source: String): Int {
    val shader = GL20.glCreateShader(type)
    GL20.glShaderSource(shader, source)
    GL20.glCompileShader(shader)
    if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL20.GL_TRUE) return shader
    System.err.println("$name: ${GL20.glGetShaderInfoLog(shader).trim()}")
    return 0
}
