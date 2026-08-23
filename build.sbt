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

// JSON codecs, byte-profile admission, manifest digest.
lazy val protocolJson = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
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

// http4s + Tapir control-plane service.
lazy val backend = project
  .in(file("modules/backend"))
  .dependsOn(apiContract.jvm, rendition.jvm)
  .settings(commonSettings)
  .settings(
    name := "neuropublish-backend",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % Versions.catsEffect,
      "org.http4s" %% "http4s-ember-server" % Versions.http4s,
      "org.http4s" %% "http4s-ember-client" % Versions.http4s % Test,
      "org.http4s" %% "http4s-circe" % Versions.http4s % Test,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-client" % Versions.tapir % Test,
      "co.fs2" %% "fs2-io" % Versions.fs2,
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % Versions.tapir,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % Versions.apispec,
      "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test
    )
  )

// `npub`: validate, inspect, pack, login, push.
lazy val publisherCli = project
  .in(file("modules/publisher-cli"))
  .dependsOn(apiContract.jvm)
  .settings(commonSettings)
  .settings(
    name := "neuropublish-publisher-cli",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % Versions.catsEffect,
      "co.fs2" %% "fs2-io" % Versions.fs2,
      "com.monovore" %% "decline-effect" % Versions.decline,
      "org.http4s" %% "http4s-ember-client" % Versions.http4s,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-client" % Versions.tapir,
      "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test
    )
  )

// Golden bundles and foreign-producer harnesses.
lazy val conformance = project
  .in(file("modules/conformance"))
  .dependsOn(protocolJson.jvm, publisherCli)
  .settings(commonSettings)
  .settings(
    name := "neuropublish-conformance",
    publish / skip := true
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
    backend,
    publisherCli,
    conformance
  )
  .settings(name := "neuropublish")

addCommandAlias("npCompile", "root/compile")
addCommandAlias("npTest", "root/test")
addCommandAlias("npFormat", ";root/scalafmtAll;scalafmtSbt")
addCommandAlias("npCheck", ";root/scalafmtCheckAll;scalafmtSbtCheck;root/compile;root/test")
