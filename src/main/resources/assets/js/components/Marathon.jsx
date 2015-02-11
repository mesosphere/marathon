/** @jsx React.DOM */

var _ = require("underscore");
var Mousetrap = require("mousetrap");
var React = require("react/addons");
var States = require("../constants/States");
var AppCollection = require("../models/AppCollection");
var DeploymentCollection = require("../models/DeploymentCollection");
var AppListComponent = require("../components/AppListComponent");
var AboutModalComponent = require("../components/modals/AboutModalComponent");
var AppModalComponent = require("../components/AppModalComponent");
var DeploymentsListComponent =
  require("../components/DeploymentsListComponent");
var NewAppModalComponent = require("../components/NewAppModalComponent");
var TabPaneComponent = require("../components/TabPaneComponent");
var TogglableTabsComponent = require("../components/TogglableTabsComponent");
var NavTabsComponent = require("../components/NavTabsComponent");

var UPDATE_INTERVAL = 5000;

var tabs = [
  {id: "apps", text: "Apps"},
  {id: "deployments", text: "Deployments"}
];

var Marathon = React.createClass({
  displayName: "Marathon",

  propTypes: {
    router: React.PropTypes.object.isRequired
  },

  getInitialState: function () {
    return {
      activeAppId: null,
      activeApp: null,
      activeAppView: null,
      activeTask: null,
      activeTabId: tabs[0].id,
      appVersionsFetchState: States.STATE_LOADING,
      collection: new AppCollection(),
      deployments: new DeploymentCollection(),
      deploymentsFetchState: States.STATE_LOADING,
      fetchState: States.STATE_LOADING,
      modalClass: null,
      route: null,
      setAppView: _.noop,
      tasksFetchState: States.STATE_LOADING
    };
  },

  componentDidMount: function () {
    var router = this.props.router;

    router.on("route", this.setRoute);
    router.on("route:about", this.setRouteAbout);
    router.on("route:apps", this.setRouteApps);
    router.on("route:deployments",
      _.bind(this.activateTab, this, "deployments")
    );
    router.on("route:newapp", this.setRouteNewapp);

    // Override Mousetrap's `stopCallback` to allow "esc" to trigger even within
    // input elements so the new app modal can be closed via "esc".
    var mousetrapOriginalStopCallback = Mousetrap.stopCallback;
    Mousetrap.stopCallback = function (e, element, combo) {
      if (combo === "esc" || combo === "escape") { return false; }
      return mousetrapOriginalStopCallback.apply(null, arguments);
    };

    Mousetrap.bind("esc", function () {
      if (this.refs.modal != null) {
        this.modalDestroy();
      }
    }.bind(this));

    Mousetrap.bind("c", function () {
      router.navigate("newapp", {trigger: true});
    }.bind(this), "keyup");

    Mousetrap.bind("g a", function () {
      if (this.state.modalClass == null) {
        router.navigate("apps", {trigger: true});
      }
    }.bind(this));

    Mousetrap.bind("g d", function () {
      if (this.state.modalClass == null) {
        router.navigate("deployments", {trigger: true});
      }
    }.bind(this));

    Mousetrap.bind("#", function () {
      if (this.state.modalClass === AppModalComponent) {
        this.destroyApp();
      }
    }.bind(this));

    Mousetrap.bind("shift+,", function () {
      router.navigate("about", {trigger: true});
    }.bind(this));

    this.updatePolling();
  },

  componentDidUpdate: function (prevProps, prevState) {
    /* jshint eqeqeq: false */
    if (prevState.activeApp != this.state.activeApp) {
      this.updatePolling();
    }

    if (this.state.activeAppId) {
      this.state.setAppView(this.state.activeAppId, this.state.activeAppView);
    }

    var route = this.state.route;
    var router = this.props.router;

    if (route) {
      router.lastRoute = _.extend(router.lastRoute, {
        route: route.route,
        params: route.params,
        hash: router.currentHash()
      });
    }
  },

  componentWillUnmount: function () {
    this.stopPolling();
  },

  setRoute: function (route, params) {
    this.setState({
      route: {
        name: route,
        params: params
      }
    });
  },

  setRouteAbout: function () {
    this.modalDestroy();
    this.setState({
      modalClass: AboutModalComponent
    });
  },

  setRouteApps: function (appid, view) {
    if (appid != null) {
      if (this.state.activeAppId !== appid) {
        this.modalDestroy();
      }

      this.setState({
        activeAppId: appid,
        // activeApp could be undefined here, if this route is triggered on
        // page load, because the collection is not ready.
        activeApp: this.state.collection.get(appid),
        modalClass: AppModalComponent,
        activeAppView: view
      });
    } else {
      this.activateTab("apps");
    }
  },

  setRouteNewapp: function () {
    this.modalDestroy();
    this.setState({
      modalClass: NewAppModalComponent
    });
  },

  fetchApps: function () {
    this.state.collection.fetch({
      error: function () {
        this.setState({fetchState: States.STATE_ERROR});
      }.bind(this),
      success: function () {
        var state = this.state;
        this.fetchDeployments();
        var activeApp = state.activeAppId ?
          state.collection.get(state.activeAppId) :
          null;

        this.setState({
          fetchState: States.STATE_SUCCESS,
          activeApp: activeApp
        });
      }.bind(this)
    });
  },

  fetchAppVersions: function () {
    if (this.state.activeApp != null) {
      this.state.activeApp.versions.fetch({
        error: function () {
          this.setState({appVersionsFetchState: States.STATE_ERROR});
        }.bind(this),
        success: function () {
          this.setState({appVersionsFetchState: States.STATE_SUCCESS});
        }.bind(this)
      });
    }
  },

  fetchDeployments: function () {
    this.state.deployments.fetch({
      error: function () {
        this.setState({deploymentsFetchState: States.STATE_ERROR});
      }.bind(this),
      success: function (response) {
        tabs[1].badge = response.models.length;
        this.setState({deploymentsFetchState: States.STATE_SUCCESS});
      }.bind(this)
    });
  },

  fetchTasks: function () {
    if (this.state.activeApp != null) {
      this.state.activeApp.tasks.fetch({
        error: function () {
          this.setState({tasksFetchState: States.STATE_ERROR});
        }.bind(this),
        success: function (collection, response) {
          this.fetchDeployments();
          // update changed attributes in app
          this.state.activeApp.update(response.app);
          this.setState({tasksFetchState: States.STATE_SUCCESS});
        }.bind(this)
      });
    }
  },

  handleSetAppView: function (setAppView) {
    this.setState({
      setAppView: setAppView
    });
  },

  handleAppCreate: function (appModel, options) {
    this.state.collection.create(appModel, options);
  },

  modalDestroy: function () {
    if (!this.state.modalClass) {
      return;
    }

    var router = this.props.router;

    if (router.lastRoute.hash === router.currentHash()) {
      router.navigate(this.state.activeTabId, {trigger: true});
    }

    this.setState({
      activeAppId: null,
      activeApp: null,
      modalClass: null,
      tasksFetchState: States.STATE_LOADING,
      appVersionsFetchState: States.STATE_LOADING
    });
  },

  handleShowTaskDetails: function (task, callback) {
    this.setState({activeTask: task}, function () {
      callback();
    }.bind(this));
  },

  handleShowTaskList: function () {
    this.setState({activeTask: null});
  },

  handleTasksKilled: function (options) {
    var instances;
    var app = this.state.activeApp;
    var _options = options || {};
    if (_options.scale) {
      instances = app.get("instances");
      app.set("instances", instances - 1);
      this.setState({appVersionsFetchState: States.STATE_LOADING});
      // refresh app versions
      this.fetchAppVersions();
    }
  },

  destroyApp: function () {
    var app = this.state.activeApp;

    if (confirm("Destroy app '" + app.id + "'?\nThis is irreversible.")) {
      app.destroy({
        error: function (data, response) {
          var msg = response.responseJSON.message || response.statusText;
          alert("Error destroying app '" + app.id + "': " + msg);
        },
        success: function () {
          this.setState({
            activeApp: null,
            modalClass: null
          });
        }.bind(this),
        wait: true
      });
    }
  },

  destroyDeployment: function (deployment, options, component) {
    component.setLoading(true);

    var forceStop = options.forceStop;
    var confirmMessage = !forceStop ?
      "Destroy deployment of apps: '" + deployment.affectedAppsString() +
        "'?\nDestroying this deployment will create and start a new " +
        "deployment to revert the affected app to its previous version." :
      "Stop deployment of apps: '" + deployment.affectedAppsString() +
        "'?\nThis will stop the deployment immediately and leave it in the " +
        "current state.";

    if (confirm(confirmMessage)) {
      setTimeout(function () {
        deployment.destroy({
          error: function (data, response) {
            // Coming from async forceStop
            if (response.status === 202) {
              return;
            }

            var msg = response.responseJSON &&
              response.responseJSON.message ||
              response.statusText;
            if (msg) {
              alert("Error destroying app '" + deployment.id + "': " + msg);
            }
          },
          forceStop: forceStop,
          wait: !forceStop
        });
      }, 1000);
    } else {
      component.setLoading(false);
    }
  },

  rollbackToAppVersion: function (version) {
    if (this.state.activeApp != null) {
      var app = this.state.activeApp;
      app.setVersion(version);
      app.save(
        null,
        {
          error: function (data, response) {
            var msg = response.responseJSON.message || response.statusText;
            alert("Could not update to chosen version: " + msg);
          },
          success: function () {
            // refresh app versions
            this.fetchAppVersions();
          }.bind(this)
        });
    }
  },

  scaleApp: function (instances) {
    if (this.state.activeApp != null) {
      var app = this.state.activeApp;
      app.save(
        {instances: instances},
        {
          error: function (data, response) {
            var msg = response.responseJSON.message || response.statusText;
            alert("Not scaling: " + msg);
          },
          success: function () {
            // refresh app versions
            this.fetchAppVersions();
          }.bind(this)
        }
      );

      if (app.validationError != null) {
        // If the model is not valid, revert the changes to prevent the UI
        // from showing an invalid state.
        app.update(app.previousAttributes());
        alert("Not scaling: " + app.validationError[0].message);
      }
    }
  },

  suspendApp: function () {
    if (confirm("Suspend app by scaling to 0 instances?")) {
      this.state.activeApp.suspend({
        error: function (data, response) {
          var msg = response.responseJSON.message || response.statusText;
          alert("Could not suspend: " + msg);
        },
        success: function () {
          // refresh app versions
          this.fetchAppVersions();
        }.bind(this)
      });
    }
  },

  poll: function () {
    this._pollResource();
  },

  setPollResource: function (func) {
    // Kill any poll that is in flight to ensure it doesn't fire after having changed
    // the `_pollResource` function.
    this.stopPolling();
    this._pollResource = func;
    this.startPolling();
  },

  startPolling: function () {
    if (this._interval == null) {
      this.poll();
      this._interval = setInterval(this.poll, UPDATE_INTERVAL);
    }
  },

  stopPolling: function () {
    if (this._interval != null) {
      clearInterval(this._interval);
      this._interval = null;
    }
  },

  updatePolling: function () {
    var id = this.state.activeTabId;

    if (this.state.activeApp) {
      this.setPollResource(this.fetchTasks);
    } else if (id === tabs[0].id) {
      this.setPollResource(this.fetchApps);
    } else if (id === tabs[1].id) {
      this.setPollResource(this.fetchDeployments);
    }
  },

  activateTab: function (id) {
    this.setState({
      activeTabId: id
    });
  },

  routeAbout: function () {
    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <AboutModalComponent
        onDestroy={this.modalDestroy}
        ref="modal" />
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  },

  routeApps: function () {
    var activeApp = this.state.collection.get(this.state.activeAppId);
    if (!activeApp) {
      return;
    }

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <AppModalComponent
        activeTask={this.state.activeTask}
        appVersionsFetchState={this.state.appVersionsFetchState}
        destroyApp={this.destroyApp}
        fetchTasks={this.fetchTasks}
        fetchAppVersions={this.fetchAppVersions}
        handleSetAppView={this.handleSetAppView}
        model={activeApp}
        onDestroy={this.modalDestroy}
        onShowTaskDetails={this.handleShowTaskDetails}
        onShowTaskList={this.handleShowTaskList}
        onTasksKilled={this.handleTasksKilled}
        rollBackApp={this.rollbackToAppVersion}
        scaleApp={this.scaleApp}
        suspendApp={this.suspendApp}
        tasksFetchState={this.state.tasksFetchState}
        router={this.props.router}
        ref="modal" />
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  },

  routeNewapp: function () {
    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <NewAppModalComponent
        onDestroy={this.modalDestroy}
        onCreate={this.handleAppCreate}
        ref="modal" />
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  },

  render: function () {
    var modal;
    var route = this.state.route;

    if (route) {
      var routeName = route.name.charAt(0).toUpperCase() + route.name.slice(1);
      var routeFunction = this["route" + routeName];

      if (_.isFunction(routeFunction)) {
        modal = routeFunction.apply(this, route.params);
      }
    }

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div>
        <nav className="navbar navbar-inverse navbar-static-top" role="navigation">
         <div className="container-fluid">
            <div className="navbar-header">
              <a className="navbar-brand" href="/">
                <img width="160" height="27" alt="Marathon" src="/img/marathon-logo.png" />
              </a>
            </div>
            <NavTabsComponent
              activeTabId={this.state.activeTabId}
              className="navbar-nav nav-tabs-unbordered"
              tabs={tabs} />
            <ul className="nav navbar-nav navbar-right">
              <li>
                <a href="#about">
                  About
                </a>
              </li>
              <li>
                <a href="https://mesosphere.github.io/marathon/docs/" target="_blank">
                  Docs ⇗
                </a>
              </li>
            </ul>
          </div>
        </nav>
        <div className="container-fluid">
          <TogglableTabsComponent activeTabId={this.state.activeTabId} >
            <TabPaneComponent id="apps">
              <a href="#newapp" className="btn btn-success navbar-btn" >
                + New App
              </a>
              <AppListComponent
                collection={this.state.collection}
                fetchState={this.state.fetchState}
                router={this.props.router}
                ref="appList" />
            </TabPaneComponent>
            <TabPaneComponent
                id="deployments"
                onActivate={this.props.fetchAppVersions} >
              <DeploymentsListComponent
                deployments={this.state.deployments}
                destroyDeployment={this.destroyDeployment}
                fetchState={this.state.deploymentsFetchState} />
            </TabPaneComponent>
          </TogglableTabsComponent>
        </div>
        {modal}
      </div>
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  }
});

module.exports = Marathon;
