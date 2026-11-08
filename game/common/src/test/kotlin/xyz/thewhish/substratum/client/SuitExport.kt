package xyz.thewhish.substratum.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.geom.ModelPart
import java.io.File

private class Recorder : VertexConsumer {
    val vertices = ArrayList<FloatArray>()

    override fun addVertex(x: Float, y: Float, z: Float, color: Int, u: Float, v: Float, overlay: Int, light: Int, nx: Float, ny: Float, nz: Float) {
        vertices += floatArrayOf(nx, ny, nz, x, y, z, u, v)
    }

    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer = unexpected()
    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = unexpected()
    override fun setUv(u: Float, v: Float): VertexConsumer = unexpected()
    override fun setUv1(u: Int, v: Int): VertexConsumer = unexpected()
    override fun setUv2(u: Int, v: Int): VertexConsumer = unexpected()
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = unexpected()

    private fun unexpected(): Nothing = error("Cube.compile left the packed vertex path")
}

private fun locate(root: ModelPart, path: String): ModelPart =
    path.split('/').filter(String::isNotEmpty).fold(root, ModelPart::getChild)

private fun vector(vararg values: Float) = JsonArray().apply { values.forEach(::add) }

private fun export(): String {
    val root = Suit.layer().bakeRoot()
    check(root.x == 0f && root.y == 0f && root.z == 0f && root.xRot == 0f && root.yRot == 0f && root.zRot == 0f) { "root is posed" }
    val parts = LinkedHashMap<String, JsonArray>()
    val identity = PoseStack().last()
    root.visit(PoseStack()) { _, path, _, cube ->
        val parent = path.substringBeforeLast('/')
        check(parent.isEmpty() || parent in parts) { "$path hangs under an empty part" }
        val recorder = Recorder()
        cube.compile(identity, recorder, 0, 0, -1)
        val quads = parts.getOrPut(path) { JsonArray() }
        for (quad in recorder.vertices.chunked(4)) {
            quads.add(JsonArray().apply {
                quad.first().take(3).forEach(::add)
                quad.forEach { vertex -> vertex.drop(3).forEach(::add) }
            })
        }
    }
    val json = JsonObject()
    json.addProperty("width", Suit.WIDTH)
    json.addProperty("height", Suit.HEIGHT)
    json.add("parts", JsonArray().apply {
        for ((path, quads) in parts) {
            val part = locate(root, path)
            check(part.xScale == 1f && part.yScale == 1f && part.zScale == 1f) { "$path is scaled" }
            add(JsonObject().apply {
                addProperty("path", path)
                add("pivot", vector(part.x / 16f, part.y / 16f, part.z / 16f))
                add("rotation", vector(part.xRot, part.yRot, part.zRot))
                add("quads", quads)
            })
        }
    })
    return json.toString() + "\n"
}

fun main(args: Array<String>) {
    val target = File(args.single())
    val model = export()
    if (System.getProperty("suit.write") == "true") {
        target.writeText(model)
        println("suit: wrote ${target.name}")
        return
    }
    check(target.isFile && target.readText() == model) { "${target.path} is stale, run :common:suitExport" }
    println("suit: launcher model matches Suit.layer()")
}
