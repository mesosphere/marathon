package mesosphere.marathon.api.v2

import java.io.InputStream
import java.util.Date
import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import com.sun.jersey.core.header.FormDataContentDisposition
import com.sun.jersey.multipart.FormDataParam
import org.eclipse.jetty.http.MimeTypes

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
  def uploadFile(@PathParam("path") path: String, @FormDataParam("file") upload: InputStream) = {
    require(upload != null, "Please use 'file' as form parameter name!")
    created(storage.item(path).store(upload).url)
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
  @Produces(Array(MediaType.APPLICATION_JSON))
  def delete(@PathParam("path") path: String) = {
    val item = storage.item(path)
    if (item.exists) item.delete()
    ok()
  }

  private[this] val mimes = new MimeTypes()
  def mediaMime(path: String) = Option(mimes.getMimeByExtension(path)).map(_.toString("UTF-8")).getOrElse("application/octet-stream")
}
