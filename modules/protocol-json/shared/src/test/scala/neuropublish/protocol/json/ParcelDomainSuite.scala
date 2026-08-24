package neuropublish.protocol.json

import munit.FunSuite

/** Language-neutral parcel-domain primitives: exact ordered finite identity and checked hard
  * assignments. These tests run on both JVM and Scala.js.
  */
class ParcelDomainSuite extends FunSuite:
  private val keys = Vector(
    "schaefer2018-7networks-lh-visual-1",
    "schaefer2018-7networks-lh-default-1",
    "schaefer2018-7networks-rh-visual-1",
    "schaefer2018-7networks-rh-default-1"
  )
  private val finite = FiniteIndexed.Payload(keys, "parcel-keys")

  private def i32le(values: Int*): Array[Byte] =
    values.iterator.flatMap { value =>
      Iterator(
        value.toByte,
        (value >>> 8).toByte,
        (value >>> 16).toByte,
        (value >>> 24).toByte
      )
    }.toArray

  private def assignment(
      coverage: String = "complete",
      empty: Vector[String] = Vector.empty
  ) = HardAssignment.Payload(
    "parcel-assignment",
    coverage,
    empty,
    "construct-schaefer-assignment"
  )

  test("finite-indexed identity pins exact Schaefer keys and their order") {
    val schema = TrustedSchemas.FiniteIndexedV1
    val bytes = FiniteIndexed.preimage(schema.id, schema.version, finite)
    assertEquals(
      bytes.take(8).toVector,
      Vector[Byte](78, 80, 85, 68, 79, 77, 49, 0)
    )
    assertEquals(
      FiniteIndexed.fingerprint(schema.id, schema.version, finite).render,
      "sha256:7297cb3eb45df97653c90bfb586eee3857912df6fe0bdc789dd6c7ab849c9394"
    )

    val reordered = finite.copy(elementKeys = keys.updated(0, keys(1)).updated(1, keys(0)))
    val foreignVariant = finite.copy(elementKeys =
      keys.updated(
        0,
        "schaefer2018-17networks-lh-visual-1"
      )
    )
    assertNotEquals(
      FiniteIndexed.fingerprint(schema.id, schema.version, reordered),
      FiniteIndexed.fingerprint(schema.id, schema.version, finite)
    )
    assertNotEquals(
      FiniteIndexed.fingerprint(schema.id, schema.version, foreignVariant),
      FiniteIndexed.fingerprint(schema.id, schema.version, finite)
    )
  }

  test("finite-indexed payload rejects duplicate and implicit keys") {
    val duplicate = io.circe.Json.obj(
      "ordering" -> io.circe.Json.fromString("explicit"),
      "elementKeys" -> io.circe.Json.arr(
        io.circe.Json.fromString("a"),
        io.circe.Json.fromString("a")
      ),
      "keysAsset" -> io.circe.Json.fromString("keys")
    )
    assertEquals(
      FiniteIndexed.readPayload("/descriptor/payload", duplicate).left.toOption.get.map(_.pointer),
      List("/descriptor/payload/elementKeys/1")
    )
    val implicitOrder = duplicate.mapObject(_.add("ordering", io.circe.Json.fromString("sorted")))
    assert(
      FiniteIndexed.readPayload("/descriptor/payload", implicitOrder).left.toOption.get
        .exists(_.pointer == "/descriptor/payload/ordering")
    )
  }

  test("a complete hard assignment is a certified surjection") {
    val checked = HardAssignment.checkBytes(
      "/domainMappings/0",
      assignment(),
      8,
      keys,
      i32le(0, 0, 1, 1, 2, 2, 3, 3)
    ).fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(checked.ordinals, Vector(0, 0, 1, 1, 2, 2, 3, 3))
    assertEquals(checked.emptyParcels, Vector.empty)
    assert(checked.surjective)
  }

  test("allow-empty preserves absent target keys in exact target order") {
    val declared = keys.drop(2)
    val checked = HardAssignment.checkBytes(
      "/domainMappings/0",
      assignment("allow-empty", declared),
      8,
      keys,
      i32le(0, 0, 1, 1, -1, -1, 0, 1)
    ).fold(ps => fail(Problem.render(ps)), identity)
    assertEquals(checked.emptyParcels, declared)
    assert(!checked.surjective)

    val reversed = assignment("allow-empty", declared.reverse)
    val problems = HardAssignment.checkBytes(
      "/domainMappings/0",
      reversed,
      8,
      keys,
      i32le(0, 0, 1, 1, -1, -1, 0, 1)
    ).left.toOption.get
    assertEquals(problems.map(_.pointer), List("/domainMappings/0/descriptor/payload/emptyParcels"))
  }

  test("assignment bytes fail closed on length, bounds, and false coverage") {
    val short = HardAssignment.checkBytes(
      "/domainMappings/0",
      assignment(),
      8,
      keys,
      i32le(0, 1)
    ).left.toOption.get
    assertEquals(short.map(_.pointer), List("/domainMappings/0/descriptor/payload/asset"))
    assert(short.head.message.contains("expected 32"), short.head.message)

    val bounds = HardAssignment.checkBytes(
      "/domainMappings/0",
      assignment(),
      8,
      keys,
      i32le(0, 1, 2, 3, 4, -2, 0, 1)
    ).left.toOption.get
    assertEquals(bounds.length, 2)
    assert(bounds.exists(_.message.contains("source ordinal 4")), bounds)
    assert(bounds.exists(_.message.contains("source ordinal 5")), bounds)

    val missing = HardAssignment.checkBytes(
      "/domainMappings/0",
      assignment(),
      8,
      keys,
      i32le(0, 0, 1, 1, 2, 2, 0, 1)
    ).left.toOption.get
    assertEquals(missing.map(_.pointer), List("/domainMappings/0/descriptor/payload/coverage"))
    assert(missing.head.message.contains(keys.last), missing.head.message)
  }
