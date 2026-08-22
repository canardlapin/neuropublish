package neuropublish.backend

import cats.effect.IO
import fs2.io.file.{Files, Path}
import neuropublish.protocol.Sha256

/** Content-addressed immutable object storage. Stage 2 adds an S3 implementation. */
trait ObjectStore:
  /** Store bytes under their digest; rejects bytes whose digest differs from `expected`. */
  def put(expected: Sha256, bytes: Array[Byte]): IO[Either[String, Unit]]
  def exists(digest: Sha256): IO[Boolean]
  def size(digest: Sha256): IO[Option[Long]]
  def get(digest: Sha256): IO[Option[Array[Byte]]]

object ObjectStore:
  /** `<root>/sha256/<2 hex>/<64 hex>` — the normalized bundle layout. */
  final class LocalFs(root: Path) extends ObjectStore:
    private def path(d: Sha256) = root / "sha256" / d.hex.take(2) / d.hex
    def put(expected: Sha256, bytes: Array[Byte]): IO[Either[String, Unit]] =
      val actual = Sha256.of(bytes)
      if actual.hex != expected.hex then
        IO.pure(Left(s"digest mismatch: declared ${expected.render}, received ${actual.render}"))
      else
        val p = path(expected)
        Files[IO].createDirectories(p.parent.get) *>
          Files[IO].exists(p).flatMap {
            case true => IO.pure(Right(()))
            case false =>
              val tmp = p.parent.get /
                s"${p.fileName}.${java.util.UUID.randomUUID().toString.take(8)}.part"
              (fs2.Stream.emits(bytes).through(Files[IO].writeAll(tmp)).compile.drain *>
                Files[IO].exists(p).flatMap {
                  case true =>
                    Files[IO].delete(tmp) // a concurrent put won; content is identical by digest
                  case false => Files[IO].move(tmp, p)
                }).as(Right(())).handleErrorWith(_ =>
                Files[IO].deleteIfExists(tmp) *>
                  Files[IO].exists(p).map(if _ then Right(()) else Left("write failed"))
              )
          }
    def exists(digest: Sha256): IO[Boolean] = Files[IO].exists(path(digest))
    def size(digest: Sha256): IO[Option[Long]] =
      Files[IO].exists(path(digest)).flatMap(e =>
        if e then Files[IO].size(path(digest)).map(Some(_)) else IO.none
      )
    def get(digest: Sha256): IO[Option[Array[Byte]]] =
      Files[IO].exists(path(digest)).flatMap {
        case false => IO.none
        case true => Files[IO].readAll(path(digest)).compile.to(Array).map(Some(_))
      }
