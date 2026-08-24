package neuropublish.backend

import cats.effect.IO
import fs2.io.file.Files
import munit.CatsEffectSuite

class IdentityRotationSuite extends CatsEffectSuite:
  test("local-fs password rotation replaces the hash and all principals can be revoked"):
    Files[IO].tempDirectory.use { dir =>
      for
        identity <- LocalIdentity(dir)
        user <- identity.ensureLocalUser("owner@example.org", "owner", "old-password")
        sessions = LocalSessions(dir / "sessions")
        tokens = LocalUserTokens(dir / "tokens")
        (session, _) <- sessions.create(user.id)
        token <- tokens.mint(user.id, "npub")
        changed <- identity.changeLocalPassword("OWNER@example.org", "new-password")
        old <- identity.authenticate("owner@example.org", "old-password")
        fresh <- identity.authenticate("owner@example.org", "new-password")
        sessionCount <- sessions.revokeAll(user.id)
        tokenCount <- tokens.revokeAll(user.id)
        sessionAfter <- sessions.resolve(session)
        tokenAfter <- tokens.resolve(token)
      yield
        assertEquals(changed.map(_.id), Some(user.id))
        assertEquals(old, None)
        assertEquals(fresh.map(_.id), Some(user.id))
        assertEquals((sessionCount, tokenCount), (1, 1))
        assertEquals((sessionAfter, tokenAfter), (None, None))
    }
