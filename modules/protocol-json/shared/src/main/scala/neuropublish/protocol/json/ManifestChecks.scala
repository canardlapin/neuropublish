package neuropublish.protocol.json

import neuropublish.protocol.SemanticId

/** Cross-field invariants from `protocol/SPEC.md` that JSON Schema cannot express. Every check runs
  * and every failure is reported with the pointer of the offending member.
  */
object ManifestChecks:

  def all(m: Manifest): List[Problem] =
    uniqueIds(m) ++ referenceClosure(m) ++ estimandClosure(m) ++ orders(m) ++ measures(m) ++
      sensitivity(m) ++ warningScopes(m) ++ openRecords(m) ++ provenanceEdges(m)

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

  /** Representations, underlays, and fields reference declared assets and domains. */
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
    reps ++ fieldDomains ++ under

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
