package neuropublish.rendition

import io.circe.Json
import munit.FunSuite
import scalafim.image.*
import scalafim.image.view.*
import intaglio.{DeviceContext, DisplayWindow, ScalarColorizer}

/** Spike A exit criterion: the browser-side decode of the typed-binary rendition reproduces the
  * exact affine and values of the canonical fixture, and a real ScalaFIM ViewerModel with one
  * underlay and two overlays reads the oracle's values back at each probe's world coordinate.
  */
class RenditionFidelitySuite extends FunSuite:
  private val ids = List("t1", "speech-t", "speech-z")

  private lazy val oracle: Json =
    _root_.io.circe.parser.parse(FixtureIO.readText("reference/assets/oracle.json")).fold(
      throw _,
      identity
    )

  private lazy val volumes: Map[String, NeuroVol[Double]] = ids.map { id =>
    val h = VolumeRendition.decodeHeader(FixtureIO.readText(s"reference/renditions/$id.json"))
      .fold(m => fail(s"$id: $m"), identity)
    id -> VolumeRendition.decode(h, FixtureIO.readBytes(s"reference/renditions/$id.f32"))
      .fold(m => fail(s"$id: $m"), identity)
  }.toMap

  test("shape and affine are exact") {
    val dims = oracle.hcursor.downField("dim").as[Vector[Int]].toOption.get
    val trans = oracle.hcursor.downField("trans").as[Vector[Vector[Double]]].toOption.get
    volumes.foreach { (id, v) =>
      assertEquals(v.space.spatialDims, dims, id)
      val m = v.space.affine3D.matrix
      for r <- 0 until 4; c <- 0 until 4 do
        assertEquals(m.data(r * 4 + c), trans(r)(c), s"$id affine[$r][$c]")
    }
  }

  test("voxel values and world coordinates match the oracle probes exactly") {
    val probes = oracle.hcursor.downField("probes").values.get.toList
    probes.foreach { p =>
      val vx = p.hcursor.downField("voxel0").as[Vector[Int]].toOption.get
      val world = p.hcursor.downField("world").as[Vector[Double]].toOption.get
      ids.foreach { id =>
        val expected = p.hcursor.downField(id).as[Double].toOption.get
        assertEquals(volumes(id)(vx(0), vx(1), vx(2)), expected, s"$id at $vx")
      }
      val w = volumes("t1").space.voxelToWorld(VoxelPoint(vx(0), vx(1), vx(2)))
      assertEquals(Vector(w.x, w.y, w.z), world, s"world of $vx")
    }
  }

  test("server-derived summary matches the oracle counts") {
    ids.foreach { id =>
      val h = VolumeRendition.decodeHeader(
        FixtureIO.readText(s"reference/renditions/$id.json")
      ).toOption.get
      val sm = h.summary.getOrElse(fail(s"$id has no summary"))
      assertEquals(
        sm.finite - sm.zero,
        oracle.hcursor.downField("nonzero").downField(id).as[Int].toOption.get,
        s"$id nonzero"
      )
      assertEquals(sm.missing, 0)
      assertEquals(sm.histogram.sum, sm.finite)
      assert(sm.quantiles.zip(sm.quantiles.tail).forall(_ <= _), "quantiles monotone")
    }
  }

  test("sums and non-zero counts match") {
    ids.foreach { id =>
      val v = volumes(id); val n = v.space.spatialDims.product
      var sum = 0.0; var nz = 0; var i = 0
      while i < n do { val x = v.linear(i); sum += x; if x != 0.0 then nz += 1; i += 1 }
      assertEquals(
        sum,
        oracle.hcursor.downField("sums").downField(id).as[Double].toOption.get,
        s"$id sum"
      )
      assertEquals(
        nz,
        oracle.hcursor.downField("nonzero").downField(id).as[Int].toOption.get,
        s"$id nonzero"
      )
    }
  }

  test("a ViewerModel with underlay + two overlays reads oracle values at each probe cursor") {
    val space = VolumeSpace(volumes("t1").space)
    def layer(id: String, lo: Double, hi: Double) =
      SliceLayer(
        LayerId.unsafe(id),
        volumes(id),
        SliceSampling.Nearest(0.0),
        ScalarColorizer(DisplayWindow.unsafe(lo, hi))
      )
    val model = ViewerModel.unsafe(
      space,
      Vector(layer("t1", 0, 100), layer("speech-t", -8, 8), layer("speech-z", -8, 8))
    )
    val session0 = ViewerSession(ViewerState.centered(space), DeviceContext.unsafe(600.0, 400.0))
    val probes = oracle.hcursor.downField("probes").values.get.toList
    probes.foreach { p =>
      val world = p.hcursor.downField("world").as[Vector[Double]].toOption.get
      val session = ViewerReducer.reduce(
        model,
        session0,
        ViewerAction.SetCursor(WorldPoint(world(0), world(1), world(2)))
      ).fold(e => fail(e.toString), identity)
      val frame = session.frame(model).fold(e => fail(e.toString), identity)
      val readout = frame.readouts(AnatomicalPlane.Axial)
      ids.foreach { id =>
        val expected = p.hcursor.downField(id).as[Double].toOption.get
        val got = readout.layers.find(_.layer == LayerId.unsafe(id)).map(_.value)
        assertEquals(got, Some(LayerSampleValue.Scalar(expected)), s"$id readout at $world")
      }
    }
  }
