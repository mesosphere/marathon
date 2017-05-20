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

/**
 *  Returns the SHA from the sha file for the file provided.
 *  ex. file == "README.txt", it has a corresponding "README.txt.sha1" as a sha file.
 *  a temp sha file of "README.txt.sha1.tmp" will be created and deleted in the process of getting s3 sha
 *  Returns a "" if sha file doesn't exist.
 */
def readShaFromS3File(file: Path): Option[String] = {
  val shaKey: String = getDefaultFileKey(fileUtil.getShaPathForFile(file).last)
  readFileFromS3(DEFAULT_BUCKET, shaKey)
}

def readFileFromS3(bucket: String, key: String): Option[String] =
  if(doesS3FileExist(DEFAULT_BUCKET, key)) { fileUtil.withTempFile { tempFile =>
    // read file to temp
    downloadFileFromS3(DEFAULT_BUCKET, key, tempFile)
    Some(read! tempFile)
  }}
  else {
    None
  }

def skip(file: Path): Unit = println(s"Skipping File: ${file.last} already exists on S3 at ${getDefaultFileKey(file.last)}.")

/**
 *  Uploads marathon artifacts to the default bucket, using the env var credentials.
 *  Upload process creates the sha1 file
 *  If file is on s3 and the sha1 file has a valid sha, it does NOT upload. (these files are big)
 */
def archiveArtifact(uploadFile: Path): Unit = {
  // is already uploaded and the sha file is the correct sha
  if(alreadyUploaded(uploadFile)) skip(uploadFile)
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
 *  returns True if the s3 sha file has a sha == to local sha and the
 *  artifact file is already uploaded on s3.
 */
def alreadyUploaded(uploadFile: Path): Boolean = {
  val fileKey: String = getDefaultFileKey(uploadFile.last)
  val localSha = read! fileUtil.writeSha1ForFile(uploadFile)
  val s3Sha = readShaFromS3File(uploadFile)
  s3Sha.fold(false)(_ == localSha) && doesS3FileExist(DEFAULT_BUCKET, fileKey)
}

/**
 *  Downloads a file from the S3 bucket
 */
def downloadFileFromS3(bucket: String, fileName: String, downloadFile: Path): Unit = {
  val transfer: TransferManager = TransferManagerBuilder.standard().withS3Client(createS3Client()).build()
  val download: Download = transfer.download(bucket, fileName, downloadFile.toIO)
  while(!download.isDone()) {
    val progress = download.getProgress()
    println(s"Downloading (${fileName}): ${progress.getPercentTransferred()} % ${download.getState()}")
    Thread.sleep(1000)
  }

  transfer.shutdownNow(true)
  assert(download.getState() == TransferState.Completed, s"Download finished with ${download.getState()}")
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
