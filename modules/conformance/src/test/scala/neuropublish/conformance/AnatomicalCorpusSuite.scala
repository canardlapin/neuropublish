package neuropublish.conformance

import java.nio.file.{Files, Path}
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import munit.FunSuite
import neuropublish.protocol.Sha256
import neuropublish.rendition.{SurfaceRendition, VolumeRendition}
import scalafim.image.io.Nifti
import scalafim.surface.{Hemisphere, SurfaceGeometry, SurfaceKind}
import scalafim.surface.io.GiftiSurfaceReader

/** Licensed, anatomically realistic samples for the viewer boundary. These are deliberately not a
  * bundle: the MNI volume and dHCP surfaces have different spatial identities and must never be
  * admitted as a co-registered scientific scene merely because both render.
  */
class AnatomicalCorpusSuite extends FunSuite:
  private val corpus =
    List(
      Path.of("fixtures/anatomical"),
      Path.of("modules/conformance/fixtures/anatomical")
    ).find(Files.isDirectory(_)).getOrElse(
      fail("anatomical corpus not found from " + Path.of("").toAbsolutePath)
    )

  private val inventory: Json =
    parse(Files.readString(corpus.resolve("corpus.json"))).fold(e => fail(e.message), identity)

  private def required[A](value: Either[io.circe.DecodingFailure, A]): A =
    value.fold(e => fail(e.message), identity)

  private def entries: List[HCursor] =
    inventory.hcursor.downField("files").values.getOrElse(fail("corpus.json has no files"))
      .toList.map(_.hcursor)

  private val expected = Map(
    "assets/tpl-MNI152NLin2009cAsym_res-02_desc-brain_T1w.nii.gz" ->
      ("sha256:7b168153a9508f5f116ac37d33835fa44dd168ba7a9ae31a3b33e6f63bd23292", 451810L),
    "assets/tpl-dhcpAsym_cohort-42_hemi-L_den-32k_pial.surf.gii" ->
      ("sha256:914d9581e534fb9f57eeaa891a4adec9239a1bbed585d76191da43e426b3328c", 719569L),
    "assets/tpl-dhcpAsym_cohort-42_hemi-R_den-32k_pial.surf.gii" ->
      ("sha256:60a032699222380f0d386943279f3d5a61f8ce8d73c029dbb711dab8f26afbe8", 709066L)
  )

  test(
    "inventory pins unchanged source bytes, immutable commits, and separate spatial identities"
  ) {
    val policy = inventory.hcursor.downField("policy")
    assertEquals(
      required(inventory.hcursor.get[String]("schema")),
      "org.neuropublish.acceptance/anatomical-corpus@1"
    )
    assertEquals(required(policy.get[Boolean]("coRegistered")), false)
    assertEquals(required(policy.get[Boolean]("scientificAnalysis")), false)
    assertEquals(entries.length, expected.size)

    val observed = entries.map { c =>
      val rel = required(c.get[String]("relativePath"))
      val (digest, size) = expected.getOrElse(rel, fail(s"unexpected corpus file $rel"))
      val path = corpus.resolve(rel)
      assert(Files.isRegularFile(path), s"missing $rel")
      assertEquals(Files.size(path), size, rel)
      assertEquals(Sha256.of(Files.readAllBytes(path)).render, digest, rel)
      assertEquals(required(c.get[String]("sha256")), digest, rel)
      assertEquals(required(c.get[Long]("size")), size, rel)
      assertEquals(required(c.get[Boolean]("modified")), false, rel)
      assert(required(c.get[String]("sourceUrl")).startsWith("https://"), rel)
      assert(
        required(c.get[String]("repositoryUrl")).startsWith("https://github.com/templateflow/"),
        rel
      )
      val commit = required(c.get[String]("sourceCommit"))
      assert(commit.matches("[0-9a-f]{40}"), rel)
      assert(required(c.get[String]("licenseUrl")).contains(commit), rel)
      assert(
        Set("MNI-template-permissive", "CC-BY-4.0").contains(
          required(c.get[String]("license"))
        ),
        rel
      )
      rel -> required(c.get[String]("space"))
    }.toMap
    assertEquals(observed.keySet, expected.keySet)
    assertEquals(observed.values.toSet, Set("MNI152NLin2009cAsym", "dhcpAsym:cohort-42"))

    val notice = Files.readString(corpus.resolve("LICENSE-MNI152NLin2009cAsym.txt"))
    assert(notice.contains("Louis Collins"))
    assert(notice.replaceAll("\\s+", " ").contains(
      "provided that the above copyright notice appear in all copies"
    ))
    val readme = Files.readString(corpus.resolve("README.md"))
    assert(readme.contains("https://creativecommons.org/licenses/by/4.0/"))
    assert(readme.contains("must not be combined into a scientific overlay"))
  }

  test("the licensed MNI underlay traverses the production NIfTI rendition path") {
    val rel = "assets/tpl-MNI152NLin2009cAsym_res-02_desc-brain_T1w.nii.gz"
    val volume = Nifti.readVol(corpus.resolve(rel))
    val rendition = VolumeRendition.encode(volume, Some(rel))
    val summary = rendition.header.summary.getOrElse(fail("volume summary absent"))

    assertEquals(rendition.header.shape, Vector(97, 115, 97))
    assertEquals(
      rendition.header.affine,
      Vector(
        Vector(2.0, 0.0, 0.0, -96.5),
        Vector(0.0, 2.0, 0.0, -132.5),
        Vector(0.0, 0.0, 2.0, -78.5),
        Vector(0.0, 0.0, 0.0, 1.0)
      )
    )
    assertEquals(rendition.payload.length, 97 * 115 * 97 * 4)
    assertEquals(summary.finite, 97 * 115 * 97)
    assertEquals(summary.missing, 0)
    assertEquals(summary.min, 0.0)
    assertEquals(summary.max, 9663.0)
    assert(summary.zero > 0)
    assertEquals(summary.histogram.sum, summary.finite)
    assert(VolumeRendition.decode(rendition.header, rendition.payload).isRight)
  }

  private final case class MeshEvidence(
      geometry: SurfaceGeometry,
      meanX: Double,
      spans: Vector[Double],
      radiusCv: Double
  )

  private def surfaceEvidence(rel: String, hemisphere: String): MeshEvidence =
    val geometry = GiftiSurfaceReader.read(
      corpus.resolve(rel),
      Hemisphere.fromString(hemisphere),
      SurfaceKind.Pial
    )
    val coordinates = geometry.mesh.coordinates
    val vertexCount = geometry.vertexCount
    val means = Vector.tabulate(3)(axis =>
      (0 until vertexCount).iterator.map(i => coordinates(i * 3 + axis)).sum / vertexCount
    )
    val spans = Vector.tabulate(3) { axis =>
      val values = (0 until vertexCount).iterator.map(i => coordinates(i * 3 + axis)).toVector
      values.max - values.min
    }
    val radii = Vector.tabulate(vertexCount) { i =>
      math.sqrt((0 until 3).map { axis =>
        val delta = coordinates(i * 3 + axis) - means(axis)
        delta * delta
      }.sum)
    }
    val radiusMean = radii.sum / radii.length
    val radiusSd = math.sqrt(radii.map(r => math.pow(r - radiusMean, 2)).sum / radii.length)
    MeshEvidence(geometry, means.head, spans, radiusSd / radiusMean)

  test("the licensed dHCP pial pair is dense, anatomical, and rendition-ready") {
    val surfaces = List(
      (
        "assets/tpl-dhcpAsym_cohort-42_hemi-L_den-32k_pial.surf.gii",
        "left",
        "CortexLeft"
      ),
      (
        "assets/tpl-dhcpAsym_cohort-42_hemi-R_den-32k_pial.surf.gii",
        "right",
        "CortexRight"
      )
    ).map { (rel, hemisphere, anatomy) =>
      val source = Files.readString(corpus.resolve(rel))
      assert(source.contains(anatomy), s"$rel lacks $anatomy metadata")
      val evidence = surfaceEvidence(rel, hemisphere)
      val geometry = evidence.geometry
      assertEquals((geometry.vertexCount, geometry.faceCount), (32492, 64980), rel)
      assert(evidence.spans.forall(_ > 40.0), s"$rel has implausible extents ${evidence.spans}")
      assert(
        evidence.radiusCv > 0.20,
        s"$rel is too sphere-like for the anatomical corpus: radius CV ${evidence.radiusCv}"
      )

      val rendition = SurfaceRendition.encode(
        geometry,
        "dhcpAsym:cohort-42",
        Some(rel),
        anatomicalStructurePrimary = Some(anatomy)
      ).fold(fail(_), identity)
      assertEquals(rendition.header.hemisphere, hemisphere)
      assertEquals(rendition.header.anatomicalStructurePrimary, Some(anatomy))
      assertEquals(
        rendition.payload.length,
        (geometry.vertexCount * 3 + geometry.faceCount * 3) * 4
      )
      assert(SurfaceRendition.decode(rendition.header, rendition.payload).isRight)
      evidence
    }
    assert(surfaces.head.meanX > surfaces(1).meanX)
  }
