package neuropublish.protocol

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

class Sha256Suite extends ScalaCheckSuite:

  private def utf8(s: String) = s.getBytes("UTF-8")

  test("FIPS 180-4 vectors") {
    assertEquals(
      Sha256.of(Array.empty[Byte]).hex,
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
    assertEquals(
      Sha256.of(utf8("abc")).hex,
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
    assertEquals(
      Sha256.of(utf8("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")).hex,
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    )
    assertEquals(
      Sha256.of(Array.fill[Byte](1_000_000)('a'.toByte)).hex,
      "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
    )
  }

  test("parse accepts prefixed and bare lowercase hex, rejects the rest") {
    val hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    assertEquals(Sha256.parse(s"sha256:$hex").map(_.hex), Right(hex))
    assertEquals(Sha256.parse(hex).map(_.render), Right(s"sha256:$hex"))
    assert(Sha256.parse(hex.toUpperCase).isLeft)
    assert(Sha256.parse(hex.take(63)).isLeft)
  }

  property("digest length is always 64 hex chars and parses") {
    forAll { (bytes: Array[Byte]) =>
      val d = Sha256.of(bytes)
      d.hex.length == 64 && Sha256.parse(d.render).isRight
    }
  }

  property("padding boundary: lengths 55..65 all digest without error") {
    forAll(org.scalacheck.Gen.choose(55, 65)) { n =>
      Sha256.of(Array.fill[Byte](n)(0x61)).hex.length == 64
    }
  }

  test("semantic id grammar") {
    assert(SemanticId.parse("org.neuropublish.measure/t-statistic").isRight)
    assert(SemanticId.parse("org.bbuchsbaum.fmrigds/reducer/meta-random-effects").isRight)
    assertEquals(
      SemanticId.parse("org.neuropublish.measure/t-statistic").map(_.namespace),
      Right("org.neuropublish.measure")
    )
    assert(SemanticId.parse("t-statistic").isLeft)
    assert(SemanticId.parse("Org.Neuropublish/T").isLeft)
    assert(SemanticId.parse("org.neuropublish/").isLeft)
  }
