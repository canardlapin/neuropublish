package neuropublish.backend

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.syntax.*
import munit.CatsEffectSuite
import neuropublish.api.*
import neuropublish.api.Protocol.given
import neuropublish.protocol.Sha256
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import software.amazon.awssdk.core.exception.SdkClientException

/** Unhandled exceptions become typed `ApiError`s: a vanished file is 404, a transient object-store
  * failure 503 `unavailable`, tampered bytes 503 `integrity`, anything else 500 `internal` — and
  * every API response is `Cache-Control: no-store`.
  */
class ExceptionMappingSuite extends CatsEffectSuite:
  private val fixtures = List("modules/conformance/fixtures", "../conformance/fixtures")
    .map(Path(_)).find(p => java.nio.file.Files.isDirectory(p.toNioPath)).get
  private val key = ProjectKey("rotman", "sherlock")
  private val token = "t"

  /** Delegates to the local store; `failWith` makes every `get` raise instead. */
  final class Flaky(inner: ObjectStore, failWith: Ref[IO, Option[Throwable]]) extends ObjectStore:
    private def gate[A](io: IO[A]): IO[A] = failWith.get.flatMap(_.fold(io)(IO.raiseError))
    def put(expected: Sha256, bytes: Array[Byte]) = inner.put(expected, bytes)
    def exists(digest: Sha256) = inner.exists(digest)
    def stat(digest: Sha256) = inner.stat(digest)
    def get(digest: Sha256) = gate(inner.get(digest))
    def getToFile(digest: Sha256, target: Path) = gate(inner.getToFile(digest, target))
    def delete(digest: Sha256) = inner.delete(digest)
    def list = inner.list
    def putBlob(key: String, bytes: Array[Byte], mediaType: String) =
      inner.putBlob(key, bytes, mediaType)
    def getBlob(key: String) = inner.getBlob(key)
    def statBlob(key: String) = inner.statBlob(key)
    def deleteBlob(key: String) = inner.deleteBlob(key)
    def listBlobs(prefix: String) = inner.listBlobs(prefix)

  final case class Env(app: HttpApp[IO], failWith: Ref[IO, Option[Throwable]], data: Path)
  private val env = ResourceFunFixture(
    Files[IO].tempDirectory.evalMap { dir =>
      for
        ref <- Ref[IO].of(Option.empty[Throwable])
        st = Server.localStorage(dir)
        flaky = Flaky(st.objects, ref)
        routes <- Server.build(
          dir,
          key,
          "http://test",
          legacyToken = Some(token),
          storage = Some(st.copy(objects = flaky))
        )
      yield Env(routes.orNotFound, ref, dir)
    }
  )
  private def auth(r: Request[IO]) =
    r.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  private def bytes(p: String) = Files[IO].readAll(fixtures / p).compile.to(Array)
  private def decode[A: io.circe.Decoder](r: org.http4s.Response[IO]): IO[A] =
    r.as[String].flatMap(b => IO.fromEither(io.circe.parser.decode[A](b)))

  private def push(app: HttpApp[IO]): IO[CommitResult] =
    for
      manifest <- bytes("reference/manifest.json")
      assets <- List("t1", "speech-effect", "speech-se", "speech-t", "speech-z").traverse(id =>
        bytes(s"reference/assets/$id.nii")
      )
      inv =
        assets.map(b => AssetInventory(Sha256.of(b).render, b.length.toLong, "application/x-nifti"))
      s <- app.run(auth(Request[IO](
        Method.POST,
        uri"/api/v1/workspaces/rotman/projects/sherlock/upload-sessions"
      ).withEntity(CreateUploadSession(
        Sha256.of(manifest).render,
        manifest.length.toLong,
        None,
        inv
      ).asJson))).flatMap(decode[UploadSessionCreated])
      _ <- assets.traverse_(b =>
        app.run(auth(Request[IO](
          Method.PUT,
          Uri.unsafeFromString(
            s"/api/v1/upload-sessions/${s.sessionId}/objects/${Sha256.of(b).render}"
          )
        ).withEntity(b)))
      )
      _ <- app.run(auth(Request[IO](
        Method.PUT,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/manifest")
      ).withEntity(manifest)))
      c <- app.run(auth(Request[IO](
        Method.POST,
        Uri.unsafeFromString(s"/api/v1/upload-sessions/${s.sessionId}/commit")
      ).withEntity(CommitRequest(None).asJson)))
      _ = assertEquals(c.status, Status.Created)
      r <- decode[CommitResult](c)
    yield r

  private def revision(e: Env, id: String) =
    e.app.run(auth(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/revisions/$id"))))

  env.test("object-store failures map to 503/404/500 ApiErrors; responses are no-store") { e =>
    for
      r <- push(e.app)
      ok <- revision(e, r.revisionId)
      _ = assertEquals(ok.status, Status.Ok)
      _ = assertEquals(
        ok.headers.get(org.typelevel.ci.CIString("Cache-Control")).map(_.head.value),
        Some("no-store")
      )
      _ <- e.failWith.set(Some(SdkClientException.create("connection reset by peer")))
      down <- revision(e, r.revisionId)
      _ = assertEquals(down.status, Status.ServiceUnavailable)
      _ <- decode[ApiError](down).map(a => assertEquals(a.code, "unavailable"))
      _ <- e.failWith.set(Some(new java.nio.file.NoSuchFileException("/gone")))
      gone <- revision(e, r.revisionId)
      _ = assertEquals(gone.status, Status.NotFound)
      _ <- decode[ApiError](gone).map(a => assertEquals(a.code, "not_found"))
      _ <- e.failWith.set(Some(new IllegalStateException("bug")))
      bug <- revision(e, r.revisionId)
      _ = assertEquals(bug.status, Status.InternalServerError)
      _ <- decode[ApiError](bug).map(a => assertEquals(a.code, "internal"))
      _ <- e.failWith.set(None)
      // tampered manifest bytes: the record's digest no longer matches what is stored
      md = Sha256.unsafe(r.digest.stripPrefix("sha256:"))
      _ <- JsonFiles.writeBytes(
        e.data / "objects" / "sha256" / md.hex.take(2) / md.hex,
        "{\"tampered\":true}".getBytes("UTF-8")
      )
      bad <- revision(e, r.revisionId)
      _ = assertEquals(bad.status, Status.ServiceUnavailable)
      err <- decode[ApiError](bad)
    yield assertEquals(err.code, "integrity")
  }
