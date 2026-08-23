package neuropublish.frontend

import munit.FunSuite

class SurfacePlacementSuite extends FunSuite:
  private def decl(id: String, hemi: String, kind: String = "pial") =
    SurfaceDecl(id, id, s"dom-$id", hemi, kind, id)

  test("one surface per hemisphere: the first declared when nothing targets a surface") {
    val placed =
      SurfacePlacement.place(List(decl("lh-pial", "left"), decl("rh-pial", "right")), Set())
    assertEquals(placed.view.mapValues(_.id).toMap, Map("left" -> "lh-pial", "right" -> "rh-pial"))
  }

  test("two decoded surfaces on one hemisphere: the slot takes the one a field targets") {
    val decoded =
      List(decl("lh-pial", "left"), decl("lh-white", "left", "white"), decl("rh-pial", "right"))
    val placed = SurfacePlacement.place(decoded, Set("lh-white"))
    assertEquals(placed("left").id, "lh-white")
    assertEquals(placed("right").id, "rh-pial")
  }

  test("both surfaces targeted: manifest order decides, deterministically") {
    val decoded = List(decl("lh-white", "left", "white"), decl("lh-pial", "left"))
    assertEquals(SurfacePlacement.place(decoded, Set("lh-pial", "lh-white"))("left").id, "lh-white")
    assertEquals(
      SurfacePlacement.place(decoded.reverse, Set("lh-pial", "lh-white"))("left").id,
      "lh-pial"
    )
  }

  test("hemispheres other than left/right are never placed") {
    assertEquals(SurfacePlacement.place(List(decl("both", "bilateral")), Set("both")), Map.empty)
  }

  test(
    "layer ids are keyed by the placed surface, so two surfaces on one hemisphere cannot collide"
  ) {
    val a = SurfacePlacement.layerId("speech-t", "lh-pial")
    val b = SurfacePlacement.layerId("speech-t", "lh-white")
    assertNotEquals(a, b)
    assertEquals(SurfacePlacement.splitLayerId(a), Some(("speech-t", "lh-pial")))
    assertEquals(SurfacePlacement.splitLayerId("nope"), None)
  }

class SpaceGuardSuite extends FunSuite:
  import SpaceGuard.Decision

  test("same space links") {
    assertEquals(
      SpaceGuard.decide(Some("MNI152NLin2009cAsym"), Some("MNI152NLin2009cAsym")),
      Decision.Link
    )
  }

  test("different spaces never link and the message names both") {
    val d = SpaceGuard.decide(Some("synthetic-ico3"), Some("MNI152NLin2009cAsym"))
    assertEquals(d, Decision.Mismatch("synthetic-ico3", "MNI152NLin2009cAsym"))
    d match
      case m: Decision.Mismatch =>
        assertEquals(
          SpaceGuard.message(m),
          "not linked: surface space synthetic-ico3 ≠ volume space MNI152NLin2009cAsym"
        )
      case _ => fail("expected a mismatch")
  }

  test("an absent space on either side is tolerated (headers without `space` still link)") {
    assertEquals(SpaceGuard.decide(None, Some("MNI152NLin2009cAsym")), Decision.Link)
    assertEquals(SpaceGuard.decide(Some("synthetic-ico3"), None), Decision.Link)
    assertEquals(SpaceGuard.decide(Some("  "), Some("x")), Decision.Link)
  }
