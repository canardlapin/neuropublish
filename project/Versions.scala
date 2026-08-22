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
  val decline = "2.5.0"
  val laminar = "17.2.1"
  val waypoint = "9.0.0"

  val munit = "1.3.0"
  val munitScalacheck = "1.1.0"
  val munitCatsEffect = "2.1.0"
  val scalacheck = "1.19.0"

  // Upstream Scala imaging libraries are consumed as exact git revisions
  // (see docs/architecture.md, "Scala library dogfooding"). Override locally
  // with -Dneuropublish.scalafim.build=/path/to/checkout.
  val scalafimRevision = "97c7ff16ff24739a147fd43d7a5477d1024071ea"
}
