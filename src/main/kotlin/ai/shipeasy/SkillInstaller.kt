package ai.shipeasy

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * `SkillInstaller` — install the bundled Shipeasy agent skill into a project. The
 * JVM has no safe post-install hook, so shipping the skill is an explicit, opt-in
 * command. The skill ships inside the jar as the classpath resource
 * `/shipeasy-skill/SKILL.md` (kept in sync with `docs/skill/SKILL.md` by
 * `tools/GenReadme.java`):
 *
 * ```
 * java -cp shipeasy-kotlin.jar ai.shipeasy.SkillInstallerKt install              # -> .claude/skills/shipeasy-kotlin/SKILL.md
 * java -cp shipeasy-kotlin.jar ai.shipeasy.SkillInstallerKt install --dir path/  # custom destination (file or dir)
 * java -cp shipeasy-kotlin.jar ai.shipeasy.SkillInstallerKt install --force      # overwrite an existing file
 * java -cp shipeasy-kotlin.jar ai.shipeasy.SkillInstallerKt print                # write the skill to stdout
 * ```
 */
private const val RESOURCE = "/shipeasy-skill/SKILL.md"
private const val DEFAULT_DEST = ".claude/skills/shipeasy-kotlin/SKILL.md"

private fun skillText(): String =
    object {}.javaClass.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("bundled SKILL.md not found on the classpath")

private fun install(dir: String, force: Boolean): Int {
    var dest: Path = Paths.get(dir)
    val looksDir = Files.isDirectory(dest) || !dest.fileName.toString().contains(".")
    if (looksDir) dest = dest.resolve("SKILL.md")
    if (Files.exists(dest) && !force) {
        System.err.println("shipeasy-skill: refusing to overwrite $dest — pass --force")
        return 1
    }
    dest.parent?.let { Files.createDirectories(it) }
    Files.writeString(dest, skillText())
    println("shipeasy-skill: installed the Shipeasy agent skill → $dest")
    return 0
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "print" -> print(skillText())
        "install" -> {
            var dir = DEFAULT_DEST
            var force = false
            var i = 1
            while (i < args.size) {
                when (args[i]) {
                    "--force" -> force = true
                    "--dir" -> if (i + 1 < args.size) dir = args[++i]
                }
                i++
            }
            exitProcess(install(dir, force))
        }
        else -> {
            println(
                """
                shipeasy-skill — install the Shipeasy agent skill.

                  ai.shipeasy.SkillInstallerKt install [--dir <path>] [--force]
                  ai.shipeasy.SkillInstallerKt print
                """.trimIndent()
            )
            val cmd = args.firstOrNull()
            if (cmd != null && cmd != "--help" && cmd != "-h") exitProcess(1)
        }
    }
}
