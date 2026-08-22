package neuropublish.viewer

/** Presentation state in the URL query, so a view survives reload and can be shared before saved
  * views exist (Stage 3 exit: "layer order and presentation survive URL round trips"). Compact,
  * human-readable, order-preserving:
  *
  * ?l=speech-t:1:0.85:-8,8:ts3.1:cold-hot;speech-z:0:0.85:-8,8:off:cold-hot&c=-56,-22,14&p=volume&i=layers
  *
  * Only *current* display state is encoded; published recommendations come from the revision.
  * Unknown or malformed parts are ignored (the revision's recommendation fills in), never guessed.
  */
object ViewUrl:
  private def num(d: Double): String =
    if d == d.floor && d.abs < 1e9 then d.toLong.toString
    else f"$d%.4f".replaceAll("0+$", "").replaceAll("\\.$", "")

  def encode(w: Workspace): String =
    val layers = w.layers.map { l =>
      val c = l.current
      val thr = c.threshold.mode match
        case "two-sided" => s"ts${num(c.threshold.min)}"
        case _ => "off"
      s"${l.id}:${if c.visible then 1 else 0}:${num(c.opacity)}:${num(c.window.min)},${num(c.window.max)}:$thr:${c.colormap}"
    }.mkString(";")
    val cursor = w.cursor.map((x, y, z) => s"&c=${num(x)},${num(y)},${num(z)}").getOrElse("")
    s"l=$layers$cursor&p=${w.layout.preset.toString.toLowerCase}&i=${w.inspector}"

  /** Apply a query string onto a workspace built from the revision's recommendations. */
  def apply(query: String, base: Workspace): Workspace =
    val params = query.stripPrefix("?").split('&').filter(_.contains('=')).map(kv =>
      kv.splitAt(kv.indexOf('='))
    ).map((k, v) => k -> v.drop(1)).toMap
    val withLayers = params.get("l").map(_.split(';').toVector.flatMap(parseLayer)).map { specs =>
      val byId = specs.map(s => s._1 -> s._2).toMap
      val ordered = specs.map(_._1).flatMap(id => base.layers.find(_.id == id)) ++
        base.layers.filterNot(l => byId.contains(l.id))
      base.copy(layers =
        ordered.map(l => byId.get(l.id).fold(l)(d => l.copy(current = d(l.current))))
      )
    }.getOrElse(base)
    val withCursor = params.get("c").flatMap(parseCursor).fold(withLayers)((x, y, z) =>
      withLayers.copy(cursor = Some((x, y, z)))
    )
    val withPreset =
      params.get("p").flatMap(p => LayoutPreset.values.find(_.toString.equalsIgnoreCase(p)))
        .fold(withCursor)(p => withCursor.copy(layout = withCursor.layout.copy(preset = p)))
    params.get("i").filter(Set("layers", "analysis", "provenance")).fold(withPreset)(i =>
      withPreset.copy(inspector = i)
    )

  private def parseLayer(s: String): Option[(String, LayerDisplay => LayerDisplay)] =
    s.split(':') match
      case Array(id, vis, op, win, thr, cmap) =>
        for
          v <- vis match { case "1" => Some(true); case "0" => Some(false); case _ => None }
          o <- op.toDoubleOption.filter(x => x >= 0 && x <= 1)
          w <- win.split(',') match
            case Array(a, b) => (a.toDoubleOption, b.toDoubleOption) match
                case (Some(x), Some(y)) if x < y => Some(Window(x, y))
                case _ => None
            case _ => None
          t <- thr match
            case "off" => Some(Threshold("off", 0.0))
            case x if x.startsWith("ts") =>
              x.drop(2).toDoubleOption.filter(_ >= 0).map(Threshold("two-sided", _))
            case _ => None
        yield id ->
          ((d: LayerDisplay) =>
            d.copy(visible = v, opacity = o, window = w, threshold = t, colormap = cmap)
          )
      case _ => None

  private def parseCursor(s: String): Option[(Double, Double, Double)] =
    s.split(',').map(_.toDoubleOption) match
      case Array(Some(x), Some(y), Some(z)) => Some((x, y, z))
      case _ => None
