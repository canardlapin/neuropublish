package neuropublish.viewer

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*

/** The saved-view wire record for a `Workspace`: the open record
  * `org.neuropublish.view/workspace-state@1` (ADR 0001), whose payload carries the layout as the
  * nested open record `org.neuropublish.view/workspace-layout@1`. Only presentation state is
  * recorded; the producer's recommendation (`published`) is kept beside the viewer's `current`
  * values so a saved view can still show and reset the difference, but on restore the revision's
  * own recommendation wins (a saved view cannot change scientific content).
  *
  * {{{
  * {"schema":{"id":"org.neuropublish.view/workspace-state","version":"1"},
  *  "payload":{"layers":[{"id":"speech-t","published":{...},"current":{...},"recommended":true,
  *                        "representations":{"volume":true,"surfaces":["left","right"]}}],
  *             "cursor":[x,y,z] | null,
  *             "layout":{"schema":{"id":"org.neuropublish.view/workspace-layout","version":"1"},
  *                       "payload":{"preset":"volume","navigatorFraction":0.18,...,"splitFraction":0.5}},
  *             "inspector":"layers",
  *             "surfaceCamera":{"viewpoint":"left","projection":"perspective"
  * }}}
  * }}}
  *
  * Stage 5 additions (`representations`, `splitFraction`, `surfaceCamera`) are optional on read so
  * Stage 4 records still decode; they are always written. The version stays 1: a Stage 4 reader
  * ignores the unknown members and loses only the surface camera and divider.
  */
object WorkspaceState:
  val StateSchema = "org.neuropublish.view/workspace-state"
  val LayoutSchema = "org.neuropublish.view/workspace-layout"
  val Version = "1"

  final case class SchemaRef(id: String, version: String)
  final case class Record[A](schema: SchemaRef, payload: A)

  given Codec[SchemaRef] = deriveCodec
  given [A: Encoder: Decoder]: Codec[Record[A]] = deriveCodec

  given Codec[Window] = deriveCodec

  /** Written without `max` when there is none: `"max": null` is not a number, and the saved-view
    * schema's threshold is a closed structure.
    */
  given Codec[Threshold] = Codec.from(
    Decoder.instance(c =>
      for
        mode <- c.get[String]("mode")
        min <- c.get[Double]("min")
        max <- c.get[Option[Double]]("max")
      yield Threshold(mode, min, max)
    ),
    Encoder.instance(t =>
      Json.obj(
        (List(
          "mode" -> Json.fromString(t.mode),
          "min" -> Json.fromDoubleOrNull(t.min)
        ) ++ t.max.map(m => "max" -> Json.fromDoubleOrNull(m)))*
      )
    )
  )
  given Codec[LayerDisplay] = deriveCodec
  given Codec[LayerRepresentations] = Codec.from(
    Decoder.instance(c =>
      for
        v <- c.get[Option[Boolean]]("volume")
        s <- c.get[Option[Set[String]]]("surfaces")
      yield LayerRepresentations(v.getOrElse(true), s.getOrElse(Set.empty))
    ),
    Encoder.forProduct2("volume", "surfaces")(r => (r.volume, r.surfaces))
  )
  given Codec[SurfaceCameraState] = deriveCodec
  given Codec[WorkspaceLayer] = Codec.from(
    Decoder.instance { c =>
      for
        id <- c.get[String]("id")
        published <- c.get[LayerDisplay]("published")
        current <- c.get[LayerDisplay]("current")
        recommended <- c.get[Option[Boolean]]("recommended")
        reps <- c.get[Option[LayerRepresentations]]("representations")
      yield WorkspaceLayer(
        id,
        published,
        current,
        recommended.getOrElse(true),
        reps.getOrElse(LayerRepresentations())
      )
    },
    Encoder.forProduct5("id", "published", "current", "recommended", "representations")(l =>
      (l.id, l.published, l.current, l.recommended, l.representations)
    )
  )

  given Codec[LayoutPreset] = Codec.from(
    Decoder[String].emap(s =>
      LayoutPreset.values.find(_.toString.equalsIgnoreCase(s)).toRight(s"unknown preset: $s")
    ),
    Encoder[String].contramap(_.toString.toLowerCase)
  )
  private val layoutPayload: Codec[WorkspaceLayout] = Codec.from(
    Decoder.instance { c =>
      for
        preset <- c.get[LayoutPreset]("preset")
        nav <- c.get[Double]("navigatorFraction")
        ins <- c.get[Double]("inspectorFraction")
        split <- c.get[Option[Double]]("splitFraction")
      yield WorkspaceLayout(
        preset,
        nav,
        ins,
        split.getOrElse(WorkspaceLayout.default.splitFraction)
      )
    },
    Encoder.forProduct4(
      "preset",
      "navigatorFraction",
      "inspectorFraction",
      "splitFraction"
    )(l => (l.preset, l.navigatorFraction, l.inspectorFraction, l.splitFraction))
  )
  given Codec[WorkspaceLayout] = Codec.from(
    Decoder[Record[Json]].emap { r =>
      if r.schema.id != LayoutSchema then Left(s"expected $LayoutSchema, got ${r.schema.id}")
      else if r.schema.version != Version then
        Left(s"unsupported layout version ${r.schema.version}")
      else layoutPayload.decodeJson(r.payload).left.map(_.getMessage)
    },
    Encoder[Record[Json]].contramap(l => Record(SchemaRef(LayoutSchema, Version), layoutPayload(l)))
  )

  /** Cursor as a 3-array (or null), matching the URL form `c=x,y,z`. */
  given Codec[Option[(Double, Double, Double)]] = Codec.from(
    Decoder.decodeOption(Decoder[List[Double]].emap {
      case List(x, y, z) => Right((x, y, z))
      case other => Left(s"cursor needs 3 coordinates, got ${other.length}")
    }),
    Encoder.encodeOption(Encoder[List[Double]].contramap((x, y, z) => List(x, y, z)))
  )

  private val workspacePayload: Codec[Workspace] = Codec.from(
    Decoder.instance { c =>
      for
        layers <- c.get[Vector[WorkspaceLayer]]("layers")
        cursor <- c.get[Option[(Double, Double, Double)]]("cursor")
        layout <- c.get[WorkspaceLayout]("layout")
        inspector <- c.get[String]("inspector")
        camera <- c.get[Option[SurfaceCameraState]]("surfaceCamera")
      yield Workspace(
        layers,
        cursor,
        layout,
        inspector,
        camera.getOrElse(SurfaceCameraState.default)
      )
    },
    Encoder.forProduct5("layers", "cursor", "layout", "inspector", "surfaceCamera")(w =>
      (w.layers, w.cursor, w.layout, w.inspector, w.surfaceCamera)
    )
  )
  given Codec[Workspace] = Codec.from(
    Decoder[Record[Json]].emap { r =>
      if r.schema.id != StateSchema then Left(s"expected $StateSchema, got ${r.schema.id}")
      else if r.schema.version != Version then
        Left(s"unsupported view-state version ${r.schema.version}")
      else workspacePayload.decodeJson(r.payload).left.map(_.getMessage)
    },
    Encoder[Record[Json]].contramap(w =>
      Record(SchemaRef(StateSchema, Version), workspacePayload(w))
    )
  )

  def encode(w: Workspace): Json = w.asJson
  def decode(j: Json): Either[String, Workspace] =
    Decoder[Workspace].decodeJson(j).left.map(_.getMessage)

  /** Apply a saved state onto the workspace built from the revision's recommendations, the same way
    * `ViewUrl.apply` does for the query string: saved layer order and current display are taken for
    * layers the revision still has; `published` always comes from the revision; layers the saved
    * view does not know keep their recommendation and follow; invalid saved parts fall back to
    * `base` field by field, never guessed.
    */
  def apply(saved: Workspace, base: Workspace): Workspace =
    val known = saved.layers.filter(l => base.layers.exists(_.id == l.id)).distinctBy(_.id)
    val ordered =
      known.flatMap(s => base.layers.find(_.id == s.id).map(b => b.copy(current = s.current))) ++
        base.layers.filterNot(b => known.exists(_.id == b.id))
    val layers = ordered.map { l =>
      val c = l.current
      val ok = c.opacity >= 0 && c.opacity <= 1 && c.window.min.isFinite && c.window.max.isFinite &&
        c.window.min < c.window.max && c.threshold.min >= 0 && Threshold.Modes(c.threshold.mode) &&
        Colormap.supported(c.colormap)
      if ok then l else l.copy(current = l.published)
    }
    Workspace(
      layers,
      saved.cursor.filter((x, y, z) => x.isFinite && y.isFinite && z.isFinite),
      if saved.layout.isValid then saved.layout else base.layout,
      if Set("layers", "analysis", "provenance")(saved.inspector) then saved.inspector
      else base.inspector,
      if SurfaceCameraState.valid(saved.surfaceCamera) then saved.surfaceCamera
      else base.surfaceCamera
    )
