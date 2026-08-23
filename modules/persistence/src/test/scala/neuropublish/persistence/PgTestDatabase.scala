package neuropublish.persistence

import cats.effect.{IO, Resource}
import munit.Assertions.{assume, fail}
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** One Testcontainers PostgreSQL per JVM; a fresh, migrated database per test. Without Docker every
  * caller skips via `assume`, so the suites report "skipped", never "failed" — unless
  * `NP_TEST_REQUIRE_DOCKER=1` or `CI=true` is set, in which case a missing daemon is a failure (so
  * a CI run can never pass by silently skipping the database and object-store suites).
  */
object PgTestDatabase:
  val SkipMessage = "Docker is not available: PostgreSQL-backed tests skipped"

  /** `-Dnp.test.noDocker=1` forces the skip path (to verify it where Docker is running). */
  lazy val dockerAvailable: Boolean =
    !sys.props.contains("np.test.noDocker") &&
      (try DockerClientFactory.instance().isDockerAvailable
      catch case _: Throwable => false)

  /** Docker-backed suites must run, not skip. */
  lazy val dockerRequired: Boolean =
    sys.env.get("NP_TEST_REQUIRE_DOCKER").exists(v => v == "1" || v == "true") ||
      sys.env.get("CI").exists(_ == "true")

  /** Skip (or fail, when required) unless Docker is reachable. */
  def requireDocker(what: String): Unit =
    if !dockerAvailable then
      if dockerRequired then
        fail(s"Docker is required (NP_TEST_REQUIRE_DOCKER/CI) but unavailable: $what")
      else assume(false, s"Docker is not available: $what skipped")

  private lazy val container: PostgreSQLContainer[Nothing] =
    val c = new PostgreSQLContainer[Nothing]("postgres:16-alpine")
    c.start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => c.stop()))
    c

  private def adminConfig: DbConfig =
    DbConfig(container.getJdbcUrl, container.getUsername, container.getPassword, poolSize = 2)

  private def createDatabase: IO[DbConfig] = IO.blocking {
    val admin = adminConfig
    val name = "np_" + java.util.UUID.randomUUID().toString.replace("-", "").take(12)
    // the driver directly: under sbt's test classloader DriverManager may not see it yet
    val props = new java.util.Properties()
    props.setProperty("user", admin.user)
    props.setProperty("password", admin.password)
    val conn = new org.postgresql.Driver().connect(admin.url, props)
    try conn.createStatement().execute(s"CREATE DATABASE $name")
    finally conn.close()
    admin.copy(
      url = s"jdbc:postgresql://${container.getHost}:${container.getMappedPort(5432)}/$name",
      poolSize = 4
    )
  }

  /** Skips the calling test without Docker; otherwise a migrated, empty database. */
  def fresh: Resource[IO, PgStores] =
    Resource.eval(IO(requireDocker("PostgreSQL-backed tests")) *> createDatabase)
      .flatMap(PgStores.resource(_))
