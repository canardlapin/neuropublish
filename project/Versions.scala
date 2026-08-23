object Versions {
  val scala3 = "3.7.4"

  val cats = "2.13.0"
  val catsEffect = "3.7.0"
  val fs2 = "3.13.0"
  val circe = "0.14.16"
  val http4s = "0.23.36"
  val tapir = "1.11.25"
  val apispec = "0.11.9"
  val doobie = "1.0.0-RC9"
  val flyway = "11.8.2"
  val testcontainers = "0.43.0"
  val decline = "2.5.0"
  val awsSdk = "2.46.7"
  val laminar = "17.2.1"
  val waypoint = "9.0.0"
  val jsonSchemaValidator = "1.5.9"
  val slf4j = "2.0.17"

  val munit = "1.3.0"
  val munitScalacheck = "1.1.0"
  val munitCatsEffect = "2.1.0"
  val scalacheck = "1.19.0"

  // Pin = branch neuropublish/lifecycle-dispose: ScalaFIM main 6b5d499 (NArray-backed volumes) +
  // controller dispose, forced WebGL context loss, threshold scene codec (Intaglio cdf1562).
  // Upstream Scala imaging libraries are consumed as exact git revisions
  // (see docs/architecture.md, "Scala library dogfooding"). Override locally
  // with -Dneuropublish.scalafim.build=/path/to/checkout.
  // ScalaFIM's build is loaded into this sbt session, so its sbt-scalajs and
  // sbt-scalajs-crossproject plugin versions must match project/plugins.sbt
  // here (1.22.0 / 1.3.2 at this revision) or Scala.js IR linking fails
  // opaquely. Re-check both when bumping the pin.
  val scalafimRevision = "ac545e651f5c92bf5113b84945aa74f4a000abe8"
}
