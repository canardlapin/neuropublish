package neuropublish.protocol.json

import munit.FunSuite

class ByteProfileSuite extends FunSuite:
  private def bytes(s: String) = s.getBytes("UTF-8")
  private def reject(s: String, needle: String)(using munit.Location) =
    ByteProfile.admit(bytes(s)) match
      case Left(vs) =>
        assert(vs.exists(_.message.contains(needle)), vs.map(_.render).mkString("; "))
      case Right(_) => fail(s"expected rejection containing '$needle'")

  test("admits a plain object and returns its byte digest") {
    val text = """{"core":"0.1","title":"t","n":[1,2.5,-3e2],"ok":true,"nil":null,"s":"é🧠"}"""
    val d = ByteProfile.admit(bytes(text))
    assert(d.isRight)
    assertEquals(d.map(_.hex), Right(neuropublish.protocol.Sha256.of(bytes(text)).hex))
  }

  test("rejects a byte order mark") {
    reject("﻿{}", "byte order mark")
  }
  test("rejects a non-object root and trailing content") {
    reject("[1,2]", "exactly one JSON object")
    reject("""{"a":1} {"b":2}""", "trailing content")
    reject("""{"a":1}x""", "trailing content")
  }
  test("rejects duplicate keys at any depth") {
    reject("""{"a":1,"a":2}""", "duplicate object key \"a\"")
    reject("""{"a":{"b":1,"c":[{"b":1,"b":2}]}}""", "duplicate object key \"b\"")
  }
  test("does not confuse equal keys in different objects") {
    assert(ByteProfile.admit(bytes("""{"a":{"k":1},"b":{"k":2}}""")).isRight)
  }
  test("rejects lone surrogate escapes") {
    reject("""{"a":"\ud83e"}""", "lone high surrogate")
    reject("""{"a":"\udde0"}""", "lone low surrogate")
  }
  test("rejects invalid UTF-8 bytes") {
    val bad = bytes("""{"a":"""") ++ Array(0xc0.toByte, 0xaf.toByte) ++ bytes("\"}")
    ByteProfile.admit(bad) match
      case Left(vs) => assert(vs.exists(_.message.contains("invalid UTF-8")))
      case Right(_) => fail("overlong encoding admitted")
  }
  test("rejects malformed numbers") {
    reject("""{"a":01}""", "leading zeros")
    reject("""{"a":1.}""", "fraction digits")
    reject("""{"a":NaN}""", "unexpected character")
  }
  test("whitespace changes the digest, not admissibility") {
    val a = ByteProfile.admit(bytes("""{"a":1}"""))
    val b = ByteProfile.admit(bytes("""{ "a" : 1 }"""))
    assert(a.isRight && b.isRight)
    assertNotEquals(a.map(_.hex), b.map(_.hex))
  }
