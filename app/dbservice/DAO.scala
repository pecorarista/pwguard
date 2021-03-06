package dbservice

import play.api.Logger

/** Wrapper DAO for controllers and classes. Hides the underlying DAO
  * implementation.
  */
object DAO {
  private val dal    = pwguard.global.Globals.DAL
  private val logger = Logger("pwguard.dbservice.DAO")

  val userDAO                           = new UserDAO(dal, logger)
  val passwordEntryDAO                  = new PasswordEntryDAO(dal, logger)
  val passwordEntryExtraFieldsDAO       = new PasswordEntryExtraFieldsDAO(dal, logger)
  val passwordEntryKeywordsDAO          = new PasswordEntryKeywordsDAO(dal, logger)
  val passwordEntrySecurityQuestionsDAO = new PasswordEntrySecurityQuestionsDAO(dal, logger)
}
