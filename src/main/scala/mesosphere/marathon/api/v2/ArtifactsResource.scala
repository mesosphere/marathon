package mesosphere.marathon.api.v2

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.{ TimeZone, Locale }
import javax.inject.Inject
import javax.ws.rs.core.{ Response, MediaType }
import javax.ws.rs._

import com.sun.jersey.core.header.FormDataContentDisposition
import com.sun.jersey.multipart.FormDataParam

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.RestResource
import mesosphere.marathon.io.storage.StorageProvider

@Path("/v2/artifacts")
class ArtifactsResource @Inject() (val config: MarathonConf, val storage: StorageProvider) extends RestResource {

  /**
    * Upload to root artifact store.
    */
  @POST
  @Consumes(Array(MediaType.MULTIPART_FORM_DATA))
  def uploadFile(
    @FormDataParam("file") upload: InputStream,
    @FormDataParam("file") fileDetail: FormDataContentDisposition) = {
    require(upload != null && fileDetail != null, "Please use 'file' as form parameter name!")
    created(storage.item(fileDetail.getFileName).store(upload).url)
  }

  /**
    * Upload to a specific path inside artifact store.
    */
  @POST
  @Path("{path:.+}")
  @Consumes(Array(MediaType.MULTIPART_FORM_DATA))
  def uploadFile(
    @PathParam("path") path: String,
    @FormDataParam("file") upload: InputStream,
    @FormDataParam("file") fileDetail: FormDataContentDisposition) = {
    require(upload != null && fileDetail != null, "Please use 'file' as form parameter name!")
    created(storage.item(path + "/" + fileDetail.getFileName).store(upload).url)
  }

  /**
    * Get a specific artifact.
    */
  @GET
  @Path("{path:.+}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def get(@PathParam("path") path: String) = {
    val item = storage.item(path)
    if (!item.exists) {
      notFound(s"No artifact with path $path")
    }
    else {
      val mediaType = path.replaceFirst(".*\\.", "")
      Response.
        ok(item.inputStream(), mediaMime(mediaType)).
        header("Content-Length", item.length).
        header("Last-Modified", internetDateFormatter.format(item.lastModified)).
        build()
    }
  }

  /**
    * Delete an artifact from store.
    */
  @DELETE
  @Path("{path:.+}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def delete(@PathParam("path") path: String) = {
    val item = storage.item(path)
    if (item.exists) item.delete()
    ok()
  }

  private def internetDateFormatter = {
    val utc = TimeZone.getTimeZone("UTC")
    val ret = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.US)
    ret.setTimeZone(utc)
    ret
  }

  private val mediaMime = Map(
    "avi" -> "video/avi",
    "bmp" -> "image/bmp",
    "bz2" -> "application/x-bzip2",
    "css" -> "text/css",
    "dtd" -> "application/xml-dtd",
    "doc" -> "application/msword",
    "eot" -> "application/vnd.ms-fontobject",
    "exe" -> "application/octet-stream",
    "flac" -> "audio/flac",
    "flv" -> "video/x-flv",
    "gif" -> "image/gif",
    "gz" -> "application/x-gzip",
    "html" -> "text/html",
    "ico" -> "image/x-icon",
    "jar" -> "application/java-archive",
    "jpg" -> "image/jpeg",
    "js" -> "application/javascript",
    "json" -> "application/json",
    "midi" -> "audio/x-midi",
    "mp3" -> "audio/mpeg",
    "mp4" -> "video/mp4",
    "mpeg" -> "video/mpeg",
    "oga" -> "audio/ogg",
    "ogg" -> "audio/ogg",
    "ogx" -> "application/ogg",
    "ogv" -> "video/ogg",
    "otf" -> "application/x-font-otf",
    "pdf" -> "application/pdf",
    "pl" -> "application/x-perl",
    "png" -> "image/png",
    "ppt" -> "application/vnd.ms-powerpoint",
    "ps" -> "application/postscript",
    "qt" -> "video/quicktime",
    "ra" -> "audio/x-pn-realaudio",
    "ram" -> "audio/x-pn-realaudio",
    "rdf" -> "application/rdf",
    "rtf" -> "application/rtf",
    "sit" -> "application/x-stuffit",
    "svg" -> "image/svg+xml",
    "swf" -> "application/x-shockwave-flash",
    "tar" -> "gz application/x-tar",
    "tgz" -> "application/x-tar",
    "tiff" -> "image/tiff",
    "tsv" -> "text/tab-separated-values",
    "ttf" -> "application/x-font-ttf",
    "txt" -> "text/plain",
    "wav" -> "audio/wav",
    "woff" -> "application/font-woff",
    "xls" -> "application/vnd.ms-excel",
    "xml" -> "application/xml",
    "zip" -> "application/zip"
  ).withDefaultValue("application/octet-stream")
}
