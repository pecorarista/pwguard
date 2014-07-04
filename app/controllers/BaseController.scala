package controllers

import dbservice.DAO
import models.User
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.Play.current
import play.api.libs.json.{ JsString, Json, JsValue }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

/** Base class for all controllers.
  */
trait BaseController {

  protected val logger = pwguard.global.Globals.mainLogger

  // --------------------------------------------------------------------------
  // Protected methods
  // ------------------------------------------------------------------------

  /** `Action` wrapper for actions that do not require a logged-in user.
    * Play infers the body parser to use from the incoming HTTP headers.
    *
    * @param f  the caller's block of action code
    */
  protected def UnsecuredAction(f: Request[AnyContent] => Future[Result]) = {
    Action.async { implicit request =>
      logAndHandleRequest(f, request)
    }
  }

  /** `Action` wrapper for actions that do not require a logged-in user.
    * Caller specifies the desired body parser type; requests that don't
    * adhere to that type are rejected by Play.
    *
    * @param bodyParser specific body parser to use
    * @tparam T         body parser type
    * @return           the Action
    */
  protected def UnsecuredAction[T](bodyParser: BodyParser[T])
                                  (f: Request[T] => Future[Result]) = {
    Action.async(bodyParser) { implicit request =>
      logAndHandleRequest(f, request)
    }
  }

  /** `Action` wrapper for actions that require a logged-in user. Play infers
    * the parser to use from the incoming content type. Example use:
    *
    * {{{
    * def doAmazingAction = ActionWithUser(
    *   { (user, request) => Future(amazing(user, request)) },
    *   { request         => Future(Redirect(routes.Application.login())) }
    * )
    * }}}
    *
    * @param whenLoggedIn function to call if a user is logged in; must return
    *               a `Future[SimpleResult]`
    * @param noUser       function to call if there isn't a logged-in user;
    *               must return a `Future[SimpleResult]`
    * @return the actual action
    */
  def ActionWithUser(whenLoggedIn: (User, Request[AnyContent]) => Future[Result],
                     noUser:       Request[AnyContent] => Future[Result]):
    Action[AnyContent] = {

    ActionWithUser(BodyParsers.parse.anyContent)(whenLoggedIn, noUser)
  }

  /** `Action` wrapper for actions that require a logged-in user. Uses an
    * explicit body parser. Example use:
    *
    * {{{
    * def doAmazingAction = ActionWithUser(parse.json) {
    * { (user, request) => Future(amazingJson(user, request)) },
    * { request         => Future(errorJson("not logged in) }
    * )
    * }}}
    *
    * @param bodyParser   the body parser to use
    * @param whenLoggedIn function to call if a user is logged in; must return
    *               a `Future[SimpleResult]`
    * @param noUser       function to call if there isn't a logged-in user;
    *               must return a `Future[SimpleResult]`
    * @return the actual action
    */
  protected def ActionWithUser[T](bodyParser: BodyParser[T])
                                 (whenLoggedIn: (User, Request[T]) => Future[Result],
                                  noUser:       Request[T] => Future[Result]): Action[T] = {

    Action.async(bodyParser) { implicit request =>
      Future {
        SessionOps.loggedInEmail(request).map { email =>
          DAO.userDAO.findByEmail(email) match {
            case Left(_)           => logAndHandleRequest(noUser, request)
            case Right(None)       => logAndHandleRequest(noUser, request)
            case Right(Some(user)) => {
              val f = { r: Request[T] => whenLoggedIn(user, r) }
              logAndHandleRequest(f, request)
            }
          }
        }.
        getOrElse(logAndHandleRequest(noUser, request))

        Ok("")
      }
    }

  }

  /** Convenience method to processing incoming secured JSON request, sending
    * back a consistent error when no user is logged in. Built on top of
    * `ActionWithUser`.
    *
    * @param f   The handler returning the JSON result, wrapped in a Future
    * @return    The actual action
    */
  def SecuredJSONAction(f: (User, Request[JsValue]) => Future[Result]) = {
    ActionWithUser(BodyParsers.parse.json)(
      f,

      { request => Future { Unauthorized } }
    )
  }

  /** Convenience method to processing incoming secured request, sending
    * back a consistent error when no user is logged in. Built on top of
    * `ActionWithUser`.
    *
    * @param f   The handler returning the result, wrapped in a Future
    * @return    The actual action
    */
  def SecuredAction(f: (User, Request[AnyContent]) => Future[Result]) = {
    ActionWithUser(
      f,

      { request => Future { Unauthorized } }
    )
  }

  /** Send a file to the browser.
    *
    * @param path the full path to the file
    *
    * @return the HTTP result, as a Future.
    */
  protected def sendFile(path: String): Future[Result] = {
    Future {
      val file = Play.getFile(path)
      if (file.exists) {
        // See http://www.playframework.com/documentation/2.1.1/ScalaStream
        Ok.sendFile(
          content = file,
          inline = true
        )
      }
      else {
        NotFound
      }
    }
  }

  /** Parameters to pass back in a new session.
    *
    * @param user the current user
    *
    * @return a sequence of pairs
    */
  protected def sessionParameters(user: User): Seq[(String, String)]= {
    Seq(Security.username -> user.email)
  }

  /** Generate consistent JSON error output.
    *
    * @param error       optional error message
    * @param status      optional HTTP status
    * @param fieldErrors option form field errors, keyed by field ID
    *
    * @return the JSON result
    */
  protected def jsonError(error:       Option[String],
                          status:      Option[Int],
                          fieldErrors: (String, String)*): JsValue = {

    val emptyJsonObject  = Map.empty[String, JsValue]
    val fieldErrorJson   = if (fieldErrors.length > 0)
                             Map("fields" -> Json.toJson(Map(fieldErrors: _*)))
                           else
                             emptyJsonObject
    val errorMessageJson = error.map { s => Map("message" -> JsString(s)) }.
                                 getOrElse(emptyJsonObject)

    val json = Json.obj("error" -> (fieldErrorJson ++ errorMessageJson))
    status.map { i => json ++ Json.obj("status" -> i) }.getOrElse(json)
  }

  // --------------------------------------------------------------------------
  // Protected methods
  // ------------------------------------------------------------------------

  private def logAndHandleRequest[T](handler: Request[T] => Future[Result],
                                     request: Request[T]): Future[Result] = {
    logger.debug { s"Received request ${request}" }
    val res = handler(request)
    logger.debug { s"Finished processing request ${request}" }
    res
  }
}
