package neuropublish.rendition

import java.nio.file.{Files, Path}

object FixtureIO:
  val root: Path =
    List(
      "modules/conformance/fixtures",
      "../conformance/fixtures",
      "../../modules/conformance/fixtures"
    )
      .map(Path.of(_)).find(Files.isDirectory(_))
      .getOrElse(throw IllegalStateException(
        s"fixtures not found from ${Path.of("").toAbsolutePath}"
      ))
  def readBytes(rel: String): Array[Byte] = Files.readAllBytes(root.resolve(rel))
  def readText(rel: String): String = Files.readString(root.resolve(rel))
  def writeBytes(rel: String, b: Array[Byte]): Unit =
    val p = root.resolve(rel); Files.createDirectories(p.getParent); Files.write(p, b)
  def exists(rel: String): Boolean = Files.exists(root.resolve(rel))
