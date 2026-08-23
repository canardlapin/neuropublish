package neuropublish.protocol.json

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import io.circe.Json
import neuropublish.protocol.Sha256

/** The trusted `org.neuropublish.domain/surface-vertices@1.0` descriptor (ADR 0005 §1, SPEC §6): an
  * ordered cortical vertex domain whose identity is the hemisphere, the surface space, the vertex
  * and face counts, and every triangle of the `topology` asset in face-array order. Vertex
  * coordinates are deliberately outside the key, so white, pial, and inflated geometries realize
  * one domain.
  *
  * The fingerprint needs the face indices, which live in a GIFTI asset the admission pipeline does
  * not open (the manifest is admitted before its assets are read, and GIFTI decoding is JVM-only).
  * Admission therefore verifies what the manifest alone can prove — payload shape, key/descriptor
  * agreement, `key.size` = `vertexCount`, a well-formed fingerprint, the `topology` asset declared
  * as a surface on this domain — and ingestion recomputes the fingerprint from the asset bytes and
  * refuses the revision when it disagrees (SPEC §6, "admission split"). Shared by JVM and Scala.js
  * so both compute the same preimage byte for byte.
  */
object SurfaceVertices:
  val Hemispheres: Set[String] = Set("left", "right")

  final case class Payload(
      space: String,
      hemisphere: String,
      vertexCount: Int,
      faceCount: Int,
      topology: String
  )

  /** The ADR 0005 `surface-vertices/v1` preimage: magic, four length-prefixed UTF-8 strings
    * (descriptor id, descriptor version, space, hemisphere), unsigned 64-bit vertex and face
    * counts, then every triangle as three unsigned 32-bit vertex ordinals in face-array order. All
    * integers little-endian.
    */
  def preimage(
      descriptorId: String,
      descriptorVersion: String,
      space: String,
      hemisphere: String,
      vertexCount: Int,
      faceCount: Int,
      faceIndices: Array[Int]
  ): Array[Byte] =
    require(faceIndices.length == faceCount * 3, "faceIndices must hold faceCount triangles")
    val strings = Vector(descriptorId, descriptorVersion, space, hemisphere)
      .map(_.getBytes(StandardCharsets.UTF_8))
    val size = VolumeGrid.Magic.length + strings.map(4 + _.length).sum + 16 + faceIndices.length * 4
    val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(VolumeGrid.Magic)
    strings.foreach { s =>
      buffer.putInt(s.length)
      buffer.put(s)
    }
    buffer.putLong(vertexCount.toLong)
    buffer.putLong(faceCount.toLong)
    var i = 0
    while i < faceIndices.length do
      buffer.putInt(faceIndices(i)) // non-negative, so the int32 bit pattern is the uint32 value
      i += 1
    buffer.array()

  def fingerprint(
      descriptorId: String,
      descriptorVersion: String,
      p: Payload,
      faceIndices: Array[Int]
  ): Sha256 =
    Sha256.of(preimage(
      descriptorId,
      descriptorVersion,
      p.space,
      p.hemisphere,
      p.vertexCount,
      p.faceCount,
      faceIndices
    ))

  /** Structural reading of a payload, every problem at its pointer under `at` (the subset of
    * `records/surface-vertices-v1.schema.json` the key needs; the JVM also validates the schema).
    */
  def readPayload(at: String, payload: Json): Either[List[Problem], Payload] =
    val c = payload.hcursor
    def str(name: String): Either[Problem, String] =
      c.get[String](name).left.map(_ => Problem(s"$at/$name", s"$name must be a string"))
        .flatMap(s =>
          if s.isEmpty then Left(Problem(s"$at/$name", s"$name must not be empty")) else Right(s)
        )
    def count(name: String): Either[Problem, Int] =
      c.get[Long](name).toOption.filter(n => n >= 1 && n <= Int.MaxValue.toLong).map(_.toInt)
        .toRight(Problem(s"$at/$name", s"$name must be an integer in [1, 2147483647]"))
    val hemisphere = str("hemisphere").flatMap(h =>
      if Hemispheres(h) then Right(h)
      else Left(Problem(s"$at/hemisphere", s"hemisphere must be 'left' or 'right', not '$h'"))
    )
    val parts =
      List(str("space"), hemisphere, count("vertexCount"), count("faceCount"), str("topology"))
    val problems = parts.collect { case Left(p) => p }
    if problems.nonEmpty then Left(problems)
    else
      (for
        space <- str("space")
        h <- hemisphere
        v <- count("vertexCount")
        f <- count("faceCount")
        t <- str("topology")
      yield Payload(space, h, v, f, t)).left.map(List(_))

  /** Admission-time check of one trusted surface-vertices domain: everything verifiable without the
    * topology asset's bytes. `surfacesOnDomain` are the `surfaces[]` entries declared on this
    * domain, `(asset id, hemisphere)`; `topology` names an asset (a `surfaces[].asset`, never a
    * `surfaces[].id` — the fingerprint is a function of the asset's bytes) and must be one of them
    * with the payload's hemisphere. The fingerprint itself is verified at ingestion
    * ([[fingerprint]]).
    */
  def check(
      at: String,
      descriptor: OpenRecord,
      key: Option[Json],
      surfacesOnDomain: List[(String, String)]
  ): List[Problem] =
    val payloadAt = s"$at/descriptor/payload"
    val schemaProblems = SchemaCheck.surfaceVerticesV1(payloadAt, descriptor.payload)
    val schemaAt = schemaProblems.map(_.pointer).toSet
    readPayload(payloadAt, descriptor.payload) match
      case Left(ps) => schemaProblems ++ ps.filterNot(p => schemaAt(p.pointer))
      case Right(p) =>
        val topologyProblem = surfacesOnDomain.find(_._1 == p.topology) match
          case None =>
            Some(Problem(
              s"$payloadAt/topology",
              s"topology '${p.topology}' is not the asset of any surfaces[] entry on this domain (topology names a surfaces[].asset, not a surfaces[].id)"
            ))
          case Some((_, h)) if h != p.hemisphere =>
            Some(Problem(
              s"$payloadAt/topology",
              s"topology asset '${p.topology}' is declared as the $h hemisphere in surfaces[]; the domain is ${p.hemisphere}"
            ))
          case _ => None
        val keyProblems = key match
          case None =>
            List(Problem(
              s"$at/key",
              "a trusted descriptor requires an exact key (descriptor, size, structuralFingerprint)"
            ))
          case Some(k) =>
            val c = k.hcursor
            val wire = c.downField("descriptor").focus.flatMap(_.as[SchemaRefWire](using
              OpenRecord.given_Decoder_SchemaRefWire
            ).toOption)
            val descriptorProblem = wire match
              case Some(w)
                  if w.id == descriptor.schema.id && w.version == descriptor.schema.version &&
                    w.digest.map(_.hex) == descriptor.schema.digest.map(_.hex) => None
              case _ =>
                Some(Problem(
                  s"$at/key/descriptor",
                  "key.descriptor must equal descriptor.schema (same id, version, and digest)"
                ))
            val sizeProblem = c.get[Long]("size").toOption match
              case Some(s) if s == p.vertexCount.toLong => None
              case Some(s) =>
                Some(Problem(
                  s"$at/key/size",
                  s"key.size $s does not equal vertexCount ${p.vertexCount}"
                ))
              case None =>
                Some(Problem(s"$at/key/size", s"key.size is required (expected ${p.vertexCount})"))
            val fpProblem = c.get[String]("structuralFingerprint").toOption match
              case Some(f) if f.startsWith("sha256:") && Sha256.parse(f).isRight => None
              case Some(f) =>
                Some(Problem(
                  s"$at/key/structuralFingerprint",
                  s"structural fingerprint '$f' is not a sha256 identity"
                ))
              case None =>
                Some(Problem(
                  s"$at/key/structuralFingerprint",
                  "structuralFingerprint is required (recomputed from the topology asset at ingestion)"
                ))
            List(descriptorProblem, sizeProblem, fpProblem).flatten
        (schemaProblems ++ topologyProblem.toList ++ keyProblems).distinct
