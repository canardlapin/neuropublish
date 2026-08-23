package neuropublish.backend

import munit.FunSuite
import scalafim.surface.io.GiftiReader

/** What ingestion decides from GIFTI bytes alone, on hand-written ASCII GIFTI documents (SPEC §5):
  * which data array is the field and whether it is one this profile can carry (S3), whether the
  * declared hemisphere survives contact with `AnatomicalStructurePrimary` (S6), and how the
  * `CoordinateSystemTransformMatrix` is applied — or refused — so the payload is world positions
  * and the header's `surfaceToWorld` is the identity (S5).
  */
class DerivationGiftiSuite extends FunSuite:
  private def document(arrays: String*): scalafim.surface.gifti.GiftiDocument =
    GiftiReader.parseString(
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<GIFTI Version="1.0" NumberOfDataArrays="${arrays.length}">
         |<MetaData/>
         |${arrays.mkString("\n")}
         |</GIFTI>
         |""".stripMargin
    ).fold(e => fail(e.message), identity)

  private def array(
      intent: String,
      dataType: String,
      dims: Seq[Int],
      data: String,
      metadata: Seq[(String, String)] = Nil,
      transform: Option[(String, String, String)] = None
  ): String =
    val dimAttrs = dims.zipWithIndex.map((d, i) => s"""Dim$i="$d"""").mkString(" ")
    val md =
      if metadata.isEmpty then "<MetaData/>"
      else
        metadata.map((k, v) => s"<MD><Name>$k</Name><Value>$v</Value></MD>")
          .mkString("<MetaData>", "", "</MetaData>")
    val xf = transform.fold("") { (from, to, matrix) =>
      s"<CoordinateSystemTransformMatrix><DataSpace>$from</DataSpace>" +
        s"<TransformedSpace>$to</TransformedSpace><MatrixData>$matrix</MatrixData>" +
        "</CoordinateSystemTransformMatrix>"
    }
    s"""<DataArray Intent="$intent" DataType="$dataType" ArrayIndexingOrder="RowMajorOrder"
       |  Dimensionality="${dims.length}" $dimAttrs Encoding="ASCII" Endian="LittleEndian">
       |$md$xf<Data>$data</Data>
       |</DataArray>""".stripMargin

  private val identity4 = "1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1"
  private val translate = "1 0 0 1 0 1 0 2 0 0 1 3 0 0 0 1"

  /** A tetrahedron: four vertices, four faces, one value per vertex. */
  private val positions = "0 0 0 1 0 0 0 1 0 0 0 1"
  private val faces = "0 1 2 0 1 3 0 2 3 1 2 3"

  private def surface(
      structure: Option[String] = Some("CortexLeft"),
      transform: Option[(String, String, String)] = Some(
        ("NIFTI_XFORM_SCANNER_ANAT", "NIFTI_XFORM_SCANNER_ANAT", identity4)
      ),
      dataType: String = "NIFTI_TYPE_FLOAT32"
  ) =
    document(
      array(
        "NIFTI_INTENT_POINTSET",
        dataType,
        Seq(4, 3),
        positions,
        structure.toSeq.map("AnatomicalStructurePrimary" -> _),
        transform
      ),
      array("NIFTI_INTENT_TRIANGLE", "NIFTI_TYPE_INT32", Seq(4, 3), faces)
    )

  private def field(
      intent: String = "NIFTI_INTENT_NONE",
      dataType: String = "NIFTI_TYPE_FLOAT32",
      dims: Seq[Int] = Seq(4),
      data: String = "1 2 3 4"
  ) = document(array(intent, dataType, dims, data))

  private def values(doc: scalafim.surface.gifti.GiftiDocument) =
    Derivation.fieldValues("f", doc, "lh-surface", 4)

  test("a sparse field (NODE_INDEX) is refused by name, not read as values") {
    val doc = document(
      array("NIFTI_INTENT_NODE_INDEX", "NIFTI_TYPE_INT32", Seq(2), "0 3"),
      array("NIFTI_INTENT_NONE", "NIFTI_TYPE_FLOAT32", Seq(2), "1.5 2.5")
    )
    val message = values(doc).left.getOrElse(fail("a sparse field was accepted"))
    assert(message.contains("NIFTI_INTENT_NODE_INDEX"), message)
    assert(message.contains("sparse vertex fields are not supported"), message)
  }

  test("a rank-2 time series is refused for what it is, not through a vertex count") {
    val doc = field("NIFTI_INTENT_TIME_SERIES", dims = Seq(4, 3), data = "1 2 3 4 5 6 7 8 9 1 2 3")
    val message = values(doc).left.getOrElse(fail("a V×T array was accepted"))
    assert(message.contains("4×3"), message)
    assert(message.contains("NIFTI_INTENT_TIME_SERIES"), message)
    assert(message.contains("one scalar per vertex"), message)
    assert(!message.contains("vertex values but surface"), message)
  }

  test("a rank-2 column vector (Dim1 = 1) is one scalar per vertex") {
    assertEquals(values(field(dims = Seq(4, 1))).map(_.toList), Right(List(1.0, 2.0, 3.0, 4.0)))
  }

  test("a float64 field is refused with the type it must be written in") {
    val message = values(field(dataType = "NIFTI_TYPE_FLOAT64")).left.getOrElse(fail("accepted"))
    assert(message.contains("NIFTI_TYPE_FLOAT64"), message)
    assert(message.contains("float32 vertex fields only"), message)
  }

  test("a field of the wrong length still names the counts") {
    val message = values(field(dims = Seq(3), data = "1 2 3")).left.getOrElse(fail("accepted"))
    assert(message.contains("3 vertex values but surface lh-surface has 4 vertices"), message)
  }

  test("a document with only geometry has no field") {
    val message = Derivation.fieldValues("f", surface(), "lh-surface", 4).left
      .getOrElse(fail("accepted"))
    assert(message.contains("no per-vertex data array"), message)
  }

  private def placed(
      doc: scalafim.surface.gifti.GiftiDocument,
      hemisphere: String = "left"
  ) = Derivation.worldGeometry("lh-pial", "lh-surface", doc, hemisphere, "pial")

  test("the GIFTI's anatomy must agree with the declared hemisphere") {
    val message = placed(surface(), "right").left.getOrElse(fail("a swapped hemisphere passed"))
    assert(message.contains("declared the right hemisphere"), message)
    assert(message.contains("AnatomicalStructurePrimary=CortexLeft"), message)
    // and the agreeing cases, including the underscore spelling
    assertEquals(
      placed(surface(structure = Some("CortexRight")), "right").map(_._3),
      Right(Some("CortexRight"))
    )
    assertEquals(placed(surface(structure = Some("CORTEX_LEFT"))).map(_._3), Right(Some(
      "CORTEX_LEFT"
    )))
    // a structure that is neither hemisphere is not silently taken for the declared one
    assert(placed(surface(structure = Some("CortexLeftAndRight"))).isLeft)
    // and a GIFTI that says nothing is taken at the manifest's word
    assertEquals(placed(surface(structure = None)).map(_._3), Right(None))
  }

  test("a transform into a known space is applied once; surfaceToWorld stays the identity") {
    val (geometry, transform, _) = placed(
      surface(transform = Some(("NIFTI_XFORM_SCANNER_ANAT", "NIFTI_XFORM_SCANNER_ANAT", translate)))
    ).fold(m => fail(m), identity)
    assertEquals(
      geometry.mesh.coordinates.toList.take(6),
      List(1.0, 2.0, 3.0, 2.0, 2.0, 3.0)
    )
    assertEquals(
      geometry.surfaceToWorld.data.toVector,
      Vector.tabulate(16)(i => if i % 5 == 0 then 1.0 else 0.0)
    )
    assertEquals(transform.map(_.transformedSpace), Some(Some("NIFTI_XFORM_SCANNER_ANAT")))
    assertEquals(transform.map(_.matrix.head), Some(Vector(1.0, 0.0, 0.0, 1.0)))
  }

  test("an unplaceable transform is refused unless it says nothing") {
    val unknown = ("NIFTI_XFORM_UNKNOWN", "NIFTI_XFORM_UNKNOWN", translate)
    val message = placed(surface(transform = Some(unknown))).left.getOrElse(fail("accepted"))
    assert(message.contains("NIFTI_XFORM_UNKNOWN"), message)
    assert(message.contains("cannot be placed in RAS+ world coordinates"), message)
    // the identity into an unknown space moves nothing, so it is no reason to refuse
    val idUnknown = ("NIFTI_XFORM_UNKNOWN", "NIFTI_XFORM_UNKNOWN", identity4)
    val (geometry, transform, _) =
      placed(surface(transform = Some(idUnknown))).fold(m => fail(m), identity)
    assertEquals(geometry.mesh.coordinates.toList.take(3), List(0.0, 0.0, 0.0))
    assertEquals(transform.map(_.dataSpace), Some(Some("NIFTI_XFORM_UNKNOWN")))
    // no transform at all: the positions as written, and nothing to record
    val (bare, none, _) = placed(surface(transform = None)).fold(m => fail(m), identity)
    assertEquals(bare.mesh.coordinates.toList.take(3), List(0.0, 0.0, 0.0))
    assertEquals(none, None)
  }

  test("float64 coordinates are refused with the type the geometry must be written in") {
    val message = placed(surface(dataType = "NIFTI_TYPE_FLOAT64")).left.getOrElse(fail("accepted"))
    assert(message.contains("NIFTI_TYPE_FLOAT64"), message)
    assert(message.contains("float32 GIFTI sources only"), message)
  }
