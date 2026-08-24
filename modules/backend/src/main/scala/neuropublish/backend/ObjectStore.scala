package neuropublish.backend

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.time.Instant
import neuropublish.api.UploadInstruction
import neuropublish.protocol.Sha256
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** What a HEAD says about a stored object. `checksumSha256` is the provider's own checksum evidence
  * (S3 `x-amz-checksum-sha256`, base64) when the object was uploaded with one.
  */
final case class ObjectStat(size: Long, lastModified: Instant, checksumSha256: Option[String])

/** Signed direct-to-store transfers (architecture: "Binary data bypasses the application server").
  * Only stores whose bytes live outside the control plane offer this; the local-filesystem store
  * does not, and the control plane then proxies uploads itself.
  */
trait Presigning:
  /** A PUT the client can perform with no credentials, into the session's staging area
    * ([[ObjectStore.stagingKey]]) — never a committed content-addressed key, so no client can
    * overwrite bytes another revision relies on. `headers` are part of the signature and must be
    * sent verbatim: Content-Length and `x-amz-checksum-sha256` are signed, so a provider that
    * honours the checksum refuses mismatching bytes at upload; commit verifies regardless
    * ([[ObjectStore.verifyStaged]]).
    */
  def presignPut(
      session: String,
      digest: Sha256,
      size: Long,
      mediaType: String,
      ttl: FiniteDuration
  ): IO[UploadInstruction]

  /** A staged PUT for the manifest: the size is signed (the client sends Content-Length anyway);
    * the digest is verified by hashing the bytes at commit, since the publisher CLI sends this URL
    * with no extra headers.
    */
  def presignManifestPut(session: String, digest: Sha256, size: Long, ttl: FiniteDuration)
      : IO[String]
  def presignGet(digest: Sha256, ttl: FiniteDuration): IO[String]

  /** Signed GET for a non-content-addressed key (renditions). */
  def presignGetKey(key: String, ttl: FiniteDuration): IO[String]

/** Content-addressed immutable object storage, keyed `sha256/<2 hex>/<64 hex>`. */
trait ObjectStore:
  /** Store bytes under their digest; rejects bytes whose digest differs from `expected`. */
  def put(expected: Sha256, bytes: Array[Byte]): IO[Either[String, Unit]]
  def exists(digest: Sha256): IO[Boolean]
  def stat(digest: Sha256): IO[Option[ObjectStat]]
  def size(digest: Sha256): IO[Option[Long]] = stat(digest).map(_.map(_.size))
  def get(digest: Sha256): IO[Option[Array[Byte]]]
  def delete(digest: Sha256): IO[Unit]

  /** Every stored digest with its stat (garbage collection). */
  def list: fs2.Stream[IO, (Sha256, ObjectStat)]

  /** Copy the object to `target` without holding it in memory; false when absent. */
  def getToFile(digest: Sha256, target: Path): IO[Boolean]

  /** Commit-time verification of a committed object: the size must equal `declaredSize`. Every
    * write to a committed key is made by the server after hashing ([[put]], [[promote]]), so the
    * digest holds by construction.
    */
  def verify(digest: Sha256, declaredSize: Long): IO[Either[String, Unit]] =
    stat(digest).map {
      case None => Left(s"${digest.render} was not uploaded")
      case Some(st) if st.size != declaredSize =>
        Left(s"${digest.render}: ${st.size} bytes stored, $declaredSize declared")
      case Some(_) => Right(())
    }

  /** Present when the store can sign direct transfers. */
  def presigning: Option[Presigning] = None

  // ---- session staging (direct-transfer mode): clients write here, never to committed keys

  /** The only key a client's signed PUT may write: scoped to its upload session. */
  def stagingKey(session: String, digest: Sha256): String = s"staging/$session/${digest.hex}"
  def stagedStat(session: String, digest: Sha256): IO[Option[ObjectStat]] =
    statBlob(stagingKey(session, digest))
  def getStaged(session: String, digest: Sha256): IO[Option[Array[Byte]]] =
    getBlob(stagingKey(session, digest))

  /** Size and SHA-256 of a staged object: provider checksum evidence when the store trusts it,
    * otherwise one streamed pass over the bytes. Left = why it cannot be committed.
    */
  def verifyStaged(session: String, digest: Sha256, declaredSize: Long): IO[Either[String, Unit]] =
    stagedStat(session, digest).flatMap {
      case None => IO.pure(Left(s"${digest.render} was not uploaded"))
      case Some(st) if st.size != declaredSize =>
        IO.pure(Left(s"${digest.render}: ${st.size} bytes stored, $declaredSize declared"))
      case Some(_) =>
        getStaged(session, digest).map {
          case None => Left(s"${digest.render} was not uploaded")
          case Some(bytes) =>
            val actual = Sha256.of(bytes)
            if actual.hex == digest.hex then Right(())
            else Left(s"${digest.render}: stored bytes hash to ${actual.render}")
        }
    }

  /** Server-side copy of a verified staged object onto its committed key (a no-op when the
    * committed object already exists: identical bytes by digest).
    */
  def promote(session: String, digest: Sha256): IO[Unit] =
    exists(digest).flatMap {
      case true => IO.unit
      case false =>
        getStaged(session, digest).flatMap {
          case None => IO.raiseError(IllegalStateException(s"${digest.render} is not staged"))
          case Some(bytes) =>
            put(digest, bytes).flatMap(r => IO.fromEither(r.leftMap(IllegalStateException(_))))
        }
    }

  /** Drop everything a session staged (after commit, or by gc for an abandoned session). */
  def deleteStaging(session: String): IO[Unit] =
    listBlobs(s"staging/$session/").evalMap(deleteBlob).compile.drain

  /** Non-content-addressed blobs under `key` (derived renditions, staging). */
  def putBlob(key: String, bytes: Array[Byte], mediaType: String): IO[Unit]
  def getBlob(key: String): IO[Option[Array[Byte]]]
  def statBlob(key: String): IO[Option[ObjectStat]]
  def blobExists(key: String): IO[Boolean] = statBlob(key).map(_.isDefined)
  def deleteBlob(key: String): IO[Unit]

  /** Keys under `prefix`. */
  def listBlobs(prefix: String): fs2.Stream[IO, String]

object ObjectStore:
  def key(d: Sha256): String = s"sha256/${d.hex.take(2)}/${d.hex}"

  /** `<root>/sha256/<2 hex>/<64 hex>` — the normalized bundle layout. Blobs live under
    * `<root>/blobs/<key>`.
    */
  final class LocalFs(root: Path) extends ObjectStore:
    private def path(d: Sha256) = root / "sha256" / d.hex.take(2) / d.hex
    private def blobPath(key: String) = root / "blobs" / Path(key)

    /** Write via a unique temp file and atomic move; a concurrent identical write is harmless. */
    private def writeAtomic(p: Path, bytes: Array[Byte]): IO[Either[String, Unit]] =
      Files[IO].createDirectories(p.parent.get) *>
        Files[IO].exists(p).flatMap {
          case true => IO.pure(Right(()))
          case false =>
            val tmp = p.parent.get /
              s"${p.fileName}.${java.util.UUID.randomUUID().toString.take(8)}.part"
            (fs2.Stream.emits(bytes).through(Files[IO].writeAll(tmp)).compile.drain *>
              Files[IO].exists(p).flatMap {
                case true => Files[IO].delete(tmp) // a concurrent put won; content is identical
                case false => Files[IO].move(tmp, p)
              }).as(Right(())).handleErrorWith(_ =>
              Files[IO].deleteIfExists(tmp) *>
                Files[IO].exists(p).map(if _ then Right(()) else Left("write failed"))
            )
        }

    def put(expected: Sha256, bytes: Array[Byte]): IO[Either[String, Unit]] =
      val actual = Sha256.of(bytes)
      if actual.hex != expected.hex then
        IO.pure(Left(s"digest mismatch: declared ${expected.render}, received ${actual.render}"))
      else writeAtomic(path(expected), bytes)
    private def statPath(p: Path): IO[Option[ObjectStat]] =
      (Files[IO].size(p), Files[IO].getLastModifiedTime(p)).mapN((sz, t) =>
        Some(ObjectStat(sz, Instant.ofEpochMilli(t.toMillis), None))
      ).recover { case _: java.nio.file.NoSuchFileException => None }
    def exists(digest: Sha256): IO[Boolean] = Files[IO].exists(path(digest))
    def stat(digest: Sha256): IO[Option[ObjectStat]] = statPath(path(digest))
    def get(digest: Sha256): IO[Option[Array[Byte]]] = JsonFiles.readBytes(path(digest))
    def getToFile(digest: Sha256, target: Path): IO[Boolean] =
      Files[IO].copy(
        path(digest),
        target,
        fs2.io.file.CopyFlags(fs2.io.file.CopyFlag.ReplaceExisting)
      )
        .as(true).recover { case _: java.nio.file.NoSuchFileException => false }
    def delete(digest: Sha256): IO[Unit] = Files[IO].deleteIfExists(path(digest)).void
    def list: fs2.Stream[IO, (Sha256, ObjectStat)] =
      val base = root / "sha256"
      fs2.Stream.eval(Files[IO].exists(base)).flatMap {
        case false => fs2.Stream.empty
        case true =>
          Files[IO].walk(base).filter(_.fileName.toString.matches("[0-9a-f]{64}"))
            .evalFilter(p => Files[IO].isRegularFile(p))
            .map(p => Sha256.unsafe(p.fileName.toString))
            .evalMap(d => stat(d).map(_.map(d -> _))).unNone
      }

    def putBlob(key: String, bytes: Array[Byte], mediaType: String): IO[Unit] =
      // blobs are overwritable (a rendition re-derived after a retry)
      JsonFiles.writeBytes(blobPath(key), bytes)
    def getBlob(key: String): IO[Option[Array[Byte]]] = JsonFiles.readBytes(blobPath(key))
    def statBlob(key: String): IO[Option[ObjectStat]] = statPath(blobPath(key))
    def deleteBlob(key: String): IO[Unit] = Files[IO].deleteIfExists(blobPath(key)).void
    def listBlobs(prefix: String): fs2.Stream[IO, String] =
      val base = root / "blobs"
      fs2.Stream.eval(Files[IO].exists(base)).flatMap {
        case false => fs2.Stream.empty
        case true =>
          Files[IO].walk(base).evalFilter(p => Files[IO].isRegularFile(p))
            .map(p => base.relativize(p).toString.replace(java.io.File.separatorChar, '/'))
            .filter(_.startsWith(prefix))
      }

  /** S3 configuration from the environment: `NP_S3_BUCKET` selects S3 mode; `NP_S3_ENDPOINT` (MinIO
    * or any S3-compatible service; unset = AWS), `NP_S3_PUBLIC_ENDPOINT` when presigned URLs need a
    * browser-reachable origin distinct from the service-network endpoint, `NP_S3_REGION` (default
    * us-east-1), `NP_S3_ACCESS_KEY` / `NP_S3_SECRET_KEY` (unset = the SDK default credential
    * chain), `NP_S3_PATH_STYLE=true` for endpoints without virtual-host buckets (MinIO).
    */
  final case class S3Config(
      bucket: String,
      endpoint: Option[String],
      region: String,
      accessKey: Option[String],
      secretKey: Option[String],
      pathStyle: Boolean,
      publicEndpoint: Option[String] = None
  ):
    /** Provider-echoed `x-amz-checksum-sha256` is evidence only on AWS itself; an S3-compatible
      * endpoint may store the header without checking it, so commit hashes the bytes instead.
      */
    def trustProviderChecksum: Boolean = endpoint.isEmpty

    /** The endpoint embedded in presigned URLs. The data client continues to use [[endpoint]]. */
    def presigningEndpoint: Option[String] = publicEndpoint.orElse(endpoint)
  object S3Config:
    def fromEnv(env: Map[String, String]): Option[S3Config] =
      env.get("NP_S3_BUCKET").map(_.trim).filter(_.nonEmpty).map(bucket =>
        S3Config(
          bucket,
          env.get("NP_S3_ENDPOINT").map(_.trim).filter(_.nonEmpty),
          env.getOrElse("NP_S3_REGION", "us-east-1"),
          env.get("NP_S3_ACCESS_KEY").filter(_.nonEmpty),
          env.get("NP_S3_SECRET_KEY").filter(_.nonEmpty),
          env.get("NP_S3_PATH_STYLE").exists(v => v == "true" || v == "1"),
          env.get("NP_S3_PUBLIC_ENDPOINT").map(_.trim).filter(_.nonEmpty)
        )
      )

  def s3(config: S3Config): Resource[IO, S3] = S3.resource(config)

  /** AWS SDK v2 async client over an S3-compatible bucket. Objects are keyed `sha256/<2>/<64>`,
    * blobs by their key. Presigned PUTs carry `x-amz-checksum-sha256`, so a provider that honours
    * it refuses mismatching bytes at upload; [[verify]] re-checks at commit from the HEAD checksum
    * when reported and by streaming the object once otherwise.
    */
  final class S3 private[ObjectStore] (
      client: software.amazon.awssdk.services.s3.S3AsyncClient,
      presigner: software.amazon.awssdk.services.s3.presigner.S3Presigner,
      bucket: String,
      trustProviderChecksum: Boolean
  ) extends ObjectStore:
    import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
    import software.amazon.awssdk.services.s3.model.*
    import software.amazon.awssdk.services.s3.presigner.model.*
    import java.util.concurrent.CompletableFuture

    private def io[A](f: => CompletableFuture[A]): IO[A] = IO.fromCompletableFuture(IO(f))
    private def notFound[A](a: IO[A]): IO[Option[A]] =
      a.map(Some(_)).recover {
        case _: NoSuchKeyException => None
        case e: S3Exception if e.statusCode() == 404 => None
      }

    private def b64(d: Sha256): String =
      java.util.Base64.getEncoder.encodeToString(hexBytes(d.hex))
    private def hexBytes(hex: String): Array[Byte] =
      hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

    private def head(key: String): IO[Option[ObjectStat]] =
      notFound(io(client.headObject(
        HeadObjectRequest.builder().bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED)
          .build()
      ))).map(_.map(h =>
        ObjectStat(h.contentLength(), h.lastModified(), Option(h.checksumSHA256()))
      ))
    private def readKey(key: String): IO[Option[Array[Byte]]] =
      notFound(io(client.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build(),
        AsyncResponseTransformer.toBytes[GetObjectResponse]()
      ))).map(_.map(_.asByteArray()))
    private def write(key: String, bytes: Array[Byte], mediaType: String, sha: Option[Sha256]) =
      val b = PutObjectRequest.builder().bucket(bucket).key(key).contentType(mediaType)
        .contentLength(bytes.length.toLong)
      io(client.putObject(
        sha.fold(b)(d => b.checksumSHA256(b64(d))).build(),
        AsyncRequestBody.fromBytes(bytes)
      )).void
    private def remove(key: String): IO[Unit] =
      io(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())).void
    private def keys(prefix: String): fs2.Stream[IO, S3Object] =
      fs2.Stream.unfoldLoopEval(Option.empty[String]) { token =>
        val b = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
        io(client.listObjectsV2(token.fold(b)(b.continuationToken).build())).map { r =>
          val next = Option(r.nextContinuationToken()).filter(_ => r.isTruncated)
          (r.contents().asScala.toList, next.map(Some(_)))
        }
      }.flatMap(fs2.Stream.emits)

    def put(expected: Sha256, bytes: Array[Byte]): IO[Either[String, Unit]] =
      val actual = Sha256.of(bytes)
      if actual.hex != expected.hex then
        IO.pure(Left(s"digest mismatch: declared ${expected.render}, received ${actual.render}"))
      else write(key(expected), bytes, "application/octet-stream", Some(expected)).as(Right(()))
    def exists(digest: Sha256): IO[Boolean] = head(key(digest)).map(_.isDefined)
    def stat(digest: Sha256): IO[Option[ObjectStat]] = head(key(digest))
    def get(digest: Sha256): IO[Option[Array[Byte]]] = readKey(key(digest))
    def getToFile(digest: Sha256, target: Path): IO[Boolean] =
      notFound(io(client.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key(digest)).build(),
        AsyncResponseTransformer.toFile[GetObjectResponse](target.toNioPath)
      ))).map(_.isDefined)
    def delete(digest: Sha256): IO[Unit] = remove(key(digest))

    /** Stream `k` once and hash it. */
    private def hashKey(k: String): IO[String] =
      io(client.getObject(
        GetObjectRequest.builder().bucket(bucket).key(k).build(),
        AsyncResponseTransformer.toPublisher[GetObjectResponse]()
      )).flatMap { pub =>
        fs2.interop.flow.fromPublisher[IO](
          org.reactivestreams.FlowAdapters.toFlowPublisher(pub),
          64
        )
          .fold(java.security.MessageDigest.getInstance("SHA-256")) { (md, bb) =>
            md.update(bb); md
          }
          .compile.lastOrError
          .map(md => md.digest().map(b => "%02x".format(b & 0xff)).mkString)
      }

    override def verifyStaged(session: String, digest: Sha256, declaredSize: Long)
        : IO[Either[String, Unit]] =
      val k = stagingKey(session, digest)
      head(k).flatMap {
        case None => IO.pure(Left(s"${digest.render} was not uploaded"))
        case Some(st) if st.size != declaredSize =>
          IO.pure(Left(s"${digest.render}: ${st.size} bytes stored, $declaredSize declared"))
        case Some(ObjectStat(_, _, Some(sum))) if trustProviderChecksum =>
          IO.pure(
            if sum == b64(digest) then Right(())
            else Left(s"${digest.render}: stored checksum differs from the declared digest")
          )
        case Some(_) =>
          hashKey(k).map(hex =>
            if hex == digest.hex then Right(())
            else Left(s"${digest.render}: stored bytes hash to sha256:$hex")
          )
      }

    /** Server-side copy: the committed key is written only by the control plane. */
    override def promote(session: String, digest: Sha256): IO[Unit] =
      exists(digest).flatMap {
        case true => IO.unit
        case false =>
          io(client.copyObject(
            CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(stagingKey(session, digest))
              .destinationBucket(bucket).destinationKey(key(digest)).build()
          )).void
      }
    def list: fs2.Stream[IO, (Sha256, ObjectStat)] =
      keys("sha256/").flatMap { o =>
        val hex = o.key().split('/').last
        if hex.matches("[0-9a-f]{64}") then
          fs2.Stream.emit(Sha256.unsafe(hex) -> ObjectStat(o.size(), o.lastModified(), None))
        else fs2.Stream.empty
      }

    override def presigning: Option[Presigning] = Some(new Presigning {
      private def dur(ttl: FiniteDuration) = java.time.Duration.ofMillis(ttl.toMillis)
      def presignPut(
          session: String,
          digest: Sha256,
          size: Long,
          mediaType: String,
          ttl: FiniteDuration
      ): IO[UploadInstruction] = IO.blocking {
        val req = PutObjectRequest.builder().bucket(bucket).key(stagingKey(session, digest))
          .contentLength(size).contentType(mediaType).checksumSHA256(b64(digest)).build()
        val p = presigner.presignPutObject(
          PutObjectPresignRequest.builder().signatureDuration(dur(ttl)).putObjectRequest(
            req
          ).build()
        )
        val headers = p.signedHeaders().asScala.toMap
          .filterNot(_._1.equalsIgnoreCase("host"))
          .map((k, v) => k -> v.asScala.mkString(","))
        UploadInstruction(digest.render, p.url().toString, "PUT", headers)
      }
      def presignManifestPut(session: String, digest: Sha256, size: Long, ttl: FiniteDuration)
          : IO[String] = IO.blocking {
        val req = PutObjectRequest.builder().bucket(bucket).key(stagingKey(session, digest))
          .contentLength(size).build()
        presigner.presignPutObject(
          PutObjectPresignRequest.builder().signatureDuration(dur(ttl)).putObjectRequest(
            req
          ).build()
        ).url().toString
      }
      def presignGet(digest: Sha256, ttl: FiniteDuration): IO[String] =
        presignGetKey(key(digest), ttl)
      def presignGetKey(k: String, ttl: FiniteDuration): IO[String] = IO.blocking {
        presigner.presignGetObject(
          GetObjectPresignRequest.builder().signatureDuration(dur(ttl))
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(k).build()).build()
        ).url().toString
      }
    })

    def putBlob(k: String, bytes: Array[Byte], mediaType: String): IO[Unit] =
      write(k, bytes, mediaType, None)
    def getBlob(k: String): IO[Option[Array[Byte]]] = readKey(k)
    def statBlob(k: String): IO[Option[ObjectStat]] = head(k)
    def deleteBlob(k: String): IO[Unit] = remove(k)
    def listBlobs(prefix: String): fs2.Stream[IO, String] = keys(prefix).map(_.key())

    /** Creates the bucket when absent (local MinIO convenience; production buckets pre-exist). */
    def ensureBucket: IO[Unit] =
      io(client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())).void.recoverWith {
        case e: S3Exception if e.statusCode() == 404 =>
          io(client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())).void
        case _: NoSuchBucketException =>
          io(client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())).void
      }

  object S3:
    import software.amazon.awssdk.auth.credentials.*
    import software.amazon.awssdk.core.checksums.{
      RequestChecksumCalculation,
      ResponseChecksumValidation
    }
    import software.amazon.awssdk.regions.Region
    import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}
    import software.amazon.awssdk.services.s3.presigner.S3Presigner

    def resource(c: S3Config): Resource[IO, S3] =
      val creds: AwsCredentialsProvider = (c.accessKey, c.secretKey) match
        case (Some(a), Some(s)) =>
          StaticCredentialsProvider.create(AwsBasicCredentials.create(a, s))
        case _ => DefaultCredentialsProvider.builder().build()
      val serviceConf = S3Configuration.builder().pathStyleAccessEnabled(c.pathStyle).build()
      val client = Resource.fromAutoCloseable(IO {
        val b = S3AsyncClient.builder().region(Region.of(c.region)).credentialsProvider(creds)
          .serviceConfiguration(serviceConf)
          // S3-compatible services (MinIO) reject the SDK's default trailing-checksum uploads
          .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
          .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        c.endpoint.fold(b)(e => b.endpointOverride(java.net.URI.create(e))).build()
      })
      val presigner = Resource.fromAutoCloseable(IO {
        val b = S3Presigner.builder().region(Region.of(c.region)).credentialsProvider(creds)
          .serviceConfiguration(serviceConf)
        c.presigningEndpoint.fold(b)(e => b.endpointOverride(java.net.URI.create(e))).build()
      })
      (client, presigner).mapN(new S3(_, _, c.bucket, c.trustProviderChecksum))
