package neuropublish.protocol.json

import com.networknt.schema.{
  InputFormat,
  JsonSchema,
  JsonSchemaFactory,
  PathType,
  SchemaLocation,
  SchemaValidatorsConfig,
  SpecVersion
}
import io.circe.Json
import scala.jdk.CollectionConverters.*

/** JSON Schema 2020-12 validation against the normative documents in `protocol/schemas/`, served
  * from the classpath as `schemas/<name>.schema.json` (the `protocol/` directory is a resource root
  * of this module, so there is exactly one copy). JVM only: the Scala.js build keeps the
  * decoder-only path and its `SchemaCheck.manifest` returns no problems.
  */
object SchemaCheck:
  val Base = "https://neuropublish.org/schema/0.1/"

  private val factory = JsonSchemaFactory.getInstance(
    SpecVersion.VersionFlag.V202012,
    builder => builder.schemaMappers(m => { m.mapPrefix(Base, "classpath:schemas/"); () })
  )
  private val config = SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build()

  private def load(name: String): JsonSchema =
    factory.getSchema(SchemaLocation.of(s"$Base$name.schema.json"), config)

  lazy val manifestSchema: JsonSchema = load("manifest")
  lazy val workspaceStateSchema: JsonSchema = load("workspace-state")
  lazy val renditionHeaderSchema: JsonSchema = load("rendition-header")
  lazy val volumeGridV1Schema: JsonSchema = load("records/volume-grid-v1")

  def validate(schema: JsonSchema, json: Json): List[Problem] =
    schema.validate(json.noSpaces, InputFormat.JSON).asScala.toList.map { v =>
      val where = Option(v.getInstanceLocation).map(_.toString).getOrElse("")
      val pointer = if where == "/" then "" else where
      // a missing required member is addressed at the member, not its parent
      val p = Option(v.getProperty).filter(_ => v.getType == "required")
        .map(name => JsonPointer.field(pointer, name)).getOrElse(pointer)
      Problem(p, v.getError)
    }.distinct

  def manifest(json: Json): List[Problem] = validate(manifestSchema, json)
  def workspaceState(json: Json): List[Problem] = validate(workspaceStateSchema, json)
  def renditionHeader(json: Json): List[Problem] = validate(renditionHeaderSchema, json)

  /** A trusted `volume-grid@1.0` descriptor payload against its records schema; problems are
    * addressed under `at`, the payload's pointer in the manifest.
    */
  def volumeGridV1(at: String, payload: Json): List[Problem] =
    validate(volumeGridV1Schema, payload).map(p => p.copy(pointer = at + p.pointer))
