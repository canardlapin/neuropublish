import org.scalajs.linker.interface.{ModuleKind, ModuleSplitStyle}
import org.typelevel.sbt.gha.JavaSpec

// ---------------------------------------------------------------------------
// Neuropublish — scientific results publication and review for neuroimaging.
// Module map: docs/architecture.md ("Scala 3 repository structure").
// ---------------------------------------------------------------------------

ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "io.github.canardlapin"
ThisBuild / organizationName := "Bradley Buchsbaum"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(tlGitHubDev("canardlapin", "Bradley Buchsbaum"))

ThisBuild / scalaVersion := Versions.scala3
ThisBuild / crossScalaVersions := Seq(Versions.scala3)
ThisBuild / tlJdkRelease := Some(21)
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21"))
ThisBuild / tlCiHeaderCheck := false
ThisBuild / tlCiDocCheck := false
ThisBuild / tlFatalWarnings := false
ThisBuild / githubWorkflowBuild := Seq(WorkflowStep.Sbt(List("npCheck"), name = Some("Check")))
ThisBuild / githubWorkflowBuildPreamble := Seq(
  WorkflowStep.Use(
    UseRef.Public("actions", "setup-node", "v4"),
    params = Map("node-version" -> "22")
  )
)

// Pre-release: no publication until Stage 6 decides a release channel.
ThisBuild / tlCiReleaseBranches := Seq()
ThisBuild / githubWorkflowPublishTargetBranches := Seq()

// ---------------------------------------------------------------------------
// Upstream Scala imaging libraries as exact git-revision source pins.
// `-Dneuropublish.scalafim.build=/local/checkout` is the explicit override.
// ---------------------------------------------------------------------------
lazy val scalafimBuild =
  sys.props
    .get("neuropublish.scalafim.build")
    .map(p => file(p).getCanonicalFile.toURI)
    .getOrElse(uri(s"https://github.com/canardlapin/scalafim.git#${Versions.scalafimRevision}"))

lazy val scalafimImageJVM = ProjectRef(scalafimBuild, "imageJVM")
lazy val scalafimImageJS = ProjectRef(scalafimBuild, "imageJS")
lazy val scalafimImageViewJVM = ProjectRef(scalafimBuild, "imageViewJVM")
lazy val scalafimImageViewJS = ProjectRef(scalafimBuild, "imageViewJS")
lazy val scalafimImageViewCanvasJS = ProjectRef(scalafimBuild, "imageViewCanvasJS")
lazy val scalafimSurfaceViewJVM = ProjectRef(scalafimBuild, "surfaceViewJVM")
lazy val scalafimSurfaceViewJS = ProjectRef(scalafimBuild, "surfaceViewJS")
lazy val scalafimSurfaceViewThreeJS = ProjectRef(scalafimBuild, "surfaceViewThreeJS")

// ---------------------------------------------------------------------------
// Shared settings
// ---------------------------------------------------------------------------
lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-Xmax-inlines:64", "-Wunused:imports"),
  Test / fork := false,
  Test / parallelExecution := false,
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % Versions.munit % Test,
    "org.scalameta" %%% "munit-scalacheck" % Versions.munitScalacheck % Test,
    "org.scalacheck" %%% "scalacheck" % Versions.scalacheck % Test
  )
)

lazy val jsSettings = Seq(
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
  scalaJSUseTestModuleInitializer := true
)

// Settings shared by every cross JVM/JS module (crossProject itself is a macro
// that must be assigned directly to a val, so it is repeated per module).
def crossSettings(id: String) = commonSettings ++ Seq(name := s"neuropublish-$id")

// ---------------------------------------------------------------------------
// Cross JVM/JS modules
// ---------------------------------------------------------------------------

// Wire-independent core: semantic ids, digests, result model primitives.
lazy val protocolCore = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .withoutSuffixFor(JVMPlatform)
  .in(file("modules/protocol-core"))
  .settings(crossSettings("protocol-core"))
  .jsSettings(jsSettings)
  .settings(libraryDependencies += "org.typelevel" %%% "cats-core" % Versions.cats)

// JSON codecs, byte-profile admission, manifest digest, semantic checks, migrations.
// JVM only: JSON Schema 2020-12 validation against `protocol/schemas/` (served from the
// classpath as `schemas/*.schema.json`, one source of truth). JS keeps the decoder path.
lazy val protocolJson = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("modules/protocol-json"))
  .settings(crossSettings("protocol-json"))
  .jsSettings(jsSettings)
  .dependsOn(protocolCore)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % Versions.circe,
      "io.circe" %%% "circe-parser" % Versions.circe
    )
  )
  .jvmSettings(
    Compile / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "protocol",
    libraryDependencies ++= Seq(
      "com.networknt" % "json-schema-validator" % Versions.jsonSchemaValidator
    )
  )

// Pure workspace model: layers, selection, layout presets, saved views.
// No application dependencies (docs/architecture.md, "Viewer modularity").
lazy val viewerState = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .withoutSuffixFor(JVMPlatform)
  .in(file("modules/viewer-state"))
  .settings(crossSettings("viewer-state"))
  .jsSettings(jsSettings)
  .dependsOn(protocolCore)
  .jvmConfigure(_.dependsOn(scalafimImageViewJVM, scalafimSurfaceViewJVM))
  .jsConfigure(_.dependsOn(scalafimImageViewJS, scalafimSurfaceViewJS))
  .settings(
    // saved-view wire record `org.neuropublish.view/workspace-state@1` (circe, no tapir here)
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % Versions.circe,
      "io.circe" %%% "circe-generic" % Versions.circe,
      "io.circe" %%% "circe-parser" % Versions.circe % Test
    )
  )

// Browser-ready typed-binary volume rendition: JVM encodes from canonical
// assets (ingestion), JVM and JS decode into ScalaFIM volumes (Spike A).
lazy val rendition = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("modules/rendition"))
  .settings(crossSettings("rendition"))
  .jsSettings(jsSettings)
  .dependsOn(protocolJson)
  .jvmConfigure(_.dependsOn(scalafimImageJVM, scalafimImageViewJVM % "test"))
  .jsConfigure(_.dependsOn(scalafimImageJS, scalafimImageViewJS % "test"))

// Tapir endpoints shared by server and browser client; OpenAPI source.
lazy val apiContract = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .withoutSuffixFor(JVMPlatform)
  .in(file("modules/api-contract"))
  .settings(crossSettings("api-contract"))
  .jsSettings(jsSettings)
  .dependsOn(protocolJson)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %%% "tapir-core" % Versions.tapir,
      "com.softwaremill.sttp.tapir" %%% "tapir-json-circe" % Versions.tapir,
      "io.circe" %%% "circe-generic" % Versions.circe
    )
  )

// ---------------------------------------------------------------------------
// Browser-only modules
// ---------------------------------------------------------------------------

// Laminar hosts for ScalaFIM volume and surface renderers; no app deps.
lazy val viewerLaminar = project
  .in(file("modules/viewer-laminar"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(viewerState.js, scalafimImageViewCanvasJS, scalafimSurfaceViewThreeJS)
  .settings(commonSettings, jsSettings)
  .settings(
    name := "neuropublish-viewer-laminar",
    libraryDependencies += "com.raquo" %%% "laminar" % Versions.laminar
  )

// The Neuropublish browser application (stores, routing, pages).
lazy val frontend = project
  .in(file("modules/frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(viewerLaminar, apiContract.js, rendition.js)
  .settings(commonSettings, jsSettings)
  .settings(
    name := "neuropublish-frontend",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~=
      (_.withModuleSplitStyle(
        ModuleSplitStyle.SmallModulesFor(List("neuropublish.frontend"))
      )),
    libraryDependencies ++= Seq(
      "com.raquo" %%% "laminar" % Versions.laminar,
      "com.raquo" %%% "waypoint" % Versions.waypoint,
      "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % Versions.tapir
    )
  )

// ---------------------------------------------------------------------------
// JVM-only modules
// ---------------------------------------------------------------------------

// Store algebras and records shared by the backend and its persistence implementations
// (package `neuropublish.backend`; kept tiny so `persistence` never depends on http4s).
lazy val domain = project
  .in(file("modules/domain"))
  .dependsOn(apiContract.jvm)
  .settings(commonSettings)
  .settings(
    name := "neuropublish-domain",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % Versions.catsEffect,
      "io.circe" %% "circe-generic" % Versions.circe
    )
  )

// PostgreSQL implementations of the domain stores: Flyway migrations, Doobie repositories,
// `reindex`. Tests need Docker (Testcontainers) and skip themselves without it.
lazy val persistence = project
  .in(file("modules/persistence"))
  .dependsOn(domain)
  .settings(commonSettings)
  .settings(
    name := "neuropublish-persistence",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core" % Versions.doobie,
      "org.tpolecat" %% "doobie-hikari" % Versions.doobie,
      "org.tpolecat" %% "doobie-postgres" % Versions.doobie,
      "org.tpolecat" %% "doobie-postgres-circe" % Versions.doobie,
      "org.flywaydb" % "flyway-core" % Versions.flyway,
      "org.flywaydb" % "flyway-database-postgresql" % Versions.flyway,
      "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test,
      "com.dimafeng" %% "testcontainers-scala-munit" % Versions.testcontainers % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % Versions.testcontainers % Test
    )
  )

// http4s + Tapir control-plane service. `test->test` on persistence shares the Testcontainers
// PostgreSQL fixture so the route suites also run against the database-backed server.
lazy val backend = project
  .in(file("modules/backend"))
  .dependsOn(apiContract.jvm, rendition.jvm, domain, persistence % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "neuropublish-backend",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % Versions.catsEffect,
      "org.slf4j" % "slf4j-simple" % "2.0.16",
      "org.http4s" %% "http4s-ember-server" % Versions.http4s,
      "org.http4s" %% "http4s-dsl" % Versions.http4s,
      "org.http4s" %% "http4s-ember-client" % Versions.http4s % Test,
      "org.http4s" %% "http4s-circe" % Versions.http4s % Test,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-client" % Versions.tapir % Test,
      "co.fs2" %% "fs2-io" % Versions.fs2,
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % Versions.tapir,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % Versions.apispec,
      // S3-compatible object store (Stage 2): async client + presigner
      "software.amazon.awssdk" % "s3" % Versions.awsSdk,
      "software.amazon.awssdk" % "netty-nio-client" % Versions.awsSdk,
      "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test,
      "com.dimafeng" %% "testcontainers-scala-munit" % Versions.testcontainers % Test
    )
  )

// Rendition derivation worker, a separate process from the control plane (Stage 2): claims
// ingestion jobs from the queue, derives renditions, writes them to the configured store.
lazy val ingestion = project
  .in(file("modules/ingestion"))
  .dependsOn(backend, rendition.jvm)
  .settings(commonSettings)
  .settings(
    name := "neuropublish-ingestion",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % Versions.catsEffect,
      "org.http4s" %% "http4s-circe" % Versions.http4s % Test,
      "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test
    )
  )

// `npub`: validate, inspect, pack, login, push.
lazy val publisherCli = project
  .in(file("modules/publisher-cli"))
  .dependsOn(apiContract.jvm, backend % "test->compile")
  .settings(commonSettings)
  .settings(
    name := "neuropublish-publisher-cli",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % Versions.catsEffect,
      "co.fs2" %% "fs2-io" % Versions.fs2,
      "com.monovore" %% "decline-effect" % Versions.decline,
      "org.http4s" %% "http4s-ember-client" % Versions.http4s,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-client" % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir % Test,
      "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test
    )
  )

// Golden bundles and foreign-producer harnesses.
lazy val conformance = project
  .in(file("modules/conformance"))
  .dependsOn(protocolJson.jvm, publisherCli, backend, viewerState.jvm, rendition.jvm)
  .settings(commonSettings)
  .settings(
    name := "neuropublish-conformance",
    publish / skip := true,
    // the Julia producer is driven against an in-process backend (Stage 2 neutrality proof)
    libraryDependencies ++= Seq(
      "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test,
      "org.http4s" %% "http4s-circe" % Versions.http4s % Test
    )
  )

// ---------------------------------------------------------------------------
// Root aggregates every Neuropublish module on every platform. Command aliases
// are `np`-prefixed because ScalaFIM's build (loaded by ProjectRef) defines its
// own `compileAll`/`testAll`/`checkAll` in the same session.
// ---------------------------------------------------------------------------
lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .aggregate(
    protocolCore.jvm,
    protocolCore.js,
    protocolJson.jvm,
    protocolJson.js,
    viewerState.jvm,
    viewerState.js,
    apiContract.jvm,
    apiContract.js,
    rendition.jvm,
    rendition.js,
    viewerLaminar,
    frontend,
    domain,
    persistence,
    backend,
    ingestion,
    publisherCli,
    conformance
  )
  .settings(name := "neuropublish")

addCommandAlias("npCompile", "root/compile")
addCommandAlias("npTest", "root/test")
addCommandAlias("npFormat", ";root/scalafmtAll;scalafmtSbt")
addCommandAlias("npCheck", ";root/scalafmtCheckAll;scalafmtSbtCheck;root/compile;root/test")
