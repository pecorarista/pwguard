package controllers

import dbservice.DAO
import models.{UserHelpers, User, PasswordEntry}
import models.PasswordEntryHelper.json.implicits._

import play.api._
import play.api.libs.json.{JsString, Json, JsValue}
import play.api.mvc.Request
import play.api.mvc.Results._

import util.JsonHelpers
import util.EitherOptionHelpers.Implicits._
import util.EitherOptionHelpers._
import pwguard.global.Globals.ExecutionContexts.Default._

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Success, Try}

/** Controller for search operations.
  */
object PasswordEntryController extends BaseController {

  override protected val logger = Logger("pwguard.controllers.PasswordEntryController")

  import DAO.passwordEntryDAO

  // -------------------------------------------------------------------------
  // Public methods
  // -------------------------------------------------------------------------

  def save(id: Int) = SecuredJSONAction {
    (user: User, request: Request[JsValue]) =>

    val f = for { pweOpt <- passwordEntryDAO.findByID(id)
                  pwe    <- pweOpt.toFuture("Password entry not found")
                  pwe2   <- decodeJSON(Some(pwe), user, request.body)
                  saved  <- passwordEntryDAO.save(pwe2)
                  json   <- jsonPasswordEntry(user, saved) }
            yield json

    f.map { json => Ok(json) }
     .recover { case NonFatal(e) => Ok(jsonError(e.getMessage)) }
  }

  def create = SecuredJSONAction { (user: User, request: Request[JsValue]) =>
    val f = for { pwe   <- decodeJSON(None, user, request.body)
                  saved <- passwordEntryDAO.save(pwe)
                  json  <- jsonPasswordEntry(user, saved) }
            yield json

    f.map { json => Ok(json) }
     .recover { case NonFatal(e) => Ok(jsonError(e.getMessage)) }
  }

  def delete(id: Int) = SecuredAction { (user: User, request: Request[Any]) =>
    passwordEntryDAO.delete(id) map { status =>
      Ok(Json.obj("ok" -> true))
    } recover { case NonFatal(e) =>
      Ok(jsonError(e.getMessage))
    }
  }

  def searchPasswordEntries = SecuredJSONAction {
    (user: User, request: Request[JsValue]) =>

    val json               = request.body
    val searchTerm         = (json \ "searchTerm").asOpt[String]
    val includeDescription = (json \ "includeDescription").asOpt[Boolean]
                                                          .getOrElse(false)
    val wordMatch          = (json \ "wordMatch").asOpt[Boolean]
                                                 .getOrElse(false)

    def searchDB(term: String): Future[Set[PasswordEntry]] = {
      user.id.map { id =>
        passwordEntryDAO.search(id, term, wordMatch, includeDescription)
      }.
      getOrElse(Future.successful(Set.empty[PasswordEntry]))
    }

    searchTerm.map { term =>
      entriesToJSON(user) { searchDB(term) } map { json =>
        Ok(json)
      } recover {
        case NonFatal(e) => Ok(jsonError(s"Search failed for $user:", e))
      }
    }.
    getOrElse(Future.successful(BadRequest(jsonError("Missing search term"))))
  }

  def all = SecuredAction { (user: User, request: Request[Any]) =>
    entriesToJSON(user) { passwordEntryDAO.allForUser(user) } map { json =>
      Ok(json)
    } recover {
      case NonFatal(e) => Ok(jsonError(s"Failed for $user", e))
    }
  }

  // -------------------------------------------------------------------------
  // Private methods
  // -------------------------------------------------------------------------

  private def decodeJSON(pwOpt: Option[PasswordEntry],
                         owner: User,
                         json: JsValue):
    Future[PasswordEntry] = {

    val nameOpt        = blankToNone((json \ "name").asOpt[String])
    val descriptionOpt = blankToNone((json \ "description").asOpt[String])
    val passwordOpt    = blankToNone((json \ "password").asOpt[String])
    val notesOpt       = blankToNone((json \ "notes").asOpt[String])
    val loginIDOpt     = blankToNone((json \ "login_id").asOpt[String])

    def maybeEncryptPassword(pwEntry: PasswordEntry): Future[PasswordEntry] = {
      passwordOpt.map { pw =>
        UserHelpers.encryptStoredPassword(owner, pw).map { epw =>
          pwEntry.copy(encryptedPassword = Some(epw))
        }
      }
      .getOrElse(Future.successful(pwEntry))
    }

    def handleExisting(pw: PasswordEntry): Future[PasswordEntry] = {
      val pw2 = pw.copy(name        = nameOpt.getOrElse(pw.name),
                        description = descriptionOpt.orElse(pw.description),
                        notes       = notesOpt.orElse(pw.notes))
      maybeEncryptPassword(pw2)
    }

    def makeNew: Future[PasswordEntry] = {

      def create(name: String, userID: Int): Future[PasswordEntry] = {
        Future.successful(PasswordEntry(id                = None,
                                        userID            = userID,
                                        name              = name,
                                        description       = descriptionOpt,
                                        loginID           = loginIDOpt,
                                        encryptedPassword = None,
                                        notes             = notesOpt))
      }

      for { name     <- nameOpt.toFuture("Missing required name field")
            userID   <- owner.id.toFuture("Missing owner user ID")
            pwEntry  <- create(name, userID)
            pwEntry2 <- maybeEncryptPassword(pwEntry)
            saved    <- passwordEntryDAO.save(pwEntry2) }
      yield saved
    }

    Seq(nameOpt, descriptionOpt, passwordOpt, notesOpt).flatMap {o => o} match {
      case Nil => Future.failed(new Exception("No posted password fields."))
      case _   => pwOpt map { handleExisting(_) } getOrElse { makeNew }

    }
  }

  private def entriesToJSON(user: User)
                           (getEntries: => Future[Set[PasswordEntry]]):
    Future[JsValue] = {

    for { entries   <- getEntries
          jsEntries <- jsonPasswordEntries(user, entries) }
    yield Json.obj("results" -> jsEntries)
  }

  // Decrypt the encrypted passwords and produce the final JSON.
  private def jsonPasswordEntries(user:            User,
                                  passwordEntries: Set[PasswordEntry]):
    Future[JsValue] = {

    val mapped: Seq[Future[JsValue]] = passwordEntries.toSeq.map { jsonPasswordEntry(user, _) }

    // We now have a sequence of futures. Map it to a future of a sequence.
    val fSeq = Future.sequence(mapped)

    // If any future is a failure, the future-sequence will be a failure.

    fSeq.map { seq =>
      // This is a sequence of JsValue objects.
      Json.toJson(seq)
    }.
    recover {
      case NonFatal(e) =>
        Json.obj("error" -> "Unableto decrypt one or more passwords.")
    }
  }

  private def jsonPasswordEntry(user: User, pwEntry: PasswordEntry):
    Future[JsValue] = {

    val json = Json.toJson(pwEntry)
    pwEntry.encryptedPassword.map { password =>
      UserHelpers.decryptStoredPassword(user, password).map { plaintext =>
        JsonHelpers.addFields(json, "plaintextPassword" -> JsString(plaintext))
      }
    }.
    getOrElse {
      Future.successful(json)
    }
  }

}
