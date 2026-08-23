package neuropublish.npub

import cats.effect.IO
import fs2.io.file.{Files, Path, PosixPermissions}
import munit.CatsEffectSuite

class CredentialsSuite extends CatsEffectSuite:
  private val tmp = ResourceFunFixture(Files[IO].tempDirectory)

  tmp.test("round trip and 0600 mode") { dir =>
    val entry = ServerEntry("tok-123", "ana@example.org")
    for
      _ <- Credentials.save(dir, CredentialsFile().put("http://h:1/", entry))
      loaded <- Credentials.load(dir)
      perms <- Files[IO].getPosixPermissions(Credentials.file(dir))
      raw <- Files[IO].readUtf8(Credentials.file(dir)).compile.string
    yield
      assertEquals(loaded.get("http://h:1"), Some(entry))
      assertEquals(loaded.get("http://h:1/"), Some(entry))
      assertEquals(perms, PosixPermissions.fromString("rw-------").get)
      assertEquals(
        io.circe.parser.parse(raw).flatMap(_.hcursor.downField("servers").downField("http://h:1")
          .downField("user").as[String]),
        Right("ana@example.org")
      )
  }

  tmp.test("missing file loads empty; remove drops the entry") { dir =>
    for
      empty <- Credentials.load(dir)
      _ <- Credentials.save(dir, CredentialsFile().put("http://a", ServerEntry("t", "u")))
      _ <- Credentials.update(dir)(_.remove("http://a/"))
      after <- Credentials.load(dir)
    yield
      assertEquals(empty, CredentialsFile())
      assertEquals(after.servers, Map.empty[String, ServerEntry])
  }

  test("config dir honours NPUB_CONFIG_DIR, else ~/.config/npub") {
    assertEquals(Credentials.configDir(Map("NPUB_CONFIG_DIR" -> "/x/y")), Path("/x/y"))
    assertEquals(
      Credentials.configDir(Map("HOME" -> "/home/me")),
      Path("/home/me") / ".config" / "npub"
    )
  }

  test("token resolution: flag > env > file > not signed in") {
    val stored = Some(ServerEntry("file-tok", "u"))
    assertEquals(
      TokenSource.resolve(Some("flag"), Some("env"), stored).map(_.source),
      Right(TokenSource.Flag)
    )
    assertEquals(
      TokenSource.resolve(None, Some("env"), stored),
      Right(TokenSource.Resolved("env", TokenSource.Env))
    )
    assertEquals(
      TokenSource.resolve(None, None, stored),
      Right(TokenSource.Resolved("file-tok", TokenSource.File))
    )
    assertEquals(TokenSource.resolve(None, Some(""), None), Left("not signed in; run `npub login`"))
  }
