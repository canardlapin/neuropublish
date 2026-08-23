package neuropublish.protocol.json

import neuropublish.protocol.SemanticId

/** Cross-field invariants from `protocol/SPEC.md` that JSON Schema cannot express. Every check runs
  * and every failure is reported with the pointer of the offending member.
  */
object ManifestChecks:

  def all(m: Manifest): List[Problem] =
    uniqueIds(m) ++ referenceClosure(m) ++ estimandClosure(m) ++ orders(m) ++ measures(m) ++
      sensitivity(m) ++ warningScopes(m) ++ openRecords(m) ++ domains(m) ++ surfaces(m) ++
      spaces(m) ++ provenanceEdges(m)

  private def dupes(pointer: Int => String, ids: List[String], what: String): List[Problem] =
    ids.zipWithIndex.groupBy(_._1).toList.filter(_._2.size > 1).flatMap { (id, occ) =>
      occ.tail.map((_, i) => Problem(pointer(i), s"duplicate $what id '$id'"))
    }.sortBy(_.pointer)

  def uniqueIds(m: Manifest): List[Problem] =
    dupes(i => s"/assets/$i/id", m.assets.map(_.id), "asset") ++
      dupes(i => s"/analyses/$i/id", m.analyses.map(_.id), "analysis") ++
      m.analyses.zipWithIndex.flatMap((a, ai) =>
        dupes(i => s"/analyses/$ai/estimands/$i/id", a.estimands.map(_.id), "estimand")
      ) ++
      dupes(i => s"/resultFields/$i/id", m.resultFields.map(_.id), "result field") ++
      dupes(i => s"/domains/$i/id", m.domains.map(_.id), "domain") ++
      dupes(i => s"/surfaces/$i/id", m.surfaces.map(_.id), "surface") ++
      dupes(i => s"/warnings/$i/id", m.warnings.map(_.id), "warning") ++
      dupes(
        i => s"/provenance/entities/$i/id",
        provenanceIds(m, "entities"),
        "provenance entity"
      ) ++
      dupes(
        i => s"/provenance/activities/$i/id",
        provenanceIds(m, "activities"),
        "provenance activity"
      )

  private def provenanceIds(m: Manifest, kind: String): List[String] =
    m.raw.hcursor.downField("provenance").downField(kind).as[List[io.circe.Json]].toOption
      .getOrElse(Nil).flatMap(_.hcursor.get[String]("id").toOption)

  /** Representations, underlays, surfaces, and fields reference declared assets and domains. */
  def referenceClosure(m: Manifest): List[Problem] =
    val assets = m.assets.map(_.id).toSet
    val domains = m.domains.map(_.id).toSet
    val reps = m.resultFields.zipWithIndex.flatMap((f, fi) =>
      f.representations.zipWithIndex.collect {
        case (r, ri) if !assets(r.asset) =>
          Problem(
            s"/resultFields/$fi/representations/$ri/asset",
            s"representation references undeclared asset '${r.asset}'"
          )
      }
    )
    val fieldDomains = m.resultFields.zipWithIndex.collect {
      case (f, fi) if !domains(f.domain) =>
        Problem(
          s"/resultFields/$fi/domain",
          s"result field references undeclared domain '${f.domain}'"
        )
    }
    val under = m.underlays.zipWithIndex.flatMap((u, ui) =>
      List(
        Option.when(!assets(u.asset))(
          Problem(s"/underlays/$ui/asset", s"underlay references undeclared asset '${u.asset}'")
        ),
        Option.when(!domains(u.domain))(
          Problem(s"/underlays/$ui/domain", s"underlay references undeclared domain '${u.domain}'")
        )
      ).flatten
    )
    val surf = m.surfaces.zipWithIndex.flatMap((s, si) =>
      List(
        Option.when(!assets(s.asset))(
          Problem(s"/surfaces/$si/asset", s"surface references undeclared asset '${s.asset}'")
        ),
        Option.when(!domains(s.domain))(
          Problem(s"/surfaces/$si/domain", s"surface references undeclared domain '${s.domain}'")
        )
      ).flatten
    )
    reps ++ fieldDomains ++ under ++ surf

  /** Every result field's estimand is declared by some analysis. */
  def estimandClosure(m: Manifest): List[Problem] =
    val estimands = m.analyses.flatMap(_.estimands.map(_.id)).toSet
    m.resultFields.zipWithIndex.collect {
      case (f, i) if !estimands(f.estimand) =>
        Problem(
          s"/resultFields/$i/estimand",
          s"result field references estimand '${f.estimand}' that no analysis declares"
        )
    }

  /** `order` is unique among an analysis's estimands and among the fields of one estimand. */
  def orders(m: Manifest): List[Problem] =
    val e = m.analyses.zipWithIndex.flatMap { (a, ai) =>
      a.estimands.zipWithIndex.collect { case (x, i) if x.order.isDefined => (x.order.get, i) }
        .groupBy(_._1).toList.filter(_._2.size > 1).flatMap((o, occ) =>
          occ.tail.map((_, i) =>
            Problem(
              s"/analyses/$ai/estimands/$i/order",
              s"estimand order $o is already used in analysis '${a.id}'"
            )
          )
        )
    }
    val f = m.resultFields.zipWithIndex.collect {
      case (x, i) if x.order.isDefined => (x.estimand, x.order.get, i)
    }.groupBy(t => (t._1, t._2)).toList.filter(_._2.size > 1).flatMap { case ((est, o), occ) =>
      occ.tail.map((_, _, i) =>
        Problem(
          s"/resultFields/$i/order",
          s"result field order $o is already used for estimand '$est'"
        )
      )
    }
    (e ++ f).sortBy(_.pointer)

  /** Measures are semantic ids (the schema checks the grammar; this check also runs on JS). */
  def measures(m: Manifest): List[Problem] =
    m.resultFields.zipWithIndex.flatMap((f, i) =>
      SemanticId.parse(f.measure).left.toOption.map(msg =>
        Problem(s"/resultFields/$i/measure", msg)
      )
    )

  val Sensitivities: Set[String] = Set("group-level", "subject-level")

  def sensitivity(m: Manifest): List[Problem] =
    m.sensitivity match
      case None =>
        List(Problem("/sensitivity", "sensitivity is required (group-level | subject-level)"))
      case Some(s) if !Sensitivities(s) =>
        List(Problem("/sensitivity", s"unknown sensitivity '$s' (group-level | subject-level)"))
      case _ => Nil

  /** A committed manifest carries digests, so a catalog reference is informational; one that still
    * lacks a digest (or carries a local `path`) was never resolved. Checked on the raw JSON so it
    * is reported even when the structural decoder cannot build the asset.
    */
  def catalogs(raw: io.circe.Json): List[Problem] =
    raw.hcursor.downField("assets").as[List[io.circe.Json]].toOption.getOrElse(Nil).zipWithIndex
      .flatMap { (a, i) =>
        val c = a.hcursor
        val hasDigest = c.downField("digest").focus.exists(_.isString)
        val hasPath = c.downField("path").succeeded
        val catalog = c.downField("catalog").focus.exists(_.isString)
        List(
          Option.when(catalog && !hasDigest)(Problem(
            s"/assets/$i/catalog",
            "catalog reference is not resolved to a digest (run `npub pack`)"
          )),
          Option.when(hasPath)(Problem(
            s"/assets/$i/path",
            "local paths are not allowed in a committed manifest (run `npub pack`)"
          ))
        ).flatten
      }

  /** Warning scope pointers (`concerns`) resolve to an analysis, result field, or provenance node.
    */
  def warningScopes(m: Manifest): List[Problem] =
    val analyses = m.analyses.map(_.id).toSet
    val fields = m.resultFields.map(_.id).toSet
    val prov = (provenanceIds(m, "entities") ++ provenanceIds(m, "activities")).toSet
    m.warnings.zipWithIndex.flatMap { (w, i) =>
      w.concerns.toList.flatMap { c =>
        def check(kind: String, known: Set[String]) =
          c.hcursor.get[String](kind).toOption.filterNot(known).map(id =>
            Problem(s"/warnings/$i/concerns/$kind", s"warning concerns unknown $kind '$id'")
          )
        check("analysis", analyses) ++ check("field", fields) ++ check("provenance", prov)
      }
    }

  /** Trusted-namespace records must match a schema this build knows; unknown ones are retained. */
  def openRecords(m: Manifest): List[Problem] =
    m.openRecords.flatMap {
      case (_, _, Interpretation.Invalid(_, ps)) => ps
      case _ => Nil
    }

  /** Trusted domain descriptors: the exact key (`descriptor`, `size`, `structuralFingerprint`) is
    * recomputed from the descriptor payload (SPEC §6); a declared key that disagrees is rejected.
    * Only understood descriptors are checked; an invalid trusted record is already reported by
    * [[openRecords]] and an unsupported one gains no behaviour to check.
    */
  def domains(m: Manifest): List[Problem] =
    m.domains.zipWithIndex.flatMap { (d, i) =>
      val at = s"/domains/$i"
      TrustedSchemas.interpret(s"$at/descriptor", d.descriptor) match
        case Interpretation.Understood(r, _) if r.schema.id == TrustedSchemas.VolumeGridV1.id =>
          VolumeGrid.check(at, r, d.key)
        case Interpretation.Understood(r, _)
            if r.schema.id == TrustedSchemas.SurfaceVerticesV1.id =>
          val onDomain = m.surfaces.filter(_.domain == d.id).map(s => (s.asset, s.hemisphere))
          SurfaceVertices.check(at, r, d.key, onDomain)
        case _ => Nil
    }

  /** How a domain reads as a surface support: a readable surface-vertices payload, a
    * surface-vertices descriptor whose payload does not read (already reported under
    * `/domains/i/descriptor/payload` by [[domains]]), or not a surface-vertices domain at all.
    * Keeping the three apart is what lets a surface say the accurate thing about its domain.
    */
  enum SurfaceSupport:
    case Vertices(payload: SurfaceVertices.Payload)
    case Unreadable
    case NotSurfaceVertices

  /** Every domain's surface support, in `domains[]` order. */
  private def supports(m: Manifest): List[SurfaceSupport] =
    m.domains.zipWithIndex.map { (d, i) =>
      TrustedSchemas.interpret(s"/domains/$i/descriptor", d.descriptor) match
        case Interpretation.Understood(r, _)
            if r.schema.id == TrustedSchemas.SurfaceVerticesV1.id =>
          SurfaceVertices.readPayload("", r.payload).fold(
            _ => SurfaceSupport.Unreadable,
            SurfaceSupport.Vertices.apply
          )
        case _ => SurfaceSupport.NotSurfaceVertices
    }

  def surfaceSupport(m: Manifest, id: String): Option[SurfaceSupport] =
    m.domains.zip(supports(m)).collectFirst { case (d, s) if d.id == id => s }

  /** The trusted surface-vertices payload of domain `id`, when it is one and reads. */
  def surfaceDomain(m: Manifest, id: String): Option[SurfaceVertices.Payload] =
    surfaceSupport(m, id).collect { case SurfaceSupport.Vertices(p) => p }

  /** The `space` of every trusted volume-grid domain whose payload reads, in `domains[]` order. */
  private def volumeSpaces(m: Manifest): List[String] =
    m.domains.zipWithIndex.flatMap { (d, i) =>
      TrustedSchemas.interpret(s"/domains/$i/descriptor", d.descriptor) match
        case Interpretation.Understood(r, _) if r.schema.id == TrustedSchemas.VolumeGridV1.id =>
          VolumeGrid.readPayload("", r.payload).toOption.map(_.space)
        case _ => None
    }.distinct

  /** A surface and a volume in one revision are linked by world millimetres — a surface pick moves
    * the volume cursor and back — which means something only when both are in the same space. A
    * `surface-vertices` domain whose `space` is not every `volume-grid` domain's `space` is refused
    * at its `space` member (SPEC §6, "Spaces"); a manifest with no volume domain has nothing to
    * disagree with.
    */
  def spaces(m: Manifest): List[Problem] =
    val volume = volumeSpaces(m)
    if volume.isEmpty then Nil
    else
      m.domains.zip(supports(m)).zipWithIndex.collect {
        case ((_, SurfaceSupport.Vertices(p)), i) if volume != List(p.space) =>
          Problem(
            s"/domains/$i/descriptor/payload/space",
            s"surface space '${p.space}' is not the volume space ${volume.map(v => s"'$v'").mkString(" or ")}; a surface and a volume in one revision must be in the same space"
          )
      }

  /** Surfaces sit on a surface-vertices domain of their hemisphere; a surface representation names
    * a declared surface of its hemisphere, and its `derivation`, when present, a provenance
    * activity. What needs the asset bytes — the field's vertex count equals the surface's, the
    * surface's triangles realize the domain's key — is verified at ingestion (SPEC §6).
    */
  def surfaces(m: Manifest): List[Problem] =
    val activities = provenanceIds(m, "activities").toSet
    // an asset renders as one thing: a geometry, a volume, or a vertex field. Two roles on one
    // asset silently keep the first rendition target and drop the rest, and several surfaces on
    // one asset would take one of their hemispheres for the whole rendition (SPEC §5).
    val assetRole: Map[String, String] =
      (m.underlays.map(u => u.asset -> "an underlay") ++
        m.resultFields.flatMap(f =>
          f.representations.collect {
            case r if r.kind == "volume" => r.asset -> s"a volume representation of '${f.id}'"
            case r if r.kind == "surface" =>
              r.asset -> s"the vertex-field asset of a surface representation of '${f.id}'"
          }
        )).toMap
    val onSurfaces = m.surfaces.zipWithIndex.flatMap { (s, si) =>
      val at = s"/surfaces/$si"
      val validHemisphere = SurfaceVertices.Hemispheres(s.hemisphere)
      val hemisphere = Option.when(!validHemisphere)(
        Problem(s"$at/hemisphere", s"hemisphere must be 'left' or 'right', not '${s.hemisphere}'")
      )
      val domain = surfaceSupport(m, s.domain) match
        case Some(SurfaceSupport.Vertices(p))
            if p.hemisphere != s.hemisphere && validHemisphere =>
          Some(Problem(
            s"$at/domain",
            s"surface is the ${s.hemisphere} hemisphere but domain '${s.domain}' is ${p.hemisphere}"
          ))
        case Some(SurfaceSupport.Vertices(_)) => None
        // the payload's own problems are reported under /domains/i/descriptor/payload; saying
        // "not a surface-vertices domain" here would be both wrong and a second voice on one fault
        case Some(SurfaceSupport.Unreadable) => None
        case Some(SurfaceSupport.NotSurfaceVertices) =>
          Some(Problem(
            s"$at/domain",
            s"domain '${s.domain}' is not a trusted surface-vertices domain"
          ))
        case None => None // undeclared: reported by the reference closure
      val shared = m.surfaces.take(si).find(_.asset == s.asset).map(first =>
        Problem(
          s"$at/asset",
          s"asset '${s.asset}' already backs surface '${first.id}'; an asset backs at most one surfaces[] entry"
        )
      )
      val role = assetRole.get(s.asset).map(what =>
        Problem(
          s"$at/asset",
          s"asset '${s.asset}' is also $what; an asset is a surface geometry or a volume or a vertex field, not two of them"
        )
      )
      List(hemisphere, domain, shared, role).flatten
    }
    val onReps = m.resultFields.zipWithIndex.flatMap((f, fi) =>
      f.representations.zipWithIndex.flatMap { (r, ri) =>
        val at = s"/resultFields/$fi/representations/$ri"
        if r.kind != "surface" then
          List(
            r.surface.map(_ =>
              Problem(s"$at/surface", "only a surface representation names a surface")
            ),
            r.hemisphere.map(_ =>
              Problem(s"$at/hemisphere", "only a surface representation names a hemisphere")
            )
          ).flatten
        else
          val surface = (r.surface, r.hemisphere) match
            case (None, _) =>
              Some(Problem(s"$at/surface", "a surface representation must name its surface"))
            case (_, None) =>
              Some(Problem(s"$at/hemisphere", "a surface representation must name its hemisphere"))
            case (Some(sid), Some(h)) =>
              m.surface(sid) match
                case None =>
                  Some(Problem(
                    s"$at/surface",
                    s"representation references undeclared surface '$sid'"
                  ))
                case Some(s) if s.hemisphere != h =>
                  Some(Problem(
                    s"$at/hemisphere",
                    s"representation is the $h hemisphere but surface '$sid' is ${s.hemisphere}"
                  ))
                case _ => None
          val derivation = r.derivation.filterNot(activities).map(d =>
            Problem(s"$at/derivation", s"derivation references unknown provenance activity '$d'")
          )
          List(surface, derivation).flatten
      }
    )
    onSurfaces ++ onReps

  /** Provenance edges connect entities, activities, assets, or result fields. */
  def provenanceEdges(m: Manifest): List[Problem] =
    val nodes =
      (provenanceIds(m, "entities") ++ provenanceIds(m, "activities") ++
        m.assets.map(_.id) ++ m.resultFields.map(_.id)).toSet
    m.raw.hcursor.downField("provenance").downField("edges").as[List[io.circe.Json]].toOption
      .getOrElse(Nil).zipWithIndex.flatMap { (e, i) =>
        List("from", "to").flatMap(end =>
          e.hcursor.get[String](end).toOption.filterNot(nodes).map(id =>
            Problem(s"/provenance/edges/$i/$end", s"edge references unknown node '$id'")
          )
        )
      }
