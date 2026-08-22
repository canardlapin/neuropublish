package neuropublish.protocol.json

import neuropublish.protocol.Sha256

/** Admission of raw `manifest.json` bytes per ADR 0001: UTF-8 without BOM, exactly one root object,
  * no trailing content, no duplicate object keys, only Unicode scalar values, only finite numbers
  * (JSON text cannot express NaN/Infinity, so a well-formed parse suffices).
  *
  * The profile is checked on the bytes, before any parser normalizes them, so that the stored bytes
  * and the parsed form can never diverge.
  */
object ByteProfile:

  final case class Violation(offset: Int, message: String):
    def render: String = s"byte $offset: $message"

  /** Returns the manifest digest if the bytes meet the profile. */
  def admit(bytes: Array[Byte]): Either[List[Violation], Sha256] =
    val errors = List.newBuilder[Violation]
    if bytes.length >= 3 && (bytes(0) & 0xff) == 0xef && (bytes(1) & 0xff) == 0xbb &&
      (bytes(2) & 0xff) == 0xbf
    then errors += Violation(0, "UTF-8 byte order mark is not allowed")
    Utf8.validate(bytes).foreach(o => errors += Violation(o, "invalid UTF-8 sequence"))
    val text = new String(bytes, "UTF-8")
    errors ++= Scanner.scan(text).map(v => v.copy(offset = byteOffset(text, v.offset)))
    val es = errors.result()
    if es.isEmpty then Right(Sha256.of(bytes)) else Left(es)

  /** UTF-8 byte offset of a UTF-16 char index, so every Violation reports bytes. */
  private def byteOffset(text: String, charIndex: Int): Int =
    var bytes = 0
    var i = 0
    val end = math.min(charIndex, text.length)
    while i < end do
      val c = text.charAt(i)
      if c < 0x80 then bytes += 1
      else if c < 0x800 then bytes += 2
      else if Character.isHighSurrogate(c) && i + 1 < text.length then { bytes += 4; i += 1 }
      else bytes += 3
      i += 1
    bytes

  /** Strict UTF-8 validation (rejects overlongs, surrogates, > U+10FFFF). Returns first bad offset.
    */
  private[json] object Utf8:
    def validate(b: Array[Byte]): Option[Int] =
      var i = 0
      val n = b.length
      while i < n do
        val c = b(i) & 0xff
        if c < 0x80 then i += 1
        else
          val (need, min) =
            if (c & 0xe0) == 0xc0 then (1, 0x80)
            else if (c & 0xf0) == 0xe0 then (2, 0x800)
            else if (c & 0xf8) == 0xf0 then (3, 0x10000)
            else return Some(i)
          if i + need >= n then return Some(i)
          var cp = c & (0x3f >> need)
          var k = 1
          while k <= need do
            val cc = b(i + k) & 0xff
            if (cc & 0xc0) != 0x80 then return Some(i + k)
            cp = (cp << 6) | (cc & 0x3f)
            k += 1
          if cp < min || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) then return Some(i)
          i += need + 1
      None

  /** A small structural JSON scanner. It does not build a tree; it checks that the text is exactly
    * one JSON object, tracks object keys per nesting level to reject duplicates, and rejects
    * unpaired surrogate escapes.
    */
  private[json] object Scanner:
    /** Nesting limit; deeper manifests are rejected rather than risking a stack overflow. */
    val MaxDepth = 512

    def scan(s: String): List[Violation] =
      val out = List.newBuilder[Violation]
      depth = 0
      var i = skipWs(s, 0)
      if i >= s.length || s.charAt(i) != '{' then
        out += Violation(i, "manifest must be exactly one JSON object")
        return out.result()
      i = value(s, i, out)
      i = skipWs(s, i)
      if i < s.length then out += Violation(i, "trailing content after the root object")
      out.result()

    private def skipWs(s: String, from: Int): Int =
      var i = from
      while i < s.length &&
        (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' ||
          s.charAt(i) == '\t')
      do i += 1
      i

    private var depth = 0

    private def fail(
        out: collection.mutable.Builder[Violation, List[Violation]],
        i: Int,
        m: String
    ): Int =
      out += Violation(i, m); Int.MaxValue

    private def enter(
        out: collection.mutable.Builder[Violation, List[Violation]],
        i: Int
    ): Boolean =
      depth += 1
      if depth > MaxDepth then { fail(out, i, s"nesting deeper than $MaxDepth"); false }
      else true

    private def value(
        s: String,
        from: Int,
        out: collection.mutable.Builder[Violation, List[Violation]]
    ): Int =
      val i = skipWs(s, from)
      if i >= s.length then return fail(out, i, "unexpected end of input")
      s.charAt(i) match
        case '{' => if enter(out, i) then { val r = obj(s, i + 1, out); depth -= 1; r }
          else Int.MaxValue
        case '[' => if enter(out, i) then { val r = arr(s, i + 1, out); depth -= 1; r }
          else Int.MaxValue
        case '"' => string(s, i + 1, out)._1
        case 't' => literal(s, i, "true", out)
        case 'f' => literal(s, i, "false", out)
        case 'n' => literal(s, i, "null", out)
        case c if c == '-' || c.isDigit => number(s, i, out)
        case c => fail(out, i, s"unexpected character '$c'")

    private def literal(
        s: String,
        i: Int,
        lit: String,
        out: collection.mutable.Builder[Violation, List[Violation]]
    ): Int =
      if s.startsWith(lit, i) then i + lit.length else fail(out, i, s"expected $lit")

    private def number(
        s: String,
        from: Int,
        out: collection.mutable.Builder[Violation, List[Violation]]
    ): Int =
      var i = from
      if s.charAt(i) == '-' then i += 1
      val digitsStart = i
      while i < s.length && s.charAt(i).isDigit do i += 1
      if i == digitsStart then return fail(out, i, "digit expected")
      if s.charAt(digitsStart) == '0' && i - digitsStart > 1 then
        return fail(out, digitsStart, "leading zeros are not allowed")
      if i < s.length && s.charAt(i) == '.' then
        i += 1
        val fs = i
        while i < s.length && s.charAt(i).isDigit do i += 1
        if i == fs then return fail(out, i, "fraction digits expected")
      if i < s.length && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
        i += 1
        if i < s.length && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
        val es = i
        while i < s.length && s.charAt(i).isDigit do i += 1
        if i == es then return fail(out, i, "exponent digits expected")
      i

    /** Returns (index after closing quote, decoded string). */
    private def string(
        s: String,
        from: Int,
        out: collection.mutable.Builder[Violation, List[Violation]]
    ): (Int, String) =
      val sb = new StringBuilder
      var i = from
      while i < s.length do
        val c = s.charAt(i)
        if c == '"' then return (i + 1, sb.toString)
        else if c < 0x20 then return (fail(out, i, "control character in string"), "")
        else if c == '\\' then
          if i + 1 >= s.length then return (fail(out, i, "unterminated escape"), "")
          s.charAt(i + 1) match
            case '"' => sb += '"'; i += 2
            case '\\' => sb += '\\'; i += 2
            case '/' => sb += '/'; i += 2
            case 'b' => sb += '\b'; i += 2
            case 'f' => sb += '\f'; i += 2
            case 'n' => sb += '\n'; i += 2
            case 'r' => sb += '\r'; i += 2
            case 't' => sb += '\t'; i += 2
            case 'u' =>
              val hi = hex4(s, i + 2)
              if hi < 0 then return (fail(out, i, "bad \\u escape"), "")
              if hi >= 0xd800 && hi <= 0xdbff then
                val lo = if s.startsWith("\\u", i + 6) then hex4(s, i + 8) else -1
                if lo < 0xdc00 || lo > 0xdfff then
                  return (fail(out, i, "lone high surrogate escape"), "")
                sb += hi.toChar += lo.toChar; i += 12
              else if hi >= 0xdc00 && hi <= 0xdfff then
                return (fail(out, i, "lone low surrogate escape"), "")
              else
                sb += hi.toChar
                i += 6
            case e => return (fail(out, i, s"bad escape '\\$e'"), "")
        else
          sb += c
          i += 1
      (fail(out, i, "unterminated string"), "")

    private def hex4(s: String, at: Int): Int =
      if at + 4 > s.length then -1
      else
        var v = 0
        var k = 0
        while k < 4 do
          val c = s.charAt(at + k)
          val d =
            if c >= '0' && c <= '9' then c - '0'
            else if c >= 'a' && c <= 'f' then c - 'a' + 10
            else if c >= 'A' && c <= 'F' then c - 'A' + 10
            else return -1
          v = (v << 4) | d
          k += 1
        v

    private def obj(
        s: String,
        from: Int,
        out: collection.mutable.Builder[Violation, List[Violation]]
    ): Int =
      val seen = collection.mutable.HashSet.empty[String]
      var i = skipWs(s, from)
      if i < s.length && s.charAt(i) == '}' then return i + 1
      while true do
        i = skipWs(s, i)
        if i >= s.length || s.charAt(i) != '"' then return fail(out, i, "object key expected")
        val keyAt = i
        val (after, key) = string(s, i + 1, out)
        if after == Int.MaxValue then return after
        if !seen.add(key) then out += Violation(keyAt, s"duplicate object key \"$key\"")
        i = skipWs(s, after)
        if i >= s.length || s.charAt(i) != ':' then return fail(out, i, "':' expected")
        i = value(s, i + 1, out)
        if i == Int.MaxValue then return i
        i = skipWs(s, i)
        if i >= s.length then return fail(out, i, "unterminated object")
        s.charAt(i) match
          case ',' => i += 1
          case '}' => return i + 1
          case _ => return fail(out, i, "',' or '}' expected")
      i

    private def arr(
        s: String,
        from: Int,
        out: collection.mutable.Builder[Violation, List[Violation]]
    ): Int =
      var i = skipWs(s, from)
      if i < s.length && s.charAt(i) == ']' then return i + 1
      while true do
        i = value(s, i, out)
        if i == Int.MaxValue then return i
        i = skipWs(s, i)
        if i >= s.length then return fail(out, i, "unterminated array")
        s.charAt(i) match
          case ',' => i += 1
          case ']' => return i + 1
          case _ => return fail(out, i, "',' or ']' expected")
      i
