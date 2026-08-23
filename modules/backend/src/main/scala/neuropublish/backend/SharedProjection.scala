package neuropublish.backend

import io.circe.Json

/** The presentation subset of a manifest that a link viewer receives (architecture, "Privacy and
  * security": a share link carries what the presentation renders, not the publication record). A
  * pure projection by allow-list: anything not named here — `provenance`, open records, method
  * payloads, asset sizes and catalogs, sensitivity, axes — is dropped, never copied.
  *
  * Kept: `core`, `title`, `synopsis`, `warnings`, `resultFields`, `underlays`, `surfaces`,
  * `domains`; `analyses` as `{id, label, estimands, sampleSize}`; `assets` as
  * `{id, digest, size, mediaType}` (`size` because the manifest decoder the page uses requires it;
  * it is the byte count of a rendition the viewer fetches anyway — `catalog` and anything else on
  * an asset is dropped).
  */
object SharedProjection:
  private val topLevel =
    List(
      "core",
      "title",
      "synopsis",
      "warnings",
      "resultFields",
      "underlays",
      "surfaces",
      "domains"
    )
  private val analysisKeys = List("id", "label", "estimands", "sampleSize")
  private val assetKeys = List("id", "digest", "size", "mediaType")

  def of(manifest: Json): Json =
    val c = manifest.hcursor
    def pick(j: Json, keys: List[String]): Json =
      Json.fromFields(keys.flatMap(k => j.hcursor.downField(k).focus.map(k -> _)))
    def arrayOf(key: String, keys: List[String]): Option[(String, Json)] =
      c.downField(key).focus.flatMap(_.asArray).map(xs =>
        key -> Json.fromValues(xs.map(pick(_, keys)))
      )
    Json.fromFields(
      topLevel.flatMap(k => c.downField(k).focus.map(k -> _)) ++
        arrayOf("analyses", analysisKeys) ++
        arrayOf("assets", assetKeys)
    )

  /** Link creation is the policy check (architecture: "sensitivity ... checked at share creation"):
    * only group-level results may be shared by link.
    */
  val ShareableSensitivity = "group-level"
  def shareable(manifest: Json): Either[String, Unit] =
    manifest.hcursor.get[String]("sensitivity").toOption match
      case Some(ShareableSensitivity) => Right(())
      case Some(other) =>
        Left(s"only group-level results may be shared by link; this revision is '$other'")
      case None =>
        Left("this revision declares no sensitivity; only group-level results may be shared")
