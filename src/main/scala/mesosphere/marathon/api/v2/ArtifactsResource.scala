package mesosphere.marathon.api.v2

import java.io.InputStream
import java.util.Date
import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import com.sun.jersey.core.header.{ FormDataContentDisposition => FormInfo }
import com.sun.jersey.multipart.{ FormDataParam => FormParam }
import org.eclipse.jetty.http.MimeTypes

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.{ MarathonMediaType, RestResource }
import mesosphere.marathon.io.storage.StorageProvider

@Path("v2/artifacts")
class ArtifactsResource @Inject() (val config: MarathonConf, val storage: StorageProvider) extends RestResource {

  /**
    * Upload to root artifact store.
    */
  @POST
  @Consumes(Array(MediaType.MULTIPART_FORM_DATA))
  def uploadFile(@FormParam("file") upload: InputStream, @FormParam("file") info: FormInfo): Response =
    storeFile(info.getFileName, upload)

  /**
    * Upload to a specific path inside artifact store.
    */
  @PUT
  @Path("{path:.+}")
  @Consumes(Array(MediaType.MULTIPART_FORM_DATA))
  def uploadFilePut(@PathParam("path") path: String, @FormParam("file") upload: InputStream): Response =
    storeFile(path, upload)

  @POST
  @Path("{path:.+}")
  @Consumes(Array(MediaType.MULTIPART_FORM_DATA))
  def uploadFilePost(@PathParam("path") path: String, @FormParam("file") upload: InputStream): Response =
    storeFile(path, upload)

  private def storeFile(path: String, upload: InputStream) = {
    //scalastyle:off null
    require(upload != null, "Please use 'file' as form parameter name!")
    //scalastyle:on
    val item = storage.item(path)
    val exists = item.exists
    item.store(upload)
    if (exists) ok() else created(item.url)
  }

  /**
    * Get a specific artifact.
    */
  @GET
  @Path("{path:.+}")
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def get(@PathParam("path") path: String): Response = {
    val item = storage.item(path)
    if (!item.exists) {
      notFound(s"No artifact with path $path")
    }
    else {
      Response.
        ok(item.inputStream(), mediaMime(path)).
        lastModified(new Date(item.lastModified)).
        header("Content-Length", item.length).
        build()
    }
  }

  /**
    * Delete an artifact from store.
    */
  @DELETE
  @Path("{path:.+}")
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def delete(@PathParam("path") path: String): Response = {
    val item = storage.item(path)
    if (item.exists) item.delete()
    ok()
  }

  private[this] val mimes = new MimeTypes()
  def mediaMime(path: String): String =
    Option(mimes.getMimeByExtension(path)).getOrElse("application/octet-stream")

}
