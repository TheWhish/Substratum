package xyz.thewhish.substratum.config

import dev.architectury.platform.Platform
import xyz.thewhish.substratum.Substratum
import java.nio.file.Files

class ConfigFile(private val name: String, private val defaults: String) {

    private var values = emptyMap<String, String>()

    fun load() {
        val path = Platform.getConfigFolder().resolve(name)
        values = runCatching {
            if (Files.notExists(path)) Files.writeString(path, defaults)
            parse(Files.readAllLines(path))
        }.onFailure {
            Substratum.LOGGER.warn("Could not read {}, using defaults", path, it)
        }.getOrDefault(emptyMap())
    }

    fun boolean(key: String, fallback: Boolean): Boolean = read(key, fallback) { it.toBooleanStrictOrNull() }

    fun int(key: String, fallback: Int, range: IntRange): Int =
        read(key, fallback) { raw -> raw.toIntOrNull()?.takeIf { it in range } }

    fun double(key: String, fallback: Double, range: ClosedFloatingPointRange<Double>): Double =
        read(key, fallback) { raw -> raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in range } }

    private fun <T : Any> read(key: String, fallback: T, parse: (String) -> T?): T {
        val raw = values[key] ?: return fallback
        return parse(raw) ?: fallback.also { Substratum.LOGGER.warn("{}: '{}' is not a valid {}, using {}", name, raw, key, it) }
    }

    private fun parse(lines: List<String>): Map<String, String> {
        val found = HashMap<String, String>()
        var section = ""
        for (line in lines) {
            val clean = line.substringBefore('#').trim()
            if (clean.startsWith('[') && clean.endsWith(']')) {
                section = clean.removeSurrounding("[", "]").trim() + "."
                continue
            }
            val parts = clean.split('=', limit = 2)
            if (parts.size == 2) found[section + parts[0].trim()] = parts[1].trim().removeSurrounding("\"")
        }
        return found
    }
}
