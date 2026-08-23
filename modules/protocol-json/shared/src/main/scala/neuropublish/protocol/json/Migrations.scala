package neuropublish.protocol.json

import io.circe.Json
import neuropublish.protocol.ProtocolVersion

/** Core-version migrations (SPEC.md, Compatibility). A migration is a pure JSON-to-JSON step from
  * one core minor to the next; it never drops a member it does not rename, it records the version
  * it came from in `migratedFrom`, and the original bytes stay the stored snapshot (the digest is
  * over them). Migrations do not apply to the digest, only to the parsed form a reader acts on.
  */
object Migrations:
  final case class Migration(from: ProtocolVersion, to: ProtocolVersion, apply: Json => Json)

  /** 0.0 → 0.1: the snapshot summary was named `description`; 0.1 calls it `synopsis`. */
  val `0.0-to-0.1`: Migration = Migration(
    ProtocolVersion(0, 0),
    ProtocolVersion(0, 1),
    json =>
      json.mapObject { o =>
        val renamed = o("description") match
          case Some(d) if !o.contains("synopsis") => o.remove("description").add("synopsis", d)
          case _ => o
        renamed.add("core", Json.fromString("0.1"))
      }
  )

  val all: List[Migration] = List(`0.0-to-0.1`)

  /** Bring a parsed manifest to the current core: apply each migration in turn (stamping
    * `migratedFrom` with the version the bytes declared), accept the current line and newer minors
    * unchanged, reject anything else with a clear problem at `/core`.
    */
  def bring(json: Json): Either[Problem, Json] =
    json.hcursor.get[String]("core") match
      case Left(_) => Left(Problem("/core", "core protocol version is required"))
      case Right(core) =>
        ProtocolVersion.parse(core) match
          case Left(m) => Left(Problem("/core", m))
          case Right(v)
              if v.major == ProtocolVersion.current.major &&
                v.minor >= ProtocolVersion.current.minor => Right(json)
          case Right(v) =>
            def step(j: Json, at: ProtocolVersion): Either[Problem, Json] =
              if at == ProtocolVersion.current then Right(j)
              else
                all.find(_.from == at) match
                  case Some(mig) => step(mig.apply(j), mig.to)
                  case None => Left(Problem(
                      "/core",
                      s"core $core is not readable by a ${ProtocolVersion.current.render} implementation and no migration exists"
                    ))
            step(json, v).map(_.mapObject(_.add("migratedFrom", Json.fromString(core))))
