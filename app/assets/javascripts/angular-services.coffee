# Custom Angular.js services

pwgServices = angular.module('pwguard-services', [])

pwgServices.value('angularTemplateURL', window.angularTemplateURL)

# ----------------------------------------------------------------------------
# Logging service. Basically, this service simply hides the initialization
# of a log4javascript-compatible logging service, providing a simple
# logger() function to retrieve a new logger.
# ----------------------------------------------------------------------------

pwgLogging = ->
  log      = log4javascript.getLogger()
  appender = new log4javascript.BrowserConsoleAppender()

  log4javascript.setShowStackTraces true

  # Allow the console appender to use the default NullLayout, which allows
  # for the logging of objects without converting them to strings.
  ###
  layout   = new log4javascript.PatternLayout "%d{HH:mm:ss} (%-5p) %c %m"
  appender.setLayout layout
  ###

  log.addAppender appender

  mapLevel = (stringLevel) ->
    switch stringLevel
      when "trace" then log4javascript.Level.TRACE
      when "debug" then log4javascript.Level.DEBUG
      when "info" then log4javascript.Level.INFO
      when "warn" then log4javascript.Level.WARN
      when "error" then log4javascript.Level.ERROR
      when "fatal" then log4javascript.Level.FATAL
      else log4javascript.Level.INFO

  loggingLevel = mapLevel window.browserLogLevel
  appender.setThreshold loggingLevel

  logger: (name, level='debug') ->
    logger = log4javascript.getLogger(name)
    logger.addAppender appender
    logger.setLevel mapLevel(level)
    logger

pwgServices.factory 'pwgLogging', [pwgLogging]

# ----------------------------------------------------------------------------
# Error service
# ----------------------------------------------------------------------------

pwgError = ->

  showStackTrace: (prefix = "") ->
    console.log prefix if prefix?
    console.log (new Error()).stack

pwgServices.factory 'pwgError', [pwgError]

# ----------------------------------------------------------------------------
# Front-end service for AJAX calls. Handles errors in a consistent way, and
# fires up a spinner.
# ----------------------------------------------------------------------------

pwgAjax = ($http, $rootScope, pwgSpinner, pwgFlash, pwgError) ->

  callOn401 = null

  # Local
  handleFailure = (data, status, onFailure) ->
    # invoke flash service here
    message = data.error?.message or "Server error. We're looking into it."
    if status is 401
      data =
        error:
          status: 401
          message: "Login required."
      callOn401() if callOn401?
    else
      pwgFlash.error "(#{status}) #{message}", status
      onFailure(data) if onFailure?

  handleSuccess = (response, status, onSuccess, onFailure) ->

    # Angular doesn't seem to handle 401 responses properly, so we're
    # mimicking them with JSON.
    #
    # NOTE: This happens when an HTTP interceptor is injected. Without
    # the interceptor, Angular behaves correctly.
    if response.error?
      console.log response
      pwgFlash.error response.error.message if response.error.message?
      onFailure(response) if onFailure?
    else
      onSuccess(response) if onSuccess?

  http = (config, onSuccess, onFailure)->
    failed = (data, status, headers, config) ->
      pwgSpinner.stop()
      handleFailure data, status, onFailure

    succeeded = (data, status, headers, config) ->
      pwgSpinner.stop()
      handleSuccess data, status, onSuccess, onFailure

    pwgSpinner.start()
    $http(config).success(succeeded).error(failed)

  # Post to a URL.
  #
  # Parameters
  # url       - the URL to which to post
  # data      - the data to send, or null for none.
  # onSuccess - Callback to invoke, with the response, on success
  # onFailure - Optional failure callback, invoked AFTER the regular one.
  post: (url, data, onSuccess, onFailure) ->
    params =
      method: 'POST'
      url:    url
      data:   data

    if url?
      http(params, onSuccess, onFailure)
    else
      pwgError.showStackTrace("No URL to pwgAjax.post()")


  # Get a URL.
  #
  # Parameters:
  #
  # url       - the URL to retrieve
  # onSuccess - Callback to invoke, with the response, on success
  # onFailure - Optional failure callback, invoked AFTER the regular one.
  get: (url, onSuccess, onFailure = null) ->
    params =
      method: 'GET'
      url:    url

    if url?
      http(params, onSuccess, onFailure)
    else
      pwgError.showStackTrace("No URL to pwgAjax.get()")

  # Issue an HTTP DELETE to a URL
  #
  # Parameters:
  #
  # url       - the URL to retrieve
  # onSuccess - Callback to invoke, with the response, on success
  # onFailure - Optional failure callback, invoked AFTER the regular one.
  delete: (url, onSuccess, onFailure = null) ->
    params =
      method: 'DELETE'
      url:    url
    http(params, onSuccess, onFailure)

  # Specify a function to call when a 401 (Unauthorized) error occurs.
  on401: (callback) ->
    callOn401 = callback

  # Check a JSON response received by other means (such as the AngularJS
  # file uploader).
  #
  # response  - JSON response to check
  # status    - HTTP status
  # onSuccess - Callback to invoke, with the response, on success
  # onFailure - Optional failure callback, invoked AFTER the regular one.
  checkResponse: (response, status, onSuccess = null, onFailure = null) ->
    if status < 400
      handleSuccess response, status, onSuccess, onFailure
    else
      handleFailure response, status, onFailure

pwgServices.factory 'pwgAjax', ['$http',
                                '$rootScope',
                                'pwgSpinner',
                                'pwgFlash',
                                'pwgError',
                                pwgAjax]
# ----------------------------------------------------------------------------
# Simple spinner service. Assumes the existence of an element that's monitoring
# the root scope's "showSpinner" variable.
# ----------------------------------------------------------------------------

pwgSpinner = ($rootScope) ->
  $rootScope.showSpinner = true

  start: ->
    $rootScope.showSpinner = true

  stop: ->
    $rootScope.showSpinner = false

pwgServices.factory 'pwgSpinner', ['$rootScope', pwgSpinner]

# ----------------------------------------------------------------------------
# A timeout service with arguments in a more sane order.
# ----------------------------------------------------------------------------

pwgTimeout = ($timeout) ->
  cancel: (promise) ->
    $timeout.cancel promise

  timeout: (timeout, callback) ->
    $timeout callback, timeout

pwgServices.factory 'pwgTimeout', ['$timeout', pwgTimeout]

# ----------------------------------------------------------------------------
# Simple flash service. Use in conjunction with the pwg-flash directive.
#
# This service sets or clears the following variables in the root scope:
#
# flash.message.info    - info alert message
# flash.message.error   - error messages
# flash.message.warning - warning alert messages
#
# The service provides the following functions. These functions are also
# available on the $rootScope.flash object, for use in HTML.
#
# init()             - CALL THIS FIRST at application startup.
# warn(msg)          - issue a warning message
# info(msg)          - issue an info message
# error(msg)         - issue an error message
# message(type, msg) - issue a message of the specified type. The types can
#                      be 'warn', 'info', 'error', 'all'
# clear(type)        - clear message(s) of the specified type. The types can
#                      be 'warn', 'info', 'error', 'all'
# clearInfo()        - convenience
# clearWarning()     - convenience
# clearError()       - convenience
# clearAll()         - convenience
# ----------------------------------------------------------------------------

pwgFlash = ($rootScope) ->

  handleMessage = (type, msg) ->
    m = $rootScope.flash.message
    switch type
      when 'info'    then m.info    = msg
      when 'warning' then m.warning = msg
      when 'error'   then m.error   = msg
      when 'all'
        m.info    = msg
        m.warning = msg
        m.error   = msg

  showMessage = (type, msg) ->
    handleMessage type, msg
    #if msg?
    #  cb = -> handleMessage type, null
    #  $timeout cb, 5000

  init: ->
    $rootScope.flash =
      message:
        info:    null
        error:   null
        warning: null
      warn:    (msg) ->
        showMessage 'warning', msg
      info:    (msg) ->
        showMessage 'info', msg
      error:   (msg) ->
        showMessage 'error', msg
      message: (type, msg) ->
        showMessage type, msg
      clear:   (type) ->
        showMessage type, null
      clearError: ->
        showMessage 'error', null
      clearWarning: ->
        showMessage 'warning', null
      clearInfo: ->
        showMessage 'info', null
      clearAll: ->
        for type in ['info', 'warning', 'error']
          showMessage type, null

  message: (type, msg) ->
    showMessage type, msg

  warn: (msg) ->
    $rootScope.flash.warn msg

  error: (msg) ->
    $rootScope.flash.error msg

  info: (msg) ->
    $rootScope.flash.info msg

  clear: (type) ->
    $rootScope.flash.clear type

  clearError: ->
    $rootScope.flash.clearError()

  clearInfo: ->
    $rootScope.flash.clearInfo()

  clearWarning: ->
    $rootScope.flash.clearWarning()

  clearAll: ->
    $rootScope.flash.clearAll()

pwgServices.factory 'pwgFlash', ['$rootScope', pwgFlash]

# ----------------------------------------------------------------------------
# Get info about the currently logged-in user
# ----------------------------------------------------------------------------

pwgCheckUser = ($q, pwgAjax) ->
  deferred = null

  onSuccess = (response) ->
    deferred.resolve response
    deferred = null

  onFailure = (response) ->
    deferred.reject response
    deferred = null

  checkUser: ->
    deferred = $q.defer()
    url = routes.controllers.SessionController.getLoggedInUser().url
    pwgAjax.post url, {}, onSuccess, onFailure
    deferred.promise

pwgServices.factory 'pwgCheckUser', ['$q', 'pwgAjax', pwgCheckUser]

# ----------------------------------------------------------------------------
# Modal service. Hides underlying implementation(s).
# ----------------------------------------------------------------------------

pwgModal = ($q, $modal, $rootScope) ->

  mobile = window.browserIsMobile

  # Shows an appropriate confirmation dialog, depending on whether the user
  # is mobile or not. Returns a promise (via $q) that resolves on confirmation
  # and rejects on cancel.
  #
  # Parameters:
  #   message - the confirmation message
  #   title   - optional title for the dialog, if supported
  #
  # NOTE: Only one of these can be active at one time!

  confirm: (message, title) ->
    deferred = $q.defer()

    if mobile
      if confirm(message)
        deferred.resolve()
      else
        deferred.reject()

    else
      modalOpts =
        title:    title
        template: routes.staticAsset("AngularTemplates/confirmModal.html")
        backdrop: 'static'
        content:  message
        show:     false

      modal = $modal(modalOpts)
      confirmed = ->
        deferred.resolve()
        modal.hide()

      canceled = ->
        deferred.reject()
        modal.hide()

      $rootScope.modalConfirmOK = ->
        confirmed()

      $rootScope.modalConfirmCancel = ->
        canceled()

      $rootScope.modalConfirmKeyPressed = ($event) ->
        if $event.keyCode is 13 # ENTER
          canceled()

      modal.$promise.then(modal.show)

    deferred.promise

pwgServices.factory "pwgModal", ['$q', '$modal', '$rootScope', pwgModal]
