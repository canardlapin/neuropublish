package neuropublish.frontend

/** Which declared surface occupies each hemisphere slot of the surface pane, and how a field's
  * per-hemisphere layer is named. Pure, so the rule is unit-tested without a renderer.
  *
  * Rule (Stage 5 review fixes): the pane has one slot per hemisphere. When a hemisphere declares
  * several decoded surfaces (`lh-pial` and `lh-white`), the slot takes the first one in manifest
  * order that some result field targets with a decoded surface representation; when none is
  * targeted, the first declared. Deterministic, and a field therefore lands on the surface it was
  * published for whenever one surface per hemisphere carries data. Representations are then counted
  * against the placed surfaces only: a field whose only left-hemisphere representation sits on an
  * unplaced surface is *not* "drawn in: left surface" — its card says so.
  */
object SurfacePlacement:
  val Hemispheres: List[String] = List("left", "right")

  /** `decoded` in manifest order; `targeted` = surface ids that some decoded surface representation
    * names. One entry per hemisphere that has at least one decoded surface.
    */
  def place(decoded: List[SurfaceDecl], targeted: Set[String]): Map[String, SurfaceDecl] =
    Hemispheres.flatMap { h =>
      val mine = decoded.filter(_.hemisphere == h)
      mine.find(d => targeted(d.id)).orElse(mine.headOption).map(h -> _)
    }.toMap

  /** Surface layer id for one field drawn on one placed surface: `field@surface`. A field has at
    * most one layer per placed surface, so ids never collide.
    */
  def layerId(field: String, surface: String): String = s"$field@$surface"

  /** Inverse of `layerId`: `(field, surface)`. Field ids are semantic ids without `@`. */
  def splitLayerId(id: String): Option[(String, String)] =
    val i = id.indexOf('@')
    if i <= 0 || i == id.length - 1 then None else Some((id.substring(0, i), id.substring(i + 1)))

/** Defensive guard for the linked cursor: a surface and the volume underlay share a world only when
  * they declare the same space. The surface's space comes from its rendition header (`space`,
  * optional until every producer writes it); the volume's from the underlay's `volume-grid` domain
  * payload. Absent on either side → link (nothing contradicts); both present and different → never
  * link, say why.
  */
object SpaceGuard:
  enum Decision:
    case Link
    case Mismatch(surfaceSpace: String, volumeSpace: String)

  def decide(surfaceSpace: Option[String], volumeSpace: Option[String]): Decision =
    (surfaceSpace.map(_.trim).filter(_.nonEmpty), volumeSpace.map(_.trim).filter(_.nonEmpty)) match
      case (Some(s), Some(v)) if s != v => Decision.Mismatch(s, v)
      case _ => Decision.Link

  def message(m: Decision.Mismatch): String =
    s"not linked: surface space ${m.surfaceSpace} ≠ volume space ${m.volumeSpace}"
