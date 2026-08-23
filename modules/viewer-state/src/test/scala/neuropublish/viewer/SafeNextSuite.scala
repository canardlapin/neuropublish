package neuropublish.viewer

import munit.FunSuite

class SafeNextSuite extends FunSuite:
  private val origin = "https://np.example"

  test("same-origin paths are accepted") {
    assertEquals(SafeNext.accept("/w/x", origin), Some("/w/x"))
    assertEquals(SafeNext.accept("/w/x/p/y?view=1", origin), Some("/w/x/p/y?view=1"))
    assertEquals(SafeNext.accept("  /w/x  ", origin), Some("/w/x"))
    assertEquals(SafeNext.accept("https://np.example/w/x", origin), Some("/w/x"))
  }

  test("protocol-relative and foreign URLs are refused") {
    assertEquals(SafeNext.accept("//evil.com", origin), None)
    assertEquals(SafeNext.accept("/\\evil.com", origin), None)
    assertEquals(SafeNext.accept("https://evil.com", origin), None)
    assertEquals(SafeNext.accept("https://np.example.evil.com/w/x", origin), None)
    assertEquals(SafeNext.accept("http://np.example/w/x", origin), None)
    assertEquals(SafeNext.accept("javascript:alert(1)", origin), None)
    assertEquals(SafeNext.accept("w/x", origin), None)
    assertEquals(SafeNext.accept("", origin), None)
  }

  test("control characters anywhere are refused") {
    assertEquals(SafeNext.accept("/\t//evil.com", origin), None)
    assertEquals(SafeNext.accept("/w/x", origin), None)
    assertEquals(SafeNext.accept("//evil.com", origin), None)
  }
