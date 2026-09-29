package xyz.thewhish.substratum.client

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL20
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val programs = Path.of(args.single())
    check(GLFW.glfwInit()) { "GLFW failed to initialise" }
    GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
    val window = GLFW.glfwCreateWindow(16, 16, "shaderCheck", 0L, 0L)
    check(window != 0L) { "no OpenGL 3.2 core context" }
    GLFW.glfwMakeContextCurrent(window)
    GL.createCapabilities()
    val vertex = compile(GL20.GL_VERTEX_SHADER, programs.resolve("substratum_screen.vsh"))
    val fragments = Files.list(programs).use { files -> files.filter { it.name.endsWith(".fsh") }.sorted().toList() }
    val failed = fragments.count { !link(vertex, it) }
    val renderer = GL20.glGetString(GL20.GL_RENDERER)
    GLFW.glfwTerminate()
    if (vertex == 0 || failed > 0) exitProcess(1)
    println("shaderCheck: ${fragments.size} programs OK on $renderer")
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

private fun compile(type: Int, file: Path): Int {
    val shader = GL20.glCreateShader(type)
    GL20.glShaderSource(shader, file.readText())
    GL20.glCompileShader(shader)
    if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL20.GL_TRUE) return shader
    System.err.println("${file.name}: ${GL20.glGetShaderInfoLog(shader).trim()}")
    return 0
}
