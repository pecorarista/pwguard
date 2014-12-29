package controllers

import java.net.URL

import dbservice.DAO
import models.{UserHelpers, User, PasswordEntry}
import util.EitherOptionHelpers._

import com.github.tototoshi.csv._

import grizzled.io.util._

import java.io._

import play.api._
import play.api.libs.json.{JsString, Json, JsValue}
import play.api.mvc.Results._
import play.api.mvc.BodyParsers._
import play.api.cache.Cache
import play.api.Play.current

import pwguard.global.Globals.ExecutionContexts.Default._

import scala.concurrent.Future
import scala.util.control.NonFatal

class UploadFailed(msg: String) extends Exception(msg)
class ImportFailed(msg: String) extends Exception(msg)

object ImportExportController extends BaseController {

  override val logger = Logger("pwguard.controllers.ImportExportController")

  import DAO.passwordEntryDAO

  object Field extends Enumeration {
    type Field = Value

    val Name        = Value
    val Description = Value
    val Login       = Value
    val Password    = Value
    val URL         = Value
    val Notes       = Value

    val Required = Set(Name, Description)
  }

  val FileCacheKey = "uploaded-file"

  // -------------------------------------------------------------------------
  // Public methods
  // -------------------------------------------------------------------------

  def exportData(filename: String) = SecuredAction { authReq =>

    def entryToList(user: User, e: PasswordEntry): Future[List[String]] = {
      val PasswordEntry(_, _, name, descriptionOpt, loginIDOpt,
                        encryptedPasswordOpt, urlOpt, notesOpt) = e
      val description = descriptionOpt.getOrElse("")
      val loginID     = loginIDOpt.getOrElse("")
      val notes       = notesOpt.getOrElse("")
      val url         = urlOpt.getOrElse("")

      encryptedPasswordOpt map { epw =>
        UserHelpers.decryptStoredPassword(user, epw) map { pw =>
          List(name, description, loginID, pw, url.toString, notes)
        }
      } getOrElse {
        Future.successful(List(name, description, loginID, "", notes))
      }
    }

    def copyResults(seq: Set[PasswordEntry], user: User): Future[File] = {
      Future {
        val out = File.createTempFile("pwguard", ".csv")
        out.deleteOnExit()
        out

      } flatMap { out: File =>

        val entryFutures = seq.map { entryToList(user, _) }
        for { seqOfFutures <- Future.sequence(entryFutures) }
        yield (out, seqOfFutures)

      } map { case (out, seq) =>
        withCloseable(new BufferedWriter(
                        new OutputStreamWriter(
                          new FileOutputStream(out), "UTF-8"))) { fOut =>
          withCloseable(CSVWriter.open(fOut)) { csv =>
            csv.writeRow(List(Field.Name.toString,
                              Field.Description.toString,
                              Field.Login.toString,
                              Field.Password.toString,
                              Field.URL.toString,
                              Field.Notes.toString))
            for (l <- seq) csv.writeRow(l)
          }
        }

        out
      }
    }

    for { seq  <- passwordEntryDAO.allForUser(authReq.user)
          file <- copyResults(seq, authReq.user) }
    yield Ok.sendFile(file)
            .as("application/x-download")
            .withHeaders("Content-disposition" -> s"attachment; filename=$filename")
  }

  def importDataUpload = SecuredAction(parse.multipartFormData) { authReq =>
    Future {
      implicit val request = authReq.request
      val optRes =
        for { uploaded <- request.body.file("file")
              // uploaded.ref is a play.api.libs.Files.TemporaryFile,
              // with a file member
              file      = uploaded.ref.file
              reader    = CSVReader.open(file)
              header    <- reader.readNext() }
        yield {
          val nonEmpty = header.filter { _.trim().length > 0 }
          if (nonEmpty.length == 0)
            throw new UploadFailed("Empty header row.")

          Cache.set(FileCacheKey, file)
          Ok(
            Json.obj(
              "headers" -> nonEmpty,
              "fields" -> Field.values.map { field =>
                Json.obj(
                  "name" -> field.toString,
                  "required" -> Field.Required.contains(field))
              }
            )
          )
        }

      optRes.getOrElse {
        throw new UploadFailed("Empty or unspecified CSV file.")
      }

    } recover {
      case NonFatal(e) => BadRequest(jsonError(e))
    }
  }

  def completeImport = SecuredJSONAction { authReq =>
    def getFile(): Future[File] = {
      Future {
        Cache.getAs[File](FileCacheKey).getOrElse {
          throw new ImportFailed("No previously uploaded file.")
        }
      }
    }

    def getMappings(): Future[Map[String, String]] = {
      Future {
        val json = authReq.request.body
        (json \ "mappings").asOpt[Map[String, String]].getOrElse {
          throw new ImportFailed("No mappings.")
        }
      }
    }

    def getReader(file: File): Future[CSVReader] = {
      Future {
        CSVReader.open(file)
      }
    }

    def mappingFor(key: Field.Value, mappings: Map[String, String]):
      Option[String] = {

      val sKey = key.toString
      val opt = mappings.get(sKey)
      if ((Field.Required contains key) && opt.isEmpty)
        throw new ImportFailed(s"Required mapping $sKey not found")
      opt
    }

    def maybeEncryptPW(password: Option[String]): Future[Option[String]] = {
      password map { pw =>
        UserHelpers.encryptStoredPassword(authReq.user, pw) map { Some(_) }
      } getOrElse {
        Future.successful(noneT[String])
      }
    }

    def saveIfNew(name:     String,
                  desc:     Option[String],
                  login:    Option[String],
                  password: Option[String],
                  urlOpt:   Option[String],
                  notes:    Option[String]): Future[Option[PasswordEntry]] = {
      val user = authReq.user

      val futureFuture: Future[Future[Option[PasswordEntry]]] =
        for { epwOpt   <- maybeEncryptPW(password)
              entryOpt <- passwordEntryDAO.findByName(user, name) }
        yield {
          if (entryOpt.isDefined) {
            logger.debug(s"Won't update existing $name entry for ${user.email}")
            Future.successful(None)
          }
          else {
            val url = urlOpt map { new URL(_) }
            val entry = PasswordEntry(id                = None,
                                      userID            = user.id.get,
                                      name              = name,
                                      description       = desc,
                                      loginID           = login,
                                      encryptedPassword = epwOpt,
                                      url               = url,
                                      notes             = notes)
            passwordEntryDAO.save(entry) map { Some(_) }
          }
        }

      futureFuture.flatMap {f => f}
    }


    // Get the mappings, find the uploaded file, and open a reader.
    val f =
      for { file     <- getFile()
            mappings <- getMappings()
            reader   <- getReader(file) }
      yield (mappings, reader)

    // If none of those failed, save the mappings in the CSV file, but only
    // if they're new.
    f flatMap {
      case (mappings, reader) => {
        // Use the mappings to find the appropriate headings.
        val nameHeader  = mappingFor(Field.Name, mappings)
        val descHeader  = mappingFor(Field.Description, mappings)
        val loginHeader = mappingFor(Field.Login, mappings)
        val notesHeader = mappingFor(Field.Notes, mappings)
        val pwHeader    = mappingFor(Field.Password, mappings)
        val urlHeader   = mappingFor(Field.URL, mappings)

        Future.sequence {
          // Load each row and map it to an Option[PasswordEntry]. The
          // None entries will correspond to existing DB entries whose names
          // match names in the uploaded file. The Some entries will be the
          // new entries.
          for { map <- reader.allWithHeaders() }
          yield {
            val name = map.get(nameHeader.get).getOrElse {
              throw new ImportFailed("Missing required name field.")
            }
            saveIfNew(name     = name,
                      password = pwHeader.flatMap(map.get(_)),
                      desc     = descHeader.flatMap(map.get(_)),
                      login    = loginHeader.flatMap(map.get(_)),
                      urlOpt   = urlHeader.flatMap(map.get(_)),
                      notes    = notesHeader.flatMap(map.get(_)))

          }
        }
      }

    } map { seq: Seq[Option[PasswordEntry]] =>
      // Get rid of the entries that weren't saved because they weren't
      // new.
      Ok(Json.obj("total" -> seq.flatten.length))

    } recover {
      case NonFatal(e) => BadRequest(jsonError(e))
    }
  }
}