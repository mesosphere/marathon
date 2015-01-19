/** @jsx React.DOM */

var Mousetrap = require("mousetrap");
var React = require("react/addons");
var _ = require("underscore");
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
var pollResourceMixin = require("../mixins/pollResourceMixin");

var tabs = [
  {id: "apps", text: "Apps"},
  {id: "deployments", text: "Deployments"}
];

var Marathon = React.createClass({
  displayName: "Marathon",

  mixins: [pollResourceMixin],

  propTypes: {
    router: React.PropTypes.object.isRequired
  },

  getInitialState: function() {
    return {
      activeAppId: null,
      activeApp: null,
      activeTabId: tabs[0].id,
      appVersionsFetchState: States.STATE_LOADING,
      collection: new AppCollection(),
      deployments: new DeploymentCollection(),
      deploymentsFetchState: States.STATE_LOADING,
      fetchState: States.STATE_LOADING,
      modalClass: null,
      route: null
    };
  },

  componentDidMount: function() {
    var router = this.props.router;

    router.on("route", function (route, params) {
      this.setState({
        route: {
          name: route,
          params: params
        }
      });
    }.bind(this));

    router.on("route:about", function() {
      this.modalDestroy();
      this.setState({
        modalClass: AboutModalComponent
      });
    }.bind(this));

    router.on("route:apps", function(appid, view) {
      if(appid) {
        if(this.state.activeAppId !== appid) {
          this.modalDestroy();
        }

        this.setState({
          activeAppId: appid,
          activeApp: this.state.collection.get("/" + appid),
          modalClass: AppModalComponent
        });
      } else {
        this.activateTab("apps");
      }
    }.bind(this));

    router.on("route:deployments", function() {
      this.activateTab("deployments");
    }.bind(this));

    router.on("route:newapp", function() {
      this.modalDestroy();
      this.setState({
        modalClass: NewAppModalComponent
      });
    }.bind(this));

    // Override Mousetrap's `stopCallback` to allow "esc" to trigger even within
    // input elements so the new app modal can be closed via "esc".
    var mousetrapOriginalStopCallback = Mousetrap.stopCallback;
    Mousetrap.stopCallback = function(e, element, combo) {
      if (combo === "esc" || combo === "escape") { return false; }
      return mousetrapOriginalStopCallback.apply(null, arguments);
    };

    Mousetrap.bind("esc", function() {
      if (this.refs.modal != null) {
        this.modalDestroy();
      }
    }.bind(this));

    Mousetrap.bind("c", function() {
      router.navigate("#newapp", {trigger: true});
    }.bind(this), "keyup");

    Mousetrap.bind("g a", function() {
      if(this.state.modalClass == null) {
        router.navigate("#apps", {trigger: true});
      }
    }.bind(this));

    Mousetrap.bind("g d", function() {
      if(this.state.modalClass == null) {
        router.navigate("#deployments", {trigger: true});
      }
    }.bind(this));

    Mousetrap.bind("shift+,", function() {
      router.navigate("#about", {trigger: true});
    }.bind(this));

    this.setPollResource(this.fetchApps);
  },

  componentDidUpdate: function(prevProps, prevState) {
    if (prevState.modalClass !== this.state.modalClass) {
      // No `modalClass` means the modal went from open to closed. Start
      // polling for apps in that case.
      // If `modalClass` is AppModalComponent start polling for tasks for that
      // app.
      // Otherwise stop polling since the modal went from closed to open.
      if (this.state.modalClass === null) {
        this.setPollResource(this.fetchApps);
      } else {
        this.setPollResource(this.fetchDeployments);
      }
    }

    var route = this.state.route;
    var router = this.props.router;

    if(route) {
      router.lastRoute = _.extend(router.lastRoute, {
        route: route.route,
        params: route.params,
        hash: router.currentHash()
      });
    }
  },

  componentWillUnmount: function() {
    this.stopPolling();
  },

  fetchApps: function() {
    this.state.collection.fetch({
      error: function() {
        this.setState({fetchState: States.STATE_ERROR});
      }.bind(this),
      success: function() {
        var state = this.state;
        var activeApp = (state.activeAppId) ?
          state.collection.get("/" + state.activeAppId) :
          null;

        this.fetchDeployments();

        this.setState({
          fetchState: States.STATE_SUCCESS,
          activeApp: activeApp
        });
      }.bind(this)
    });
  },

  fetchAppVersions: function() {
    if (this.state.activeApp != null) {
      this.state.activeApp.versions.fetch({
        error: function() {
          this.setState({appVersionsFetchState: States.STATE_ERROR});
        }.bind(this),
        success: function() {
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

  handleAppCreate: function (appModel, options) {
    this.state.collection.create(appModel, options);
  },

  modalDestroy: function() {
    if(!this.state.modalClass) {
      return;
    }

    var router = this.props.router;

    if(router.lastRoute.hash === router.currentHash()) {
      router.navigate("#"+this.state.activeTabId, { trigger: true });
    }

    this.setState({
      activeAppId: null,
      activeApp: null,
      modalClass: null,
      appVersionsFetchState: States.STATE_LOADING
    });
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
    this.setState({
      activeApp: null,
      modalClass: null
    });
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

  activateTab: function(id) {
    this.setState({
      activeTabId: id
    });

    if (id === tabs[0].id) {
      this.setPollResource(this.fetchApps);
    } else if (id === tabs[1].id) {
      this.setPollResource(this.fetchDeployments);
    }
  },

  routeAbout: function () {
    return (
      <AboutModalComponent
        onDestroy={this.modalDestroy}
        ref="modal" />
    );
  },

  routeApps: function(appid, view) {
    if(!appid || !this.state.activeApp) {
      return null;
    }

    return (
      <AppModalComponent
        appVersionsFetchState={this.state.appVersionsFetchState}
        destroyApp={this.destroyApp}
        fetchAppVersions={this.fetchAppVersions}
        model={this.state.activeApp}
        onDestroy={this.modalDestroy}
        onTasksKilled={this.handleTasksKilled}
        rollBackApp={this.rollbackToAppVersion}
        router={this.props.router}
        ref="modal" />
    );
  },

  routeDeployments: function() {
    return null;
  },

  routeNewapp: function() {
    return (
      <NewAppModalComponent
        model={this.state.activeApp}
        onDestroy={this.modalDestroy}
        onCreate={this.handleAppCreate}
        ref="modal" />
    );
  },

  render: function() {
    var modal;
    var route = this.state.route;

    if(route) {
      var routeName = route.name.charAt(0).toUpperCase() + route.name.slice(1);
      modal = this["route" + routeName].apply(this, route.params);
    }

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
  }
});

module.exports = Marathon;
