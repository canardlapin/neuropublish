package neuropublish.npub

import cats.effect.{IO, Ref}
import fs2.{Pipe, Stream}
import fs2.hashing.HashAlgorithm
import neuropublish.protocol.Sha256

/** Streaming SHA-256 for the CLI: a file of any size is hashed in bounded memory. */
object Hashing:
  /** Folds a byte stream to its (digest, byte count); emits exactly one element. */
  def sha256: Pipe[IO, Byte, (Sha256, Long)] = in =>
    Stream.eval(Ref[IO].of(0L)).flatMap { count =>
      in.chunks.evalTap(c => count.update(_ + c.size)).unchunks
        .through(fs2.hashing.Hashing[IO].hash(HashAlgorithm.SHA256))
        .evalMap { h =>
          val hex = h.bytes.toArray.map(b => f"${b & 0xff}%02x").mkString
          count.get.map(n => (Sha256.unsafe(hex), n))
        }
    }
