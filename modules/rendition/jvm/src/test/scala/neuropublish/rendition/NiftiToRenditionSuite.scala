package neuropublish.rendition

import munit.FunSuite
import scalafim.image.io.Nifti

/** JVM half of Spike A: the canonical NIfTI fixtures encode to the checked-in renditions byte for
  * byte.
  */
class NiftiToRenditionSuite extends FunSuite:
  private val ids = List("t1", "speech-t", "speech-z")

  test("NIfTI fixtures encode deterministically to the committed renditions") {
    ids.foreach { id =>
      val vol = Nifti.readVol(FixtureIO.root.resolve(s"reference/assets/$id.nii"))
      val r = VolumeRendition.encode(vol, Some(s"assets/$id.nii"))
      val hdr = VolumeRendition.headerJson(r.header)
      val jsonRel = s"reference/renditions/$id.json"; val binRel = s"reference/renditions/$id.f32"
      if sys.env.contains("NP_WRITE_FIXTURES") || !FixtureIO.exists(binRel) then
        FixtureIO.writeBytes(jsonRel, hdr.getBytes("UTF-8"));
        FixtureIO.writeBytes(binRel, r.payload)
      assertEquals(FixtureIO.readText(jsonRel), hdr, s"$id header drifted")
      assert(
        java.util.Arrays.equals(FixtureIO.readBytes(binRel), r.payload),
        s"$id payload drifted"
      )
    }
  }
