package neuropublish.viewer

/** Presentation state in the URL query, so a view survives reload and can be shared before saved
  * views exist (Stage 3 exit: "layer order and presentation survive URL round trips"). Compact,
  * human-readable, order-preserving:
  *
  * ?l=speech-t:1:0.85:-8,8:ts3.1:cold-hot;speech-z:0:0.85:-8,8:off:cold-hot&c=-56,-22,14&p=volume&i=layers
  *
  * Stage 5 adds the surface camera (`sc=left,perspective`) and the Hybrid divider (`sf=0.5`, the
  * volume pane's share of the centre width); both are omitted when they equal the defaults.
  *
  * A two-sided threshold with a maximum magnitude appends it after an underscore (`ts3.1_8`); both
  * bounds are non-negative, so the separator can never be confused with a sign. A link written
  * before maximum magnitude existed still reads: `ts3.1` is simply a threshold with no upper bound.
  *
  * Layer ids and colormap names are percent-encoded so they can never collide with the separators.
  * Only *current* display state is encoded; published recommendations come from the revision.
  * Unknown or malformed parts are ignored (the revision's recommendation fills in), never guessed.
  */
object ViewUrl:
  private def esc(s: String): String =
    val sb = new StringBuilder
    s.getBytes("UTF-8").foreach { b =>
      val c = (b & 0xff).toChar
      if (c < 128 && c.isLetterOrDigit) || c == '-' || c == '_' || c == '.' then sb += c
      else sb.append(f"%%${b & 0xff}%02X")
    }
    sb.toString

  private def unesc(s: String): String =
    val out = new java.io.ByteArrayOutputStream
    var i = 0
    while i < s.length do
      if s.charAt(i) == '%' && i + 2 < s.length then
        try { out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 3 }
        catch case _: NumberFormatException => { out.write(s.charAt(i).toInt); i += 1 }
      else { out.write(s.charAt(i).toInt); i += 1 }
    new String(out.toByteArray, "UTF-8")

  private def num(d: Double): String =
    if d == d.floor && d.abs < 1e9 then d.toLong.toString
    else f"$d%.4f".replaceAll("0+$", "").replaceAll("\\.$", "")

  def encode(w: Workspace): String =
    val layers = w.layers.map { l =>
      val c = l.current
      val thr = c.threshold.mode match
        case "two-sided" =>
          s"ts${num(c.threshold.min)}${c.threshold.max.map(m => s"_${num(m)}").getOrElse("")}"
        case "positive" => s"pos${num(c.threshold.min)}"
        case "negative" => s"neg${num(c.threshold.min)}"
        case _ => "off"
      s"${esc(l.id)}:${if c.visible then 1 else 0}:${num(c.opacity)}:${num(c.window.min)},${num(c.window.max)}:$thr:${esc(c.colormap)}"
    }.mkString(";")
    val cursor = w.cursor.map((x, y, z) => s"&c=${num(x)},${num(y)},${num(z)}").getOrElse("")
    val cam =
      if w.surfaceCamera == SurfaceCameraState.default then ""
      else s"&sc=${w.surfaceCamera.viewpoint},${w.surfaceCamera.projection}"
    val split =
      if w.layout.splitFraction == WorkspaceLayout.default.splitFraction then ""
      else s"&sf=${num(w.layout.splitFraction)}"
    s"l=$layers$cursor&p=${w.layout.preset.toString.toLowerCase}&i=${w.inspector}$cam$split"

  /** Apply a query string onto a workspace built from the revision's recommendations. */
  def apply(query: String, base: Workspace): Workspace =
    val params = query.stripPrefix("?").split('&').filter(_.contains('=')).map(kv =>
      kv.splitAt(kv.indexOf('='))
    ).map((k, v) => k -> v.drop(1)).toMap
    val withLayers =
      params.get("l").map(_.split(';').toVector.flatMap(parseLayer).distinctBy(_._1)).map { specs =>
        val byId = specs.toMap
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
    val withInspector =
      params.get("i").filter(Set("layers", "analysis", "provenance")).fold(withPreset)(i =>
        withPreset.copy(inspector = i)
      )
    val withCamera = params.get("sc").flatMap(parseCamera).fold(withInspector)(c =>
      withInspector.copy(surfaceCamera = c)
    )
    params.get("sf").flatMap(_.toDoubleOption).filter(f => f > 0.0 && f < 1.0).fold(withCamera)(f =>
      withCamera.copy(layout =
        WorkspaceLayout.reduce(withCamera.layout, WorkspaceLayout.Action.ResizeSplit(f))
      )
    )

  private def parseCamera(s: String): Option[SurfaceCameraState] =
    s.split(',') match
      case Array(v, p) => Some(SurfaceCameraState(v, p)).filter(SurfaceCameraState.valid)
      case _ => None

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
              x.drop(2).split('_') match
                case Array(lo) => lo.toDoubleOption.filter(_ >= 0).map(Threshold("two-sided", _))
                case Array(lo, hi) =>
                  for
                    l <- lo.toDoubleOption.filter(_ >= 0)
                    h <- hi.toDoubleOption.filter(_ > l)
                  yield Threshold("two-sided", l, Some(h))
                case _ => None
            case x if x.startsWith("pos") =>
              x.drop(3).toDoubleOption.filter(_ >= 0).map(Threshold("positive", _))
            case x if x.startsWith("neg") =>
              x.drop(3).toDoubleOption.filter(_ >= 0).map(Threshold("negative", _))
            case _ => None
          cm <- Some(unesc(cmap)).filter(Colormap.valid)
        yield unesc(id) ->
          ((d: LayerDisplay) =>
            d.copy(visible = v, opacity = o, window = w, threshold = t, colormap = cm)
          )
      case _ => None

  private def parseCursor(s: String): Option[(Double, Double, Double)] =
    s.split(',').map(_.toDoubleOption) match
      case Array(Some(x), Some(y), Some(z)) if x.isFinite && y.isFinite && z.isFinite =>
        Some((x, y, z))
      case _ => None
