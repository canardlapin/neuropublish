package neuropublish.conformance

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import neuropublish.protocol.Sha256
import neuropublish.protocol.json.*
import neuropublish.rendition.{SurfaceRendition, VertexFieldRendition, VolumeRendition}
import neuropublish.viewer.{
  LayerDisplay,
  LayerRepresentations,
  Threshold,
  Window,
  Workspace,
  WorkspaceLayer,
  WorkspaceLayout,
  WorkspaceState
}

/** The golden bundles: reference, valid, invalid, old-version, new-extension, and
  * schema-digest-mismatch, plus the schema documents' single-source-of-truth rules.
  *
  * An invalid fixture's `.expect` file lists the exact problems admission must report, one per
  * line, in order: each line is a prefix of the rendered problem (`<pointer>: <message>`, or just
  * `<message>` for a whole-document problem). Admission must report exactly those problems — a
  * spurious extra problem fails the fixture as surely as a missing one.
  */
class FixtureSuite extends FunSuite:
  private val fixtures =
    List("fixtures", "modules/conformance/fixtures").map(Path.of(_)).find(Files.isDirectory(_))
      .getOrElse(fail("fixtures directory not found from " + Path.of("").toAbsolutePath))
  private val schemas =
    List("../../protocol/schemas", "protocol/schemas").map(Path.of(_)).find(Files.isDirectory(_))
      .getOrElse(fail("protocol/schemas not found"))
  private def read(p: String) = Files.readAllBytes(fixtures.resolve(p))
  private def cases(dir: String): List[Path] =
    Files.list(fixtures.resolve(dir)).toList.asScala.filter(_.toString.endsWith(".json")).toList
      .sortBy(_.getFileName.toString)
  private def expect(p: Path): String =
    Files.readString(Path.of(p.toString.stripSuffix(".json") + ".expect")).trim
  private def expectedProblems(p: Path): List[String] =
    expect(p).linesIterator.map(_.trim).filter(_.nonEmpty).toList
  private def admitted(p: Path): Manifest =
    Manifest.parse(Files.readAllBytes(p)).fold(
      ps => fail(s"${p.getFileName} rejected: ${Problem.render(ps)}"),
      _._2
    )

  private def required[A](value: Either[io.circe.DecodingFailure, A]): A =
    value.fold(e => fail(e.message), identity)

  private def referenceDomain: HCursor =
    val manifest = parse(new String(read("reference/manifest.json"), StandardCharsets.UTF_8))
      .fold(e => fail(e.message), identity)
    manifest.hcursor.downField("domains").downArray.success
      .getOrElse(fail("reference manifest has no domain"))

  test("reference manifest is admitted and its digest matches java.security") {
    val bytes = read("reference/manifest.json")
    val ours = ByteProfile.admit(bytes).fold(vs => fail(vs.map(_.render).mkString("; ")), identity)
    val jdk = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      .map(b => f"${b & 0xff}%02x").mkString
    assertEquals(ours.hex, jdk)
    assertEquals(ours.hex, Files.readString(fixtures.resolve("reference/manifest.sha256")).trim)
  }

  test("reference manifest passes full admission; its records are read as intended") {
    val (digest, m) = Manifest.parse(read("reference/manifest.json")).fold(
      ps => fail(Problem.render(ps)),
      identity
    )
    assertEquals(digest.hex, Files.readString(fixtures.resolve("reference/manifest.sha256")).trim)
    assertEquals(m.sensitivity, Some("group-level"))
    val how = m.openRecords.map((p, _, i) => p -> i.getClass.getSimpleName).toMap
    assertEquals(how("/domains/0/descriptor"), "Understood")
    assertEquals(how("/analyses/0/method"), "Unsupported")
    assertEquals(how("/provenance/activities/2"), "Unsupported")
    assertEquals(
      m.analyses.flatMap(a => m.orderedEstimands(a).map(_.id)),
      List("speech", "articulatory-rate", "speech-vs-silence")
    )
    assertEquals(
      m.orderedFields("speech").map(_.id),
      List("speech-effect", "speech-se", "speech-t", "speech-z")
    )
    // Stage 5: two hemispheres, each a trusted surface-vertices domain realized by one surface
    assertEquals(how("/domains/1/descriptor"), "Understood")
    assertEquals(how("/domains/2/descriptor"), "Understood")
    assertEquals(
      m.surfaces.map(s => (s.id, s.asset, s.hemisphere, s.domain, s.kind)),
      List(
        ("lh-pial-surface", "lh-pial", "left", "ico3-lh", "pial"),
        ("rh-pial-surface", "rh-pial", "right", "ico3-rh", "pial")
      )
    )
    assertEquals(
      m.orderedFields("speech").find(_.id == "speech-t").get.representations.map(r =>
        (r.kind, r.asset, r.surface, r.hemisphere, r.derivation)
      ),
      List(
        ("volume", "speech-t", None, None, None),
        (
          "surface",
          "speech-t-lh",
          Some("lh-pial-surface"),
          Some("left"),
          Some("project-to-surface")
        ),
        (
          "surface",
          "speech-t-rh",
          Some("rh-pial-surface"),
          Some("right"),
          Some("project-to-surface")
        )
      )
    )
    assertEquals(
      m.renditionTargets.map(t => (t.assetId, t.kind, t.surface)),
      List(
        ("t1", "volume", None),
        ("speech-effect", "volume", None),
        ("speech-se", "volume", None),
        ("speech-t", "volume", None),
        ("speech-z", "volume", None),
        ("lh-pial", "surface-mesh", None),
        ("rh-pial", "surface-mesh", None),
        ("speech-t-lh", "vertex-field", Some("lh-pial-surface")),
        ("speech-t-rh", "vertex-field", Some("rh-pial-surface")),
        ("speech-z-lh", "vertex-field", Some("lh-pial-surface")),
        ("speech-z-rh", "vertex-field", Some("rh-pial-surface"))
      )
    )
  }

  test(
    "reference surface domains: key.size is the vertex count; the fingerprint is not recomputable here"
  ) {
    // what admission can verify without the GIFTI bytes (SPEC §6, admission split); the
    // fingerprint itself is proven by GiftiToRenditionSuite (rendition) and at ingestion
    val (_, m) =
      Manifest.parse(read("reference/manifest.json")).fold(ps => fail(Problem.render(ps)), identity)
    val volumeSpace = ManifestChecks.surfaceDomain(m, "ico3-lh").map(_.space)
    // a surface and a volume in one revision are in one space (B1): the surface domains say what
    // the volume domain says, and nothing links across spaces by raw millimetres
    assertEquals(
      volumeSpace,
      VolumeGrid.readPayload(
        "",
        m.domains.find(_.id == "mni-2mm").get.descriptor.payload
      ).toOption.map(_.space)
    )
    List("ico3-lh" -> "left", "ico3-rh" -> "right").foreach { (id, hemisphere) =>
      val p = ManifestChecks.surfaceDomain(m, id).getOrElse(fail(s"$id is not a surface domain"))
      assertEquals(
        (p.space, p.hemisphere, p.vertexCount, p.faceCount),
        ("MNI152NLin2009cAsym", hemisphere, 642, 1280)
      )
      // `topology` is the ASSET of a surfaces[] entry on this domain, never the entry's id (B2)
      assertEquals(p.topology, s"${id.drop(5)}-pial")
      assert(m.assets.exists(_.id == p.topology), p.topology)
      assert(!m.surfaces.exists(_.id == p.topology), p.topology)
      assertEquals(
        m.surfaces.find(_.asset == p.topology).map(_.id),
        Some(s"${id.drop(5)}-pial-surface")
      )
      val key = m.domains.find(_.id == id).flatMap(_.key).get.hcursor
      assertEquals(key.get[Long]("size"), Right(642L))
      val oracle =
        parse(new String(read("reference/assets/oracle.json"), StandardCharsets.UTF_8)).toOption.get
      val declared = oracle.hcursor.downField("surfaces").values.get
        .find(_.hcursor.get[String]("id").toOption.contains(p.topology)).get
        .hcursor.get[String]("structuralFingerprint").toOption.get
      assertEquals(key.get[String]("structuralFingerprint"), Right(declared))
    }
    // the fingerprint preimage spelled out independently (ADR 0005 §1 words) on a tiny mesh
    val faces = Array(0, 1, 2, 0, 2, 3)
    val p = SurfaceVertices.Payload("s", "left", 4, 2, "t")
    val strings = Vector("org.neuropublish.domain/surface-vertices", "1.0", "s", "left")
      .map(_.getBytes(StandardCharsets.UTF_8))
    val buffer =
      java.nio.ByteBuffer.allocate(8 + strings.map(4 + _.length).sum + 16 + faces.length * 4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put("NPUDOM1\u0000".getBytes(StandardCharsets.US_ASCII))
    strings.foreach(s => { buffer.putInt(s.length); buffer.put(s) })
    buffer.putLong(4L); buffer.putLong(2L)
    faces.foreach(buffer.putInt)
    assertEquals(
      SurfaceVertices.fingerprint(
        "org.neuropublish.domain/surface-vertices",
        "1.0",
        p,
        faces
      ).render,
      Sha256.of(buffer.array()).render
    )
  }

  test("reference volume domain uses the open descriptor and recomputable exact key") {
    val domain = referenceDomain
    assertEquals(domain.downField("kind").focus, None)

    val key = domain.downField("key")
    val descriptor = domain.downField("descriptor")
    val schema = descriptor.downField("schema")
    val payload = descriptor.downField("payload")
    assertEquals(key.downField("descriptor").focus, schema.focus)

    val descriptorId = required(schema.get[String]("id"))
    val descriptorVersion = required(schema.get[String]("version"))
    val p = VolumeGrid.readPayload("/domains/0/descriptor/payload", payload.focus.get)
      .fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(p.shape.length, 3)
    assertEquals(p.affine.map(_.length), Vector.fill(4)(4))
    assertEquals(required(key.get[Long]("size")), p.shape.map(_.toLong).product)

    // the preimage is spelled out here independently of VolumeGrid (ADR 0005 §2 words)
    val strings = Vector(
      descriptorId,
      descriptorVersion,
      p.space,
      p.coordinateConvention,
      p.spatialUnit,
      p.ordinalLayout
    ).map(_.getBytes(StandardCharsets.UTF_8))
    val buffer = java.nio.ByteBuffer.allocate(8 + strings.map(4 + _.length).sum + 12 + 128)
      .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put("NPUDOM1\u0000".getBytes(StandardCharsets.US_ASCII))
    strings.foreach(s => { buffer.putInt(s.length); buffer.put(s) })
    p.shape.foreach(buffer.putInt)
    p.affine.flatten.foreach(v => buffer.putDouble(if v == 0.0 then 0.0 else v))
    assertEquals(
      required(key.get[String]("structuralFingerprint")),
      Sha256.of(buffer.array()).render
    )
    assertEquals(
      VolumeGrid.fingerprint(descriptorId, descriptorVersion, p).render,
      Sha256.of(buffer.array()).render
    )

    val schemaBytes = read("reference/schemas/volume-grid-v1.schema.json")
    assertEquals(required(schema.get[String]("digest")), Sha256.of(schemaBytes).render)
  }

  test("a volume-grid key the server cannot recompute is rejected at the key member") {
    val bytes = read("reference/manifest.json")
    val json = parse(new String(bytes, StandardCharsets.UTF_8)).toOption.get
    def problemsWith(f: Json => Json): List[String] =
      Manifest.admit(f(json)).fold(_.map(_.pointer), _ => fail("admitted"))
    val key = json.hcursor.downField("domains").downArray.downField("key")
    assertEquals(
      problemsWith(_ => key.downField("size").set(Json.fromInt(1)).top.get),
      List("/domains/0/key/size")
    )
    assertEquals(
      problemsWith(_ => key.delete.top.get),
      List("/domains/0/key")
    )
    val affine = json.hcursor.downField("domains").downArray.downField("descriptor")
      .downField("payload").downField("affine").downArray.downArray
    assertEquals(
      problemsWith(_ => affine.set(Json.fromDoubleOrNull(2.5)).top.get),
      List("/domains/0/key/structuralFingerprint")
    )
  }

  test("the trusted volume-grid schema is the one under protocol/schemas/records") {
    val protocolCopy = Files.readAllBytes(schemas.resolve("records/volume-grid-v1.schema.json"))
    assert(java.util.Arrays.equals(
      protocolCopy,
      read("reference/schemas/volume-grid-v1.schema.json")
    ))
    assertEquals(
      TrustedSchemas.VolumeGridV1.digest.map(_.render),
      Some(Sha256.of(protocolCopy).render)
    )
  }

  test("the trusted surface-vertices schema is the one under protocol/schemas/records") {
    val protocolCopy =
      Files.readAllBytes(schemas.resolve("records/surface-vertices-v1.schema.json"))
    assert(java.util.Arrays.equals(
      protocolCopy,
      read("reference/schemas/surface-vertices-v1.schema.json")
    ))
    assertEquals(
      TrustedSchemas.SurfaceVerticesV1.digest.map(_.render),
      Some(Sha256.of(protocolCopy).render)
    )
  }

  test("every valid fixture is admitted") {
    val all = cases("valid")
    assert(all.nonEmpty)
    all.foreach(admitted)
    val unknown = admitted(fixtures.resolve("valid/unknown-fields.json"))
    assertEquals(unknown.raw.hcursor.downField("acknowledgements").succeeded, true)
    assert(unknown.openRecords.exists {
      case (p, r, Interpretation.Unsupported(_)) =>
        p == "/provenance/activities/4" && r.schema.id == "org.example.lab/denoise"
      case _ => false
    })
  }

  test("every invalid fixture is rejected with exactly the problems its .expect lists") {
    val all = cases("invalid")
    assert(all.length >= 34, all.length)
    all.foreach { p =>
      val expected = expectedProblems(p)
      assert(expected.nonEmpty, s"${p.getFileName}: empty .expect")
      Manifest.parse(Files.readAllBytes(p)) match
        case Right(_) => fail(s"${p.getFileName} was admitted")
        case Left(ps) =>
          val rendered = ps.map(_.render)
          assertEquals(
            rendered.length,
            expected.length,
            s"${p.getFileName}: expected exactly ${expected.mkString(" | ")}, got ${rendered.mkString(" | ")}"
          )
          rendered.zip(expected).foreach((got, want) =>
            assert(got.startsWith(want), s"${p.getFileName}: expected '$want', got '$got'")
          )
    }
  }

  test("invalid byte-profile fixtures still report byte offsets, and duplicate keys a pointer") {
    val dup = Manifest.parse(read("invalid/duplicate-nested.json")).left.getOrElse(Nil)
    assert(dup.exists(p => p.pointer.nonEmpty && p.message.startsWith("byte ")), dup)
  }

  test("old-version: core 0.0 is brought to 0.1 by the migration, original bytes keep the digest") {
    val p = fixtures.resolve("old-version/core-0.0.json")
    val bytes = Files.readAllBytes(p)
    val (digest, m) = Manifest.parse(bytes).fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(digest.hex, Sha256.of(bytes).hex)
    assertEquals(m.migratedFrom, Some("0.0"))
    assertEquals(m.core, "0.1")
    assertEquals(m.synopsis.isDefined, true)
    assertEquals(m.raw.hcursor.downField("description").succeeded, false)
    assertEquals(expect(p), "migratedFrom 0.0")
    // the original is still what the producer wrote
    val original = parse(new String(bytes, "UTF-8")).toOption.get
    assertEquals(original.hcursor.get[String]("core"), Right("0.0"))
    assert(original.hcursor.downField("description").succeeded)
  }

  test("new-extension: core 0.2 with unknown fields is admitted and round-trips value-for-value") {
    val p = fixtures.resolve("new-extension/core-0.2-extensions.json")
    val bytes = Files.readAllBytes(p)
    val original = parse(new String(bytes, "UTF-8")).toOption.get
    val (_, m) = Manifest.parse(bytes).fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(m.core, "0.2")
    assertEquals(m.migratedFrom, None)
    assertEquals(m.raw, original)
    // encode → decode → encode is the identity on the JSON value (not on whitespace)
    val reencoded = parse(m.raw.spaces2).toOption.get
    assertEquals(reencoded, original)
    val again = Manifest.parse(m.raw.noSpaces.getBytes("UTF-8")).fold(
      ps => fail(Problem.render(ps)),
      _._2
    )
    assertEquals(again.raw, original)
    for pointer <- List(
        "/license",
        "/assets/3/compression/codec",
        "/analyses/0/preregistration",
        "/resultFields/2/uncertainty/level",
        "/domains/0/descriptor/payload/voxelUnits/2"
      )
    do assert(at(again.raw, pointer).isDefined, s"$pointer lost")
  }

  private def at(json: Json, pointer: String): Option[Json] =
    pointer.split('/').drop(1).foldLeft(Option(json)) { (acc, tok) =>
      acc.flatMap(j => j.asArray.flatMap(_.lift(tok.toInt)).orElse(j.asObject.flatMap(_(tok))))
    }

  test("schema-digest-mismatch: trusted id rejected, unknown id retained as unsupported") {
    val trusted = fixtures.resolve("schema-digest-mismatch/trusted-volume-grid.json")
    Manifest.parse(Files.readAllBytes(trusted)) match
      case Right(_) => fail("trusted id with a foreign digest was admitted")
      case Left(ps) =>
        assertEquals(ps.map(_.pointer), expectedProblems(trusted).map(_.takeWhile(_ != ':')))
    val unknown = fixtures.resolve("schema-digest-mismatch/unknown-volume-grid.json")
    val m = admitted(unknown)
    val d = m.openRecords.find(_._1 == "/domains/0/descriptor").map(_._3)
    assert(d.exists(_.isInstanceOf[Interpretation.Unsupported]), d)
    assertEquals(expect(unknown), "unsupported org.example.lab/volume-grid@1.0")
  }

  test("rendition-header.schema.json accepts the reference rendition headers") {
    for id <- List("t1", "speech-t", "speech-z") do
      val text = new String(read(s"reference/renditions/$id.json"), StandardCharsets.UTF_8)
      val json = parse(text).toOption.get
      assertEquals(SchemaCheck.renditionHeader(json), Nil, id)
      assert(VolumeRendition.decodeHeader(text).isRight)
    for id <- List("lh-pial", "rh-pial") do
      val text = new String(read(s"reference/renditions/$id.json"), StandardCharsets.UTF_8)
      assertEquals(SchemaCheck.renditionHeader(parse(text).toOption.get), Nil, id)
      val h = SurfaceRendition.decodeHeader(text).fold(m => fail(s"$id: $m"), identity)
      // world positions: the source transform is provenance, surfaceToWorld the identity
      assertEquals(h.surfaceToWorld, SurfaceRendition.Identity)
      assert(h.faceDigest.isDefined, id)
      assert(h.sourceTransform.isDefined, id)
      assertEquals(h.space, "MNI152NLin2009cAsym", id)
    for id <- List("speech-t-lh", "speech-z-rh") do
      val text = new String(read(s"reference/renditions/$id.json"), StandardCharsets.UTF_8)
      assertEquals(SchemaCheck.renditionHeader(parse(text).toOption.get), Nil, id)
      assert(VertexFieldRendition.decodeHeader(text).isRight)
    val badSurface = parse(
      """{"profile":"org.neuropublish.rendition/surface-mesh@0","hemisphere":"both","kind":"pial",
      "space":"MNI152NLin2009cAsym","vertexCount":4,"faceCount":2,"surfaceToWorld":[],
      "coordinateSystem":"RAS+","topologyIdentity":"x"}"""
    ).toOption.get
    assert(SchemaCheck.renditionHeader(badSurface).nonEmpty)
    assert(SurfaceRendition.decodeHeader(badSurface.noSpaces).isLeft)
    val bad =
      parse("""{"profile":"org.neuropublish.rendition/volume-f32@0","shape":[1,2],"affine":[],
      "dtype":"float64","byteOrder":"little","order":"x-fastest","missing":"nan"}""").toOption.get
    val ps = SchemaCheck.renditionHeader(bad).map(_.pointer)
    assert(ps.contains("/shape"), ps)
    assert(ps.contains("/dtype"), ps)
  }

  test("workspace-state.schema.json accepts what WorkspaceState encodes") {
    // With layers, and with every optional member present: an empty workspace would agree with the
    // schema about parts neither of them has.
    val display = LayerDisplay(
      visible = true,
      opacity = 0.6,
      window = Window(-8, 8),
      threshold = Threshold("two-sided", 3.1, Some(12.0)),
      colormap = "cold-hot"
    )
    val ws = Workspace(
      Vector(
        WorkspaceLayer("speech-t", display, display, true, LayerRepresentations(true, Set("left"))),
        WorkspaceLayer(
          "speech-z",
          display,
          display.copy(threshold = Threshold("positive", 2.3)),
          false,
          LayerRepresentations(false, Set("left", "right"))
        )
      ),
      Some((1.0, -2.0, 3.5)),
      WorkspaceLayout.default,
      "layers"
    )
    val json = WorkspaceState.encode(ws)
    assertEquals(SchemaCheck.workspaceState(json), Nil)
    assertEquals(WorkspaceState.decode(json), Right(ws))
    // a threshold with no maximum magnitude omits the member rather than writing a null
    assert(
      !json.hcursor.downField("payload").downField("layers").downN(1).downField("current")
        .downField("threshold").downField("max").succeeded
    )
    val bad = json.hcursor.downField("payload").downField("inspector").set(Json.fromString("x")).top
      .get
    assertEquals(SchemaCheck.workspaceState(bad).map(_.pointer), List("/payload/inspector"))
  }

  test("the committed Julia bundle is admitted with its recorded digest and unknown fields") {
    val jbytes = Files.readAllBytes(fixtures.resolve("julia/manifest.json"))
    val (digest, manifest) = Manifest.parse(jbytes).fold(ps => fail(ps.mkString("; ")), identity)
    assertEquals(digest.hex, Files.readString(fixtures.resolve("julia/manifest.sha256")).trim)
    assertEquals(manifest.core, "0.1")
    assertEquals(
      manifest.volumeAssetIds.sorted,
      List("speech-effect", "speech-t", "speech-z", "t1")
    )
    manifest.assets.foreach { a =>
      val name =
        if a.mediaType == "application/x-nifti" then s"${a.id}.nii"
        else if manifest.surfaceAssetIds.contains(a.id) then s"${a.id}.surf.gii"
        else s"${a.id}.func.gii"
      val file = Files.readAllBytes(fixtures.resolve(s"julia/assets/$name"))
      assertEquals(Sha256.of(file).render, a.digest.render, a.id)
      assertEquals(file.length.toLong, a.size, a.id)
    }
    // the producer's own spelling: a surfaces[] id that happens to equal its asset. The reference
    // bundle spells them apart, so a check that confuses the two fails on one of the two bundles.
    assertEquals(
      manifest.surfaces.map(s => (s.id, s.asset)),
      List(("lh-pial", "lh-pial"), ("rh-pial", "rh-pial"))
    )
    assertEquals(manifest.renditionTargets.length, 10)
    val raw = manifest.raw.hcursor
    assertEquals(raw.downField("x-julia-producer").downField("version").as[String], Right("0.1"))
    // deterministic output: nothing in the bundle names the Julia that wrote it
    assertEquals(raw.downField("x-julia-producer").downField("julia").succeeded, false)
    assertEquals(
      manifest.domains.headOption.flatMap(_.key).flatMap(_.hcursor.get[Long]("size").toOption),
      Some(16L * 16 * 12)
    )
    assert(raw.downField("provenance").downField("activities").values.exists(_.exists(
      _.hcursor.downField("schema").downField("id").as[String] == Right("org.example.julia/denoise")
    )))
  }
