package neuropublish.backend

import cats.effect.{IO, Resource}
import fs2.io.file.{Files, Path}
import java.time.Instant
import org.http4s.HttpApp

/** A built server plus the store-specific peeks the suites need: what is persisted (for the
  * secrets-at-rest check) and a user token's expiry (to age one past its lifetime).
  */
trait TestServer:
  def app: HttpApp[IO]
  def storedText: IO[String]
  def tokenExpiry(secret: String): IO[Option[Instant]]
  def setTokenExpiry(secret: String, at: Instant): IO[Unit]

/** Builds [[Server]] over one kind of record store; the suites are parameterized over it. */
trait ServerFactory:
  def build(
      bootstrap: ProjectKey,
      baseUrl: String,
      ownerEmail: String = Server.DefaultOwnerEmail,
      ownerPassword: String = Server.DefaultOwnerPassword,
      legacyToken: Option[String] = None,
      extra: List[Server.Bootstrap] = Nil
  ): Resource[IO, TestServer]

object ServerFactory:
  /** The local-fs JSON stores in a temp data dir — the default deployment. */
  object Local extends ServerFactory:
    def build(
        bootstrap: ProjectKey,
        baseUrl: String,
        ownerEmail: String,
        ownerPassword: String,
        legacyToken: Option[String],
        extra: List[Server.Bootstrap]
    ): Resource[IO, TestServer] =
      Files[IO].tempDirectory.evalMap { dir =>
        Server.build(dir, bootstrap, baseUrl, ownerEmail, ownerPassword, legacyToken, extra).map {
          routes =>
            new TestServer:
              val app = routes.orNotFound
              private def tokenFile(secret: String) =
                dir / "tokens" / s"${Secrets.sha256Hex(secret)}.json"
              def storedText: IO[String] =
                Files[IO].walk(dir).filter(p =>
                  p.toString.endsWith(".json") || p.toString.endsWith(".jsonl")
                ).evalMap(p => Files[IO].readUtf8(p).compile.string).compile.toList.map(
                  _.mkString("\n")
                )
              def tokenExpiry(secret: String): IO[Option[Instant]] =
                JsonFiles.read[UserTokenRecord](tokenFile(secret))
                  .map(_.flatMap(_.expiresAt).map(Instant.parse))
              def setTokenExpiry(secret: String, at: Instant): IO[Unit] =
                JsonFiles.read[UserTokenRecord](tokenFile(secret)).flatMap {
                  case None => IO.raiseError(new java.util.NoSuchElementException("no such token"))
                  case Some(r) =>
                    JsonFiles.write(tokenFile(secret), r.copy(expiresAt = Some(at.toString)))
                }
        }
      }
