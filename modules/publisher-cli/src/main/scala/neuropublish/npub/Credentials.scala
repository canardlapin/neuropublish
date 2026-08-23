package neuropublish.npub

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.{Files, Path, PosixPermissions}
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.syntax.*

/** One signed-in server: a user token from `npub login` and the email it belongs to. */
final case class ServerEntry(token: String, user: String)

/** Layout of `credentials.json`: `{"servers": {"<server url>": {"token": ..., "user": ...}}}`. */
final case class CredentialsFile(servers: Map[String, ServerEntry] = Map.empty):
  def get(server: String): Option[ServerEntry] = servers.get(Credentials.key(server))
  def put(server: String, e: ServerEntry): CredentialsFile =
    copy(servers = servers.updated(Credentials.key(server), e))
  def remove(server: String): CredentialsFile =
    copy(servers = servers.removed(Credentials.key(server)))

/** The credentials file lives at `$NPUB_CONFIG_DIR/credentials.json`, or
  * `~/.config/npub/credentials.json` when the variable is unset. It is created with mode 0600 (the
  * mode is an attribute of the create, never a chmod after the bytes are on disk) and its token is
  * never printed.
  */
object Credentials:
  given Codec[ServerEntry] = deriveCodec
  given Codec[CredentialsFile] = deriveCodec

  val fileName = "credentials.json"

  /** Servers are keyed by normalized origin plus path: scheme and host lower-cased, the scheme's
    * default port made explicit, no trailing slash — so `HTTP://H:80/`, `http://h/` and
    * `http://h:80` all name one entry. A string that is not a URL is keyed as written (trimmed,
    * without a trailing slash).
    */
  def key(server: String): String =
    val trimmed = server.trim.stripSuffix("/")
    org.http4s.Uri.fromString(trimmed).toOption.flatMap(u => Origin.of(u).map(o => (o, u))) match
      case Some((o, u)) =>
        val path = u.path.renderString.stripSuffix("/")
        o.render + (if path.isEmpty || path == "/" then "" else path)
      case None => trimmed

  def configDir(env: Map[String, String] = sys.env): Path =
    env.get("NPUB_CONFIG_DIR").filter(_.nonEmpty).map(Path(_)).getOrElse(
      Path(env.getOrElse("HOME", System.getProperty("user.home"))) / ".config" / "npub"
    )

  def file(dir: Path): Path = dir / fileName

  def load(dir: Path): IO[CredentialsFile] =
    val f = file(dir)
    Files[IO].exists(f).flatMap {
      case false => IO.pure(CredentialsFile())
      case true =>
        Files[IO].readUtf8(f).compile.string.flatMap { s =>
          IO.fromEither(io.circe.parser.decode[CredentialsFile](s).leftMap(e =>
            CliError(s"cannot read $f: ${e.getMessage}")
          ))
        }
    }

  /** Temp file created `rw-------` in one step, written, then renamed over the target (the rename
    * keeps the mode). On a file system without POSIX permissions the create falls back to the
    * platform default.
    */
  def save(dir: Path, c: CredentialsFile): IO[Unit] =
    val f = file(dir)
    val tmp = dir / s".$fileName.tmp"
    val ownerRw = PosixPermissions.fromString("rw-------").get
    val ownerDir = PosixPermissions.fromString("rwx------").get
    def posixOr(withPerms: IO[Unit], plain: IO[Unit]): IO[Unit] =
      withPerms.recoverWith { case _: UnsupportedOperationException => plain }
    for
      _ <-
        posixOr(Files[IO].createDirectories(dir, Some(ownerDir)), Files[IO].createDirectories(dir))
      _ <- Files[IO].deleteIfExists(tmp)
      _ <- posixOr(Files[IO].createFile(tmp, Some(ownerRw)), Files[IO].createFile(tmp))
      _ <- fs2.Stream.emit(c.asJson.spaces2).through(Files[IO].writeUtf8(tmp)).compile.drain
      _ <- Files[IO].move(tmp, f, fs2.io.file.CopyFlags(fs2.io.file.CopyFlag.ReplaceExisting))
    yield ()

  def update(dir: Path)(f: CredentialsFile => CredentialsFile): IO[Unit] =
    load(dir).map(f).flatMap(save(dir, _))

final case class CliError(message: String) extends RuntimeException(message)

/** Where a bearer token for `push`/`credential`/`whoami` comes from, in order: `--token`
  * (discouraged: it lands in shell history), `NP_TOKEN` (batch jobs with a project credential),
  * then the credentials-file entry for `--server`.
  */
enum TokenSource:
  case Flag, Env, File

object TokenSource:
  final case class Resolved(token: String, source: TokenSource)

  def resolve(
      flag: Option[String],
      env: Option[String],
      stored: Option[ServerEntry]
  ): Either[String, Resolved] =
    flag.filter(_.nonEmpty).map(Resolved(_, TokenSource.Flag))
      .orElse(env.filter(_.nonEmpty).map(Resolved(_, TokenSource.Env)))
      .orElse(stored.map(e => Resolved(e.token, TokenSource.File)))
      .toRight("not signed in; run `npub login`")
