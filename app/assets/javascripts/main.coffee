###############################################################################
# Some non-Angular stuff.
###############################################################################

###############################################################################
# Angular JS stuff
###############################################################################

requiredModules = ['ngRoute',
                   'ngAnimate',
                   'route-segment',
                   'view-segment',
                   'ngCookies',
                   'ui.utils',
                   'tableSort',
                   'Mac',
                   'pwguard-services',
                   'pwguard-directives']

# Angular.js configuration function. Passed into the actual application, below,
# when it is created.
configApp = ($routeSegmentProvider,
             $routeProvider,
             $locationProvider) ->

  # For now, don't use this. Allow Angular to use its "#" URLs. This approach
  # simplifies things on the backend, since it doesn't result in backend
  # routing issues. (That is, the Play! router ignores hash fragments.)

  #$locationProvider.html5Mode(true).hashPrefix('!')

  window.setRoutes $routeSegmentProvider, $routeProvider

# Initialize the application by storing some data and functions into the
# root scope. Invoked when the app is defined, below.
initApp = ($rootScope,
           $http,
           $routeSegment,
           $location,
           $timeout,
           pwgAjax,
           pwgFlash) ->

  $rootScope.loggedInUser  = null
  $rootScope.$routeSegment = $routeSegment
  $rootScope.segmentOnLoad = window.segmentForURL($location.path())
  $rootScope.initializing  = true

  pwgFlash.init() # initialize the flash service
  ###
  $rootScope.$watch "loggedInUser", (user, prevUser) ->
    return if user is prevUser

    if user?
      segment = $rootScope.segmentOnLoad
      if segment?
        # We've logged in. Only honor the browser-specified URL if it's not
        # one of the pre-login ones.
        unless segment[0..4] is "home"
          segment = "home"
      else
        segment = "home"
      $rootScope.redirectToSegment segment
    else
      $rootScope.redirectToSegment "login"
  ###

  # Page-handling.

  # Convenient way to show a page/segment

  $rootScope.redirectToSegment = (segment) ->
    url = $rootScope.pathForSegment segment
    if url?
      #console.log "Redirecting to #{url}"
      $location.path(url)
    else
      console.log "(BUG) No URL for segment #{segment}"

  $rootScope.segmentIsActive = (segment) ->
    ($routeSegment.name is segment) or ($routeSegment.startsWith("#{segment}."))

  $rootScope.pathForSegment = window.pathForSegment
  $rootScope.hrefForSegment = window.hrefForSegment

  $rootScope.loggedIn = ->
    $rootScope.loggedInUser?

  $rootScope.saveLoggedInUser = (user) ->
    $rootScope.loggedInUser =
      email:       user.email
      admin:       user.admin
      displayName: user.displayName
      firstName:   user.firstName
      lastName:    user.lastName
      isMobile:    user.isMobile

  # On initial load or reload, we need to determine whether the user is
  # still logged in, since a reload clears everything in the browser.

  $rootScope.redirectIfNotLoggedIn

  onSuccess = (response) ->
    $rootScope.initializing = false
    if response.loggedIn
      $rootScope.loggedInUser = response.user
      $rootScope.redirectToSegment("home")
    else
      $rootScope.loggedInUser = null
      $rootScope.redirectToSegment("login")

  onFailure = (response) ->
    $rootScope.initializing = false
    $rootScope.loggedInUser = null
    $rootScope.redirectToSegment("login")

  checkUser = ->
    url = $("#config").data("logged-in-user-url")
    pwgAjax.post(url, {}, onSuccess, onFailure)

  checkUser()

# The app itself.
pwguardApp = angular.module('PWGuardApp', requiredModules)
pwguardApp.config configApp
pwguardApp.run initApp

# Instantiating the module this way, rather than via "ng-app", provides
# better browser console errors.
###
try
  angular.bootstrap document, ['PWGuardApp', requiredModules]
catch e
  console.error e.stack or e.message or e
  throw e
###

###############################################################################
# Controllers
###############################################################################

# ---------------------------------------------------------------------------
# Main controller
# ---------------------------------------------------------------------------

mainCtrl = ($scope, $rootScope, macModal, $q) ->

  $scope.dialogConfirmTitle   = null
  $scope.dialogConfirmMessage = null

  deferred = null

  $scope.ok = ->
    macModal.hide ->
      deferred.resolve()
      deferred = null

  $scope.cancel = ->
    macModal.hide ->
      deferred.reject()
      deferred = null

  # Shows an appropriate confirmation dialog, depending on whether the user
  # is mobile or not. Returns a promise (via $q) that resolves on confirmation
  # and rejects on cancel.
  #
  # Parameters:
  #   message - the confirmation message
  #   title   - optional title for the dialog, if supported
  $scope.confirm = (message, title) ->
    deferred = $q.defer()

    if $rootScope.loggedInUser.isMobile
      if confirm message
        deferred.resolve()
      else
        deferred.reject()

    else
      $scope.dialogConfirmTitle   = title
      $scope.dialogConfirmMessage = message

      macModal.show 'confirm-dialog'

    deferred.promise

pwguardApp.controller 'MainCtrl', ['$scope', '$rootScope', 'modal', '$q', mainCtrl]

# ---------------------------------------------------------------------------
# Navigation bar controller
# ---------------------------------------------------------------------------

pwguardApp.controller 'NavbarCtrl', ($scope, $rootScope, pwgAjax) ->
  $scope.logout = () ->
    # NOTE: See https://groups.google.com/forum/#!msg/angular/bsTbZ86WAY4/gdpKwc4f7ToJ
    #
    # Specifically, see Majid Burney's response: "They've only disallowed
    # accessing DOM nodes in expressions, not in directives. Your code is only
    # broken because of Coffeescript's bad habit of automatically returning the
    # last value in a function's scope. Angular detects that the function has
    # returned a DOM node and throws an exception to keep you safe. Add an
    # explicit "return" to the end of each of those functions and they should
    # work fine."
    #
    # So, this means the confirm call can't be the last thing in the
    # function.

    if $rootScope.loggedIn()
      $scope.confirm("Really log out?", "Confirm log out").then (result) ->
        always = () ->
          $rootScope.loggedInUser = null

        onSuccess = (response) ->
          always()

        onFailure = (response) ->
          console.log "WARNING: Server logout error. #{response.status}"
          always()

        url = $("#config").data("logout-url")

        pwgAjax.post(url, {}, onSuccess, onFailure)

# ---------------------------------------------------------------------------
# Login controller
# ---------------------------------------------------------------------------

pwguardApp.controller 'LoginCtrl', ($scope, $rootScope, pwgAjax, pwgFlash) ->
  $scope.email     = null
  $scope.password  = null
  $scope.canSubmit = false

  #### DEBUG
  $scope.email = "admin@example.com"; $scope.password = "admin"
  #### END DEBUG

  $scope.$watch 'email', (newValue, oldValue) ->
    checkSubmit()

  $scope.$watch 'password', (newValue, oldValue) ->
    checkSubmit()

  $scope.login = ->
    if $scope.canSubmit
      handleLogin = (data) ->
        $rootScope.saveLoggedInUser(data.user)
        $rootScope.redirectToSegment('home')

      handleFailure = (data) ->
        # Nothing to do.
        return

      url = $("#config").data("login-url")
      data =
        email: $scope.email
        password: $scope.password

      pwgAjax.post url, data, handleLogin, handleFailure

  $scope.clear = ->
    $scope.email    = null
    $scope.password = null

  checkSubmit = ->
    $scope.canSubmit = nonEmpty($scope.email) and nonEmpty($scope.password)


  nonEmpty = (s) ->
    s? and s.trim().length > 0

# ---------------------------------------------------------------------------
# Search controller
# ---------------------------------------------------------------------------

pwguardApp.controller 'HomeCtrl', ($scope, $rootScope) ->
  return
