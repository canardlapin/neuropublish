package neuropublish.protocol.json

import java.nio.{ByteBuffer, ByteOrder}
import io.circe.Json

/** Trusted hard assignments from an exact spatial support domain to an exact finite-indexed domain
  * (ADR 0005 section 2). One int32 per source ordinal makes this a partial map by construction:
  * `-1` is background and every non-negative value is one target ordinal.
  */
object HardAssignment:
  val MediaType = "application/vnd.neuropublish.hard-assignment-i32le-v1"
  val Coverages: Set[String] = Set("complete", "allow-empty")

  final case class Payload(
      asset: String,
      coverage: String,
      emptyParcels: Vector[String],
      derivation: String
  )

  /** A structurally checked assignment. `surjective` is true exactly for complete target coverage;
    * allow-empty assignments preserve every target key and report the absent fibers.
    */
  final case class Checked(ordinals: Vector[Int], emptyParcels: Vector[String], surjective: Boolean)

  def readPayload(at: String, payload: Json): Either[List[Problem], Payload] =
    val c = payload.hcursor
    def str(name: String): Either[Problem, String] =
      c.get[String](name).toOption.filter(_.nonEmpty)
        .toRight(Problem(s"$at/$name", s"$name must be a non-empty string"))
    val asset = str("asset")
    val coverage = str("coverage").flatMap(value =>
      Either.cond(
        Coverages(value),
        value,
        Problem(s"$at/coverage", s"coverage must be 'complete' or 'allow-empty', not '$value'")
      )
    )
    val empty = c.downField("emptyParcels").as[Vector[Json]].left.map(_ =>
      Problem(s"$at/emptyParcels", "emptyParcels must be an array of unique target keys")
    ).flatMap { values =>
      val decoded = values.zipWithIndex.map { (value, i) =>
        value.as[String].toOption.filter(_.nonEmpty)
          .toRight(Problem(s"$at/emptyParcels/$i", "empty parcel key must be a non-empty string"))
      }
      decoded.collectFirst { case Left(p) => p } match
        case Some(p) => Left(p)
        case None =>
          val keys = decoded.collect { case Right(k) => k }
          val seen = scala.collection.mutable.HashSet.empty[String]
          keys.zipWithIndex.collectFirst {
            case (key, i) if !seen.add(key) =>
              Problem(s"$at/emptyParcels/$i", s"duplicate empty parcel key '$key'")
          }.toLeft(keys)
    }
    val derivation = str("derivation")
    val problems = List(asset, coverage, empty, derivation).collect { case Left(p) => p }
    if problems.nonEmpty then Left(problems)
    else
      Right(Payload(
        asset.toOption.get,
        coverage.toOption.get,
        empty.toOption.get,
        derivation.toOption.get
      ))

  /** Validate declaration-level facts available before opening the assignment asset. */
  def checkDeclaration(
      at: String,
      mapping: DomainMapping,
      manifest: Manifest
  ): List[Problem] =
    val payloadAt = s"$at/descriptor/payload"
    val schemaProblems = SchemaCheck.hardAssignmentV1(payloadAt, mapping.descriptor.payload)
    val schemaAt = schemaProblems.map(_.pointer).toSet
    readPayload(payloadAt, mapping.descriptor.payload) match
      case Left(ps) => schemaProblems ++ ps.filterNot(p => schemaAt(p.pointer))
      case Right(p) =>
        val source = manifest.domains.find(_.id == mapping.source)
        val target = manifest.domains.find(_.id == mapping.target)
        val supportProblem = source.flatMap { domain =>
          val id = domain.descriptor.schema.id
          Option.when(
            id != TrustedSchemas.VolumeGridV1.id && id != TrustedSchemas.SurfaceVerticesV1.id
          )(Problem(
            s"$at/source",
            s"hard assignment source '${mapping.source}' is not a trusted volume-grid or surface-vertices domain"
          ))
        }
        val targetPayload = target.flatMap { domain =>
          Option.when(domain.descriptor.schema.id == TrustedSchemas.FiniteIndexedV1.id)(domain)
        }.flatMap(domain => FiniteIndexed.readPayload("", domain.descriptor.payload).toOption)
        val targetProblem = target.flatMap(_ =>
          Option.when(targetPayload.isEmpty)(Problem(
            s"$at/target",
            s"hard assignment target '${mapping.target}' is not a readable trusted finite-indexed domain"
          ))
        )
        val assetProblems = manifest.asset(p.asset) match
          case None => List(Problem(
              s"$payloadAt/asset",
              s"assignment asset '${p.asset}' is not declared in assets[]"
            ))
          case Some(asset) =>
            val sourceSize = source.flatMap(_.key).flatMap(_.hcursor.get[Long]("size").toOption)
            val expectedSize = sourceSize.flatMap(size =>
              try Some(math.multiplyExact(size, 4L))
              catch case _: ArithmeticException => None
            )
            List(
              Option.when(asset.mediaType != MediaType)(Problem(
                s"$payloadAt/asset",
                s"assignment asset '${asset.id}' has media type '${asset.mediaType}', expected '$MediaType'"
              )),
              expectedSize.flatMap(size =>
                Option.when(asset.size != size)(Problem(
                  s"$payloadAt/asset",
                  s"assignment asset '${asset.id}' size ${asset.size} does not equal 4 bytes x source size ${sourceSize.getOrElse("(missing)")} = $size"
                ))
              )
            ).flatten
        val activityIds = manifest.provenanceIds("activities").toSet
        val derivationProblem = Option.when(!activityIds(p.derivation))(Problem(
          s"$payloadAt/derivation",
          s"derivation references unknown provenance activity '${p.derivation}'"
        ))
        val coverageProblems = targetPayload.toList.flatMap { target =>
          val targetSet = target.elementKeys.toSet
          val unknown = p.emptyParcels.zipWithIndex.collect {
            case (key, i) if !targetSet(key) =>
              Problem(
                s"$payloadAt/emptyParcels/$i",
                s"empty parcel '$key' is not in target domain '${mapping.target}'"
              )
          }
          val ordered = target.elementKeys.filter(p.emptyParcels.toSet)
          val orderProblem = Option.when(ordered != p.emptyParcels)(Problem(
            s"$payloadAt/emptyParcels",
            "emptyParcels must appear in exact target-domain order"
          ))
          val policyProblem =
            if p.coverage == "complete" && p.emptyParcels.nonEmpty then
              Some(Problem(
                s"$payloadAt/emptyParcels",
                "complete coverage requires emptyParcels to be empty"
              ))
            else if p.coverage == "allow-empty" && p.emptyParcels.isEmpty then
              Some(Problem(
                s"$payloadAt/emptyParcels",
                "allow-empty coverage must declare at least one empty target; use complete otherwise"
              ))
            else None
          unknown ++ List(orderProblem, policyProblem).flatten
        }
        (schemaProblems ++ List(supportProblem, targetProblem, derivationProblem).flatten ++
          assetProblems ++ coverageProblems).distinct

  /** Open and validate an assignment asset. Binary errors stay attached to the asset declaration
    * because byte offsets are not JSON members. The returned value is the neutral checked partial
    * map; a complete result is a certified surjection.
    */
  def checkBytes(
      at: String,
      payload: Payload,
      sourceSize: Long,
      targetKeys: Vector[String],
      bytes: Array[Byte]
  ): Either[List[Problem], Checked] =
    val assetAt = s"$at/descriptor/payload/asset"
    val expected =
      try Some(math.multiplyExact(sourceSize, 4L))
      catch case _: ArithmeticException => None
    expected match
      case None =>
        Left(List(Problem(assetAt, "source domain is too large for an int32 assignment asset")))
      case Some(size) if size != bytes.length.toLong =>
        Left(List(Problem(
          assetAt,
          s"assignment has ${bytes.length} bytes, expected $size (4 bytes x source size $sourceSize)"
        )))
      case Some(_) =>
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ordinals = Vector.fill(sourceSize.toInt)(buffer.getInt())
        val bounds = ordinals.zipWithIndex.collect {
          case (value, i) if value < -1 || value >= targetKeys.length =>
            Problem(
              assetAt,
              s"assignment source ordinal $i has target ordinal $value; expected -1 or [0, ${targetKeys.length -
                  1}]"
            )
        }
        if bounds.nonEmpty then Left(bounds.toList)
        else
          val reached = ordinals.iterator.filter(_ >= 0).toSet
          val computed = targetKeys.zipWithIndex.collect { case (key, i) if !reached(i) => key }
          val coverage = payload.coverage match
            case "complete" if computed.nonEmpty =>
              List(Problem(
                s"$at/descriptor/payload/coverage",
                s"complete coverage has empty target keys: ${computed.mkString(", ")}"
              ))
            case "allow-empty" if computed != payload.emptyParcels =>
              List(Problem(
                s"$at/descriptor/payload/emptyParcels",
                s"declared empty targets [${payload.emptyParcels.mkString(", ")}] do not equal computed [${computed.mkString(", ")}]"
              ))
            case _ => Nil
          Either.cond(
            coverage.isEmpty,
            Checked(ordinals, computed, computed.isEmpty),
            coverage
          )
