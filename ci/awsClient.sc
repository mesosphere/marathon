#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._

import $file.aws
import $file.fileUtil

import com.amazonaws.services.s3._
import com.amazonaws.auth._
import com.amazonaws.services.s3.transfer._
import com.amazonaws.services.s3.transfer.Transfer.TransferState
import com.amazonaws.services.s3.model.{PutObjectRequest, CannedAccessControlList}
import scala.collection.JavaConversions._


val DEFAULT_BUCKET = "downloads.mesosphere.io"
val DEFAULT_FOLDER = "marathon/snapshots"

/**
 *  Returns AWS S3 client.
 */
def createS3Client(): AmazonS3Client = {
  new AmazonS3Client(new DefaultAWSCredentialsProviderChain())
}

/**
 *  Returns True if the key is in the bucket
 */
def doesS3FileExist(bucket: String, key: String): Boolean = {
  val s3client = createS3Client()
  s3client.doesObjectExist(bucket, key)
}

/**
 *  Returns the default folder for the filekey
 */
def getDefaultFileKey(fileName: String): String = {
  return s"${DEFAULT_FOLDER}/${fileName}"
}

def skip(file: Path): Unit = println(s"Skipping File: ${file.last} already exists on S3 at ${getDefaultFileKey(file.last)}.")

/**
 *  Uploads marathon artifacts to the default bucket, using the env var credentials.
 *  Upload process creates the sha1 file.
 *  If file name is on s3, it does NOT upload (these files are big). We cannot
 *  compare the sha1 sums because they change for each build of the same commit.
 *  However, our artifact names are unique for each commit.
 */
def archiveArtifact(uploadFile: Path): Unit = {
  // is already uploaded.
  if(doesS3FileExist(uploadFile)) skip(uploadFile)
  else uploadFileAndSha(uploadFile)
}

/**
 *  Uploads marathon artifacts to the default bucket, using the env var credentials.
 *  Upload process creates the sha1 file
 */
def uploadFileAndSha(uploadFile: Path): Unit = {

  val fileKey: String = getDefaultFileKey(uploadFile.last)
  val shaFile = fileUtil.writeSha1ForFile(uploadFile)
  val shaFileKey: String = getDefaultFileKey(shaFile.last)

  println(s"${fileKey} uploading to S3")
  uploadFileToS3(DEFAULT_BUCKET, fileKey, uploadFile)

  println(s"${shaFileKey} uploading to S3")
  uploadFileToS3(DEFAULT_BUCKET, shaFileKey, shaFile)
}


/**
 *  Uploads a file to the S3 bucket
 */
def uploadFileToS3(bucket: String, fileName: String, uploadFile: Path): Unit = {
  val transfer: TransferManager = TransferManagerBuilder.standard().withS3Client(createS3Client()).build()
  val request = new PutObjectRequest(bucket, fileName, uploadFile.toIO)
  request.withCannedAcl(CannedAccessControlList.PublicRead)
  val upload: Upload = transfer.upload(request)

  while(!upload.isDone()) {
    val progress = upload.getProgress()
    println(s"Uploading (${fileName}): ${progress.getPercentTransferred()} % ${upload.getState()}")
    Thread.sleep(2000)
  }

  transfer.shutdownNow(true)
  assert(upload.getState() == TransferState.Completed, s"Upload finished with ${upload.getState()}")
}
