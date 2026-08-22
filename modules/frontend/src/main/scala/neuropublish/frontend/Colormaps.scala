package neuropublish.frontend

import intaglio.{ColorRamp, Rgba32}

/** Stage 3 colormaps over Intaglio's two-stop `ColorRamp`. Scientific multi-stop
  * sequential/diverging palettes are an upstream item (architecture: "Viewer integration and
  * upstream gaps", item 3); until then the choices are honest about being two-stop.
  */
object Colormaps:
  private def c(r: Int, g: Int, b: Int) = Rgba32.unsafe(r, g, b, 255)
  val all: List[(String, String)] = List(
    "cold-hot" -> "cold–hot",
    "gray" -> "grayscale",
    "heat" -> "heat",
    "viridis-2" -> "viridis (2-stop)"
  )
  def ramp(id: String): ColorRamp = id match
    case "gray" => ColorRamp.Grayscale
    case "heat" => ColorRamp.Heat
    case "viridis-2" => ColorRamp(c(0x44, 0x01, 0x54), c(0xfd, 0xe7, 0x25))
    case _ => ColorRamp(c(0x1f, 0x4e, 0x9c), c(0xff, 0xe0, 0x66))
  def css(id: String): String = id match
    case "gray" => "linear-gradient(90deg,#000,#fff)"
    case "heat" => "linear-gradient(90deg,#000,#f00,#ff0)"
    case "viridis-2" => "linear-gradient(90deg,#440154,#fde725)"
    case _ => "linear-gradient(90deg,#1f4e9c,#ffe066)"
