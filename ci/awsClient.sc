#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._

import $ivy.`com.amazonaws:aws-java-sdk-s3:1.11.129`

import $file.fileUtil

import com.amazonaws.services.s3._
import com.amazonaws.auth._
import com.amazonaws.services.s3.transfer._
import com.amazonaws.services.s3.transfer.Transfer.TransferState
import com.amazonaws.services.s3.model.{PutObjectRequest, CannedAccessControlList}
import scala.collection.JavaConversions._

/**
 * Describes an S3 location.
 */
case class S3Path(bucket: String, path: Path) {
  override def toString(): String = s"s3://${bucket}${path}"
  def /(component: String): S3Path = copy(path = path / component)
  def key: String = path.relativeTo(Path("/")).toString
}

/**
 * Describes an artifact.
 *
 * @param path S3 path for artifact.
 * @param sha1 The checksum of the artifact.
 */
case class Artifact(path: S3Path, sha1: String) {

  val base = "https://s3.amazonaws.com"

  /**
   * @return download url.
   */
  def downloadUrl: String = s"$base/${path.bucket}/${path.key}"
}

/**
 * builds =  builds, milestones, releases
 */
def s3PathFor(buildLocation: String = "builds") : S3Path = {
  S3Path(
    sys.env.getOrElse("S3_BUCKET", "downloads.mesosphere.io"),
    Path(sys.env.getOrElse("S3_PATH" , s"/marathon/$buildLocation")))
}

/**
 *  Returns AWS S3 client.
 */
def createS3Client(): AmazonS3Client = {
  new AmazonS3Client(new DefaultAWSCredentialsProviderChain())
}

/**
 *  Returns True if the key is in the bucket
 */
def doesS3FileExist(path: S3Path): Boolean = {
  val s3client = createS3Client()
  s3client.doesObjectExist(path.bucket, path.key)
}

/**
 *  Uploads marathon artifacts to the default bucket, using the env var credentials.
 *  Upload process creates the sha1 file.
 *  If file name is on s3, it does NOT upload (these files are big). We cannot
 *  compare the sha1 sums because they change for each build of the same commit.
 *  However, our artifact names are unique for each commit.
 *
 *  @param uploadFile path of the file to be uploaded to S3.
 *  @param s3path S3 path to upload file to on S3.
 *  @return Artifact description if it was uploaded. None otherwise.
 */
def archiveArtifact(uploadFile: Path, s3path: S3Path): Option[Artifact] = {
  // is already uploaded.
  if(doesS3FileExist(s3path / uploadFile.last)) {
    println(s"Skipping File: ${uploadFile.last} already exists on S3 at ${s3path / uploadFile.last}")
    None
  } else
    Some(uploadFileAndSha(uploadFile, s3path))
}

/**
 *  Uploads marathon artifacts to the default bucket, using the env var credentials.
 *  Upload process creates the sha1 file
 *
 *  @param uploadFile path of the file to be uploaded to S3.
 *  @param s3path S3 path to upload file to on S3.
 */
def uploadFileAndSha(uploadFile: Path, s3path: S3Path): Artifact = {
  val shaFile = fileUtil.writeSha1ForFile(uploadFile)

  upload(uploadFile, s3path)
  println(s"Sha1 for file: ${uploadFile}")
  val sha1 = %%('cat, shaFile).out.string
  println(sha1)
  upload(shaFile, s3path)
  Artifact(s3path / uploadFile.last, read(shaFile))
}

/**
 * Uploads file to default bucket.
 */
def upload(file: Path, s3path: S3Path): Unit = {
  uploadFileToS3(file, s3path / file.last)
}

/**
 *  Uploads a file to the S3 bucket
 */
def uploadFileToS3(localFile: Path, remotePath: S3Path): Unit = {
  val transfer: TransferManager = TransferManagerBuilder.standard().withS3Client(createS3Client()).build()
  val request = new PutObjectRequest(remotePath.bucket, remotePath.key, localFile.toIO)
  request.withCannedAcl(CannedAccessControlList.PublicRead)
  val upload: Upload = transfer.upload(request)

  println(s"Uploading ${localFile} -> ${remotePath}")
  while(!upload.isDone()) {
    val progress = upload.getProgress()
    println(s"Uploading ${localFile} -> ${remotePath}: ${progress.getPercentTransferred()} % ${upload.getState()}")
    Thread.sleep(2000)
  }

  transfer.shutdownNow(true)
  assert(upload.getState() == TransferState.Completed, s"Upload finished with ${upload.getState()}")
  println(s"Uploading ${localFile} -> ${remotePath}: Finished")
}
