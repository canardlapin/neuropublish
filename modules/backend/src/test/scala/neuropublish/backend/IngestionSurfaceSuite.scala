package neuropublish.backend

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.protocol.Sha256
import neuropublish.rendition.{SurfaceRendition, VertexFieldRendition}
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*

/** Stage 5 ingestion of surfaces (SPEC §6, admission split): the reference bundle's GIFTI surfaces
  * and vertex fields derive `surface-mesh@0` and `vertex-field-f32@0` renditions that decode on the
  * server; a surface whose triangles do not realize its domain's key, and a field whose vertex
  * count is not its surface's, are refused with a message naming the asset — inline here (the push
  * fails); `WorkerSuite` (ingestion) covers the worker path (the job fails).
  */
class IngestionSurfaceSuite extends CatsEffectSuite:
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val token = "t"

  final case class Env(app: HttpApp[IO], stores: Server.Storage, data: Path)
  private def env(mode: IngestionMode) = ResourceFunFixture(
    Files[IO].tempDirectory.evalMap { dir =>
      val stores = Server.localStorage(dir, mode)
      Server.build(dir, key, "http://test", legacyToken = Some(token), storage = Some(stores))
        .map(routes => Env(routes.orNotFound, stores, dir))
    }
  )
  private val inline = env(IngestionMode.Inline)

  private def auth(r: Request[IO]) =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)
  private def decode[A: io.circe.Decoder](r: org.http4s.Response[IO]): IO[A] =
    r.as[String].flatMap(b => IO.fromEither(io.circe.parser.decode[A](b)))
  private def get(app: HttpApp[IO], path: String) =
    app.run(auth(Request[IO](Method.GET, Uri.unsafeFromString(path))))

  /** The `space` the reference manifest's domain `id` declares (never hard-coded here). */
  private def domainSpace(id: String): IO[String] =
    bytes("reference/manifest.json").map { b =>
      io.circe.parser.parse(new String(b, "UTF-8")).toOption.get
        .hcursor.downField("domains").values.get
        .find(_.hcursor.get[String]("id").toOption.contains(id)).get
        .hcursor.downField("descriptor").downField("payload").get[String]("space").toOption.get
    }

  /** The reference bundle's `lh-pial` with the first two vertex ordinals of face 0 swapped: the
    * same vertex set, a different ordered topology, so its ADR 0005 key differs from the domain's.
    */
  private def permutedSurface(original: Array[Byte]): Array[Byte] =
    val text = new String(original, "UTF-8")
    val data = "<Data>([A-Za-z0-9+/=]+)</Data>".r.findAllMatchIn(text).toList
    assertEquals(data.length, 2)
    val tri = java.util.Base64.getDecoder.decode(data(1).group(1))
    for k <- 0 until 4 do
      val a = tri(k); tri(k) = tri(4 + k); tri(4 + k) = a
    val swapped = java.util.Base64.getEncoder.encodeToString(tri)
    (text.substring(0, data(1).start(1)) + swapped + text.substring(data(1).end(1)))
      .getBytes("UTF-8")

  /** A four-vertex ASCII `.func.gii`: readable GIFTI, wrong length for a 642-vertex surface. */
  private val shortField: Array[Byte] =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<GIFTI Version="1.0" NumberOfDataArrays="1">
      |<DataArray Intent="NIFTI_INTENT_NONE" DataType="NIFTI_TYPE_FLOAT32" ArrayIndexingOrder="RowMajorOrder" Dimensionality="1" Dim0="4" Encoding="ASCII" Endian="LittleEndian">
      |<Data>1 2 3 4</Data>
      |</DataArray>
      |</GIFTI>
      |""".stripMargin.getBytes("UTF-8")

  /** Pushes the reference bundle with `substitute` replacing one asset's bytes; the manifest's
    * digest and size for that asset are rewritten to the substitute's, so the push is well formed
    * and only ingestion can object. `edit` rewrites the manifest itself, for a bundle that admits
    * cleanly and only the asset bytes can contradict.
    */
  private def push(
      app: HttpApp[IO],
      substitute: Option[(String, Array[Byte])] = None,
      edit: Option[Json => Json] = None
  ): IO[org.http4s.Response[IO]] =
    for
      manifest0 <- bytes("reference/manifest.json")
      assets0 <- ReferenceBundle.assets.traverse((id, file, mt) =>
        bytes(s"reference/assets/$file").map(b => (id, b, mt))
      )
      assets =
        assets0.map((id, b, mt) => (id, substitute.filter(_._1 == id).map(_._2).getOrElse(b), mt))
      manifest =
        if substitute.isEmpty && edit.isEmpty then manifest0
        else
          val json = io.circe.parser.parse(new String(manifest0, "UTF-8")).toOption.get
          val withAssets = substitute.fold(json) { (sid, sb) =>
            json.hcursor.downField("assets").withFocus(_.mapArray(_.map(a =>
              if a.hcursor.get[String]("id").toOption.contains(sid) then
                a.mapObject(
                  _.add("digest", Json.fromString(Sha256.of(sb).render))
                    .add("size", Json.fromInt(sb.length))
                )
              else a
            ))).top.get
          }
          edit.fold(withAssets)(_(withAssets)).spaces2.getBytes("UTF-8")
      inv = assets.map((_, b, mt) => AssetInventory(Sha256.of(b).render, b.length.toLong, mt))
      s <- app.run(auth(Request[IO](
        Method.POST,
        uri"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions"
      ).withEntity(CreateUploadSession(
        Sha256.of(manifest).render,
        manifest.length.toLong,
        None,
        inv
      ).asJson))).flatMap(decode[UploadSessionCreated])
      _ <- assets.traverse_((_, b, _) =>
        app.run(auth(Request[IO](
          Method.PUT,
          Uri.unsafeFromString(
            s"/api/v1/upload-sessions/${s.sessionId}/objects/${Sha256.of(b).render}"
          )
        ).withEntity(b))).map(r => assertEquals(r.status, Status.NoContent))
      )
      _ <- app.run(auth(Request[IO](
        Method.PUT,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/manifest")
      ).withEntity(manifest)))
      c <- app.run(auth(Request[IO](
        Method.POST,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/commit")
      ).withEntity(CommitRequest(None).asJson)))
    yield c

  inline.test("surface and vertex-field renditions are derived inline and decode on the server") {
    e =>
      for
        c <- push(e.app)
        _ = assertEquals(c.status, Status.Created)
        r <- decode[CommitResult](c)
        d <- get(e.app, s"/api/v1/revisions/${r.revisionId}").flatMap(decode[RevisionDetail])
        _ = assertEquals(d.renditions.map(_.status).distinct, List("ready"))
        _ = assertEquals(
          d.renditions.map(x => (x.assetId, x.kind, x.surface)).drop(5),
          List(
            ("lh-pial", "surface-mesh", None),
            ("rh-pial", "surface-mesh", None),
            ("speech-t-lh", "vertex-field", Some("lh-pial-surface")),
            ("speech-t-rh", "vertex-field", Some("rh-pial-surface")),
            ("speech-z-lh", "vertex-field", Some("lh-pial-surface")),
            ("speech-z-rh", "vertex-field", Some("rh-pial-surface"))
          )
        )
        mh <- get(e.app, s"/api/v1/revisions/${r.revisionId}/renditions/lh-pial/header")
          .flatMap(_.as[String])
        mp <- get(e.app, s"/api/v1/revisions/${r.revisionId}/renditions/lh-pial/payload")
          .flatMap(_.body.compile.to(Array))
        fh <- get(e.app, s"/api/v1/revisions/${r.revisionId}/renditions/speech-t-lh/header")
          .flatMap(_.as[String])
        fp <- get(e.app, s"/api/v1/revisions/${r.revisionId}/renditions/speech-t-lh/payload")
          .flatMap(_.body.compile.to(Array))
        space <- domainSpace("ico3-lh")
      yield
        val header = SurfaceRendition.decodeHeader(mh).fold(fail(_), identity)
        assertEquals(
          (header.hemisphere, header.kind, header.vertexCount, header.faceCount),
          ("left", "pial", 642, 1280)
        )
        // the space comes from the surface's domain, so a reader can refuse to link a surface
        // with a volume of another space
        assertEquals(header.space, space)
        // the GIFTI's transform was applied to the positions and kept as provenance; the header's
        // surfaceToWorld is the identity, so nothing applies it a second time
        assertEquals(header.surfaceToWorld, SurfaceRendition.Identity)
        assertEquals(
          header.sourceTransform.flatMap(_.transformedSpace),
          Some("NIFTI_XFORM_SCANNER_ANAT")
        )
        assertEquals(header.anatomicalStructurePrimary, Some("CortexLeft"))
        assert(header.faceDigest.exists(_.startsWith("sha256:")), header.faceDigest)
        val g = SurfaceRendition.decode(header, mp).fold(fail(_), identity)
        assertEquals(g.vertexCount, 642)
        val fheader = VertexFieldRendition.decodeHeader(fh).fold(fail(_), identity)
        assertEquals(fheader.surface, "lh-pial-surface")
        val f = VertexFieldRendition.decode(fheader, fp, g).fold(fail(_), identity)
        assertEquals(f.size, 642)
        // the rendition's source is the canonical asset's digest
        assert(header.source.exists(_.startsWith("sha256:")), header.source)
  }

  inline.test("a surface whose triangles do not realize its domain's key fails the push") { e =>
    for
      original <- bytes("reference/assets/lh-pial.surf.gii")
      c <- push(e.app, Some("lh-pial" -> permutedSurface(original)))
      _ = assertEquals(c.status, Status.BadRequest)
      err <- decode[ApiError](c)
    yield
      assert(err.message.contains("surface lh-pial"), err.message)
      assert(err.message.contains("triangles hash to sha256:"), err.message)
      assert(err.message.contains("ico3-lh"), err.message)
  }

  inline.test("a surface declared as the hemisphere its GIFTI denies fails the push") { e =>
    // the reference `lh-pial` GIFTI says AnatomicalStructurePrimary=CortexLeft. Declaring it the
    // right hemisphere admits cleanly — the manifest is consistent with itself — and only the
    // bytes contradict it; left and right meshes of one template share counts and topology, so
    // nothing further downstream would notice (review S6).
    def rightward(j: Json): Json =
      val surfaces = j.hcursor.downField("surfaces").withFocus(_.mapArray(_.map(s =>
        if s.hcursor.get[String]("id").toOption.contains("lh-pial-surface") then
          s.mapObject(_.add("hemisphere", Json.fromString("right")))
        else s
      ))).top.get
      val domains = surfaces.hcursor.downField("domains").withFocus(_.mapArray(_.map(d =>
        if d.hcursor.get[String]("id").toOption.contains("ico3-lh") then
          d.hcursor.downField("descriptor").downField("payload")
            .withFocus(_.mapObject(_.add("hemisphere", Json.fromString("right")))).top.get
        else d
      ))).top.get
      domains.hcursor.downField("resultFields").withFocus(_.mapArray(_.map(f =>
        f.hcursor.downField("representations").withFocus(_.mapArray(_.map(r =>
          if r.hcursor.get[String]("surface").toOption.contains("lh-pial-surface") then
            r.mapObject(_.add("hemisphere", Json.fromString("right")))
          else r
        ))).top.getOrElse(f)
      ))).top.get
    for
      c <- push(e.app, edit = Some(rightward))
      _ = assertEquals(c.status, Status.BadRequest)
      err <- decode[ApiError](c)
    yield
      assert(err.message.contains("asset lh-pial (surface lh-pial-surface)"), err.message)
      assert(err.message.contains("declared the right hemisphere"), err.message)
      assert(err.message.contains("AnatomicalStructurePrimary=CortexLeft"), err.message)
  }

  inline.test("a vertex field whose length is not its surface's vertex count fails the push") {
    e =>
      for
        c <- push(e.app, Some("speech-t-lh" -> shortField))
        _ = assertEquals(c.status, Status.BadRequest)
        err <- decode[ApiError](c)
      yield
        assert(err.message.contains("asset speech-t-lh has 4 vertex values"), err.message)
        assert(err.message.contains("surface lh-pial-surface has 642 vertices"), err.message)
  }
