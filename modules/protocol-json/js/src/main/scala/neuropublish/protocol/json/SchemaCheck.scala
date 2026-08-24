package neuropublish.protocol.json

import io.circe.Json

/** The Scala.js build keeps the decoder-only path: structural, closure, and semantic checks run,
  * JSON Schema validation does not (the JVM server and CLI are the admission authorities).
  */
object SchemaCheck:
  def manifest(json: Json): List[Problem] = Nil
  def volumeGridV1(at: String, payload: Json): List[Problem] = Nil
  def surfaceVerticesV1(at: String, payload: Json): List[Problem] = Nil
  def finiteIndexedV1(at: String, payload: Json): List[Problem] = Nil
  def hardAssignmentV1(at: String, payload: Json): List[Problem] = Nil
