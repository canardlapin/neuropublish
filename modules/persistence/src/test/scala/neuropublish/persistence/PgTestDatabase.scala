package neuropublish.persistence

import cats.effect.{IO, Resource}
import munit.Assertions.assume
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** One Testcontainers PostgreSQL per JVM; a fresh, migrated database per test. Without Docker every
  * caller skips via `assume`, so the suites report "skipped", never "failed".
  */
object PgTestDatabase:
  val SkipMessage = "Docker is not available: PostgreSQL-backed tests skipped"

  /** `-Dnp.test.noDocker=1` forces the skip path (to verify it where Docker is running). */
  lazy val dockerAvailable: Boolean =
    !sys.props.contains("np.test.noDocker") &&
      (try DockerClientFactory.instance().isDockerAvailable
      catch case _: Throwable => false)

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
    Resource.eval(IO(assume(dockerAvailable, SkipMessage)) *> createDatabase)
      .flatMap(PgStores.resource(_))
