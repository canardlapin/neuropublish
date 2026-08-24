package neuropublish.protocol.json

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import io.circe.Json
import neuropublish.protocol.Sha256

/** Trusted `org.neuropublish.domain/finite-indexed@1.0` domains (ADR 0005 section 1).
  *
  * Identity is the ordered vector of stable element keys. Labels, colours, atlas names, and row
  * count alone never establish alignment. The `keysAsset` is the exact identity preimage, making
  * the ordering independently content-addressable instead of dependent on JSON formatting.
  */
object FiniteIndexed:
  val KeysMediaType = "application/vnd.neuropublish.finite-indexed-keys-v1"
  private val Magic: Array[Byte] = "NPUDOM1\u0000".getBytes(StandardCharsets.US_ASCII)

  final case class Payload(elementKeys: Vector[String], keysAsset: String)

  /** `NPUDOM1\0`, descriptor id/version, uint64 element count, then each stable key in order. Every
    * string is uint32-length-prefixed UTF-8 and every integer is little-endian.
    */
  def preimage(descriptorId: String, descriptorVersion: String, p: Payload): Array[Byte] =
    val strings = (Vector(descriptorId, descriptorVersion) ++ p.elementKeys)
      .map(_.getBytes(StandardCharsets.UTF_8))
    val total = Magic.length.toLong + 8L + strings.map(s => 4L + s.length).sum
    require(total <= Int.MaxValue.toLong, "finite-indexed identity preimage exceeds 2 GiB")
    val buffer = ByteBuffer.allocate(total.toInt).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(Magic)
    strings.take(2).foreach { s =>
      buffer.putInt(s.length)
      buffer.put(s)
    }
    buffer.putLong(p.elementKeys.length.toLong)
    strings.drop(2).foreach { s =>
      buffer.putInt(s.length)
      buffer.put(s)
    }
    buffer.array()

  def fingerprint(descriptorId: String, descriptorVersion: String, p: Payload): Sha256 =
    Sha256.of(preimage(descriptorId, descriptorVersion, p))

  def readPayload(at: String, payload: Json): Either[List[Problem], Payload] =
    val c = payload.hcursor
    val ordering = c.get[String]("ordering").toOption.filter(_ == "explicit")
      .toRight(Problem(s"$at/ordering", "ordering must be 'explicit' in finite-indexed@1.0"))
    val asset = c.get[String]("keysAsset").toOption.filter(_.nonEmpty)
      .toRight(Problem(s"$at/keysAsset", "keysAsset must be a non-empty asset id"))
    val keys = c.downField("elementKeys").as[Vector[Json]].left.map(_ =>
      Problem(s"$at/elementKeys", "elementKeys must be a non-empty array of unique strings")
    ).flatMap { values =>
      if values.isEmpty then
        Left(Problem(s"$at/elementKeys", "elementKeys must contain at least one key"))
      else
        val decoded = values.zipWithIndex.map { (value, i) =>
          value.as[String].toOption.filter(_.nonEmpty)
            .toRight(Problem(s"$at/elementKeys/$i", "element key must be a non-empty string"))
        }
        decoded.collectFirst { case Left(p) => p } match
          case Some(p) => Left(p)
          case None =>
            val ks = decoded.collect { case Right(k) => k }
            val seen = scala.collection.mutable.HashSet.empty[String]
            ks.zipWithIndex.collectFirst {
              case (key, i) if !seen.add(key) =>
                Problem(s"$at/elementKeys/$i", s"duplicate element key '$key'")
            }.toLeft(ks)
    }
    val problems = List(ordering, keys, asset).collect { case Left(p) => p }
    if problems.nonEmpty then Left(problems)
    else Right(Payload(keys.toOption.get, asset.toOption.get))

  /** Recompute the exact finite-domain key and bind its separately stored key asset to the same
    * bytes. The asset's bytes are checked against its digest by normal publication integrity.
    */
  def check(
      at: String,
      descriptor: OpenRecord,
      key: Option[Json],
      assets: List[ManifestAsset]
  ): List[Problem] =
    val payloadAt = s"$at/descriptor/payload"
    val schemaProblems = SchemaCheck.finiteIndexedV1(payloadAt, descriptor.payload)
    val schemaAt = schemaProblems.map(_.pointer).toSet
    readPayload(payloadAt, descriptor.payload) match
      case Left(ps) => schemaProblems ++ ps.filterNot(p => schemaAt(p.pointer))
      case Right(p) =>
        val bytes = preimage(descriptor.schema.id, descriptor.schema.version, p)
        val expected = Sha256.of(bytes)
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
              case _ => Some(Problem(
                  s"$at/key/descriptor",
                  "key.descriptor must equal descriptor.schema (same id, version, and digest)"
                ))
            val sizeProblem = c.get[Long]("size").toOption match
              case Some(size) if size == p.elementKeys.length.toLong => None
              case Some(size) => Some(Problem(
                  s"$at/key/size",
                  s"key.size $size does not equal elementKeys length ${p.elementKeys.length}"
                ))
              case None => Some(Problem(
                  s"$at/key/size",
                  s"key.size is required (expected ${p.elementKeys.length})"
                ))
            val fingerprintProblem = c.get[String]("structuralFingerprint").toOption match
              case Some(value) if value == expected.render => None
              case Some(value) => Some(Problem(
                  s"$at/key/structuralFingerprint",
                  s"structural fingerprint $value does not equal the recomputed ${expected.render}"
                ))
              case None => Some(Problem(
                  s"$at/key/structuralFingerprint",
                  s"structuralFingerprint is required (expected ${expected.render})"
                ))
            List(descriptorProblem, sizeProblem, fingerprintProblem).flatten
        val assetProblems = assets.find(_.id == p.keysAsset) match
          case None => List(Problem(
              s"$payloadAt/keysAsset",
              s"keysAsset '${p.keysAsset}' is not declared in assets[]"
            ))
          case Some(asset) =>
            List(
              Option.when(asset.mediaType != KeysMediaType)(Problem(
                s"$payloadAt/keysAsset",
                s"keys asset '${asset.id}' has media type '${asset.mediaType}', expected '$KeysMediaType'"
              )),
              Option.when(asset.digest.render != expected.render)(Problem(
                s"$payloadAt/keysAsset",
                s"keys asset '${asset.id}' digest ${asset.digest.render} does not equal the recomputed ${expected.render}"
              )),
              Option.when(asset.size != bytes.length.toLong)(Problem(
                s"$payloadAt/keysAsset",
                s"keys asset '${asset.id}' size ${asset.size} does not equal identity preimage size ${bytes.length}"
              ))
            ).flatten
        (schemaProblems ++ keyProblems ++ assetProblems).distinct
