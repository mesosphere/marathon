/** @jsx React.DOM */

var Mousetrap = require("mousetrap");
var React = require("react/addons");
var States = require("../constants/States");
var AppCollection = require("../models/AppCollection");
var DeploymentCollection = require("../models/DeploymentCollection");
var AppListComponent = require("../components/AppListComponent");
var AboutPageComponent = require("../components/AboutPageComponent");
var AppPageComponent = require("../components/AppPageComponent");
var DeploymentsListComponent =
  require("../components/DeploymentsListComponent");
var NewAppPageComponent = require("../components/NewAppPageComponent");
var TabPaneComponent = require("../components/TabPaneComponent");
var TogglableTabsComponent = require("../components/TogglableTabsComponent");
var NavTabsComponent = require("../components/NavTabsComponent");

var UPDATE_INTERVAL = 5000;

var tabs = [
  {id: "apps", text: "Apps"},
  {id: "deployments", text: "Deployments"},
  {id: "about", text: "About", alignRight: true}
];

var Marathon = React.createClass({
  displayName: "Marathon",

  getInitialState: function () {
    return {
      activeApp: null,
      activeTask: null,
      activeTabId: tabs[0].id,
      appVersionsFetchState: States.STATE_LOADING,
      collection: new AppCollection(),
      deployments: new DeploymentCollection(),
      deploymentsFetchState: States.STATE_LOADING,
      fetchState: States.STATE_LOADING,
      pageClass: null,
      tasksFetchState: States.STATE_LOADING
    };
  },

  componentDidMount: function () {
    // Override Mousetrap's `stopCallback` to allow "esc" to trigger even within
    // input elements so the new app page can be closed via "esc".
    var mousetrapOriginalStopCallback = Mousetrap.stopCallback;
    Mousetrap.stopCallback = function (e, element, combo) {
      if (combo === "esc" || combo === "escape") { return false; }
      return mousetrapOriginalStopCallback.apply(null, arguments);
    };

    Mousetrap.bind("esc", function () {
      if (this.refs.page != null) {
        this.refs.page.destroy();
      }
    }.bind(this));

    Mousetrap.bind("c", function () {
      this.showNewAppPage();
    }.bind(this), "keyup");

    Mousetrap.bind("g a", function () {
      this.onTabClick("apps");
    }.bind(this));

    Mousetrap.bind("g d", function () {
      this.onTabClick("deployments");
    }.bind(this));

    Mousetrap.bind("#", function () {
      if (this.state.pageClass === AppPageComponent) {
        this.destroyApp();
      }
    }.bind(this));

    Mousetrap.bind("shift+,", function () {
      this.onTabClick("about");
    }.bind(this));

    this.setPollResource(this.fetchApps);
  },

  componentDidUpdate: function (prevProps, prevState) {
    if (prevState.pageClass !== this.state.pageClass) {
      // No `pageClass` means the page went from open to closed. Start
      // polling for apps in that case.
      // If `pageClass` is AppPageComponent start polling for tasks for that
      // app.
      // Otherwise stop polling since the page went from closed to open.
      if (this.state.pageClass === null) {
        this.setPollResource(this.fetchApps);
      } else if (this.state.pageClass === AppPageComponent) {
        this.setPollResource(this.fetchTasks);
      } else {
        this.stopPolling();
      }
    }
  },

  componentWillUnmount: function () {
    this.stopPolling();
  },

  fetchApps: function () {
    this.state.collection.fetch({
      error: function () {
        this.setState({fetchState: States.STATE_ERROR});
      }.bind(this),
      reset: true,
      success: function () {
        this.fetchDeployments();
        this.setState({fetchState: States.STATE_SUCCESS});
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

  handleAppCreate: function (appModel, options) {
    this.state.collection.create(appModel, options);
  },

  handlePageDestroy: function () {
    this.setState({
      activeApp: null,
      pageClass: null,
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
            pageClass: null
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

  showAppPage: function (app) {
    this.setState({
      activeApp: app,
      pageClass: AppPageComponent
    });
  },

  showNewAppPage: function () {
    this.setState({
      pageClass: NewAppPageComponent
    });
  },

  onTabClick: function (id) {
    this.setState({
      activeTabId: id,
      pageClass: null,
      activeApp: null
    });

    if (id === tabs[0].id) {
      this.setPollResource(this.fetchApps);
    } else if (id === tabs[1].id) {
      this.setPollResource(this.fetchDeployments);
    }
  },

  render: function () {
    var page;

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    if (this.state.pageClass === AppPageComponent) {
      page = (
        <AppPageComponent
          activeTask={this.state.activeTask}
          appVersionsFetchState={this.state.appVersionsFetchState}
          destroyApp={this.destroyApp}
          fetchTasks={this.fetchTasks}
          fetchAppVersions={this.fetchAppVersions}
          model={this.state.activeApp}
          onDestroy={this.handlePageDestroy}
          onShowTaskDetails={this.handleShowTaskDetails}
          onShowTaskList={this.handleShowTaskList}
          onTasksKilled={this.handleTasksKilled}
          rollBackApp={this.rollbackToAppVersion}
          scaleApp={this.scaleApp}
          suspendApp={this.suspendApp}
          tasksFetchState={this.state.tasksFetchState}
          ref="page" />
      );
    } else if (this.state.pageClass === NewAppPageComponent) {
      page = (
        <NewAppPageComponent
          model={this.state.activeApp}
          onDestroy={this.handlePageDestroy}
          onCreate={this.handleAppCreate}
          ref="page" />
      );
    } else {
      page = (
        <TogglableTabsComponent activeTabId={this.state.activeTabId} className="toggleabletabs-container">
          <TabPaneComponent id="apps">
            <button type="button" className="btn btn-success navbar-btn"
                onClick={this.showNewAppPage} >
              + New App
            </button>
            <AppListComponent
              collection={this.state.collection}
              onSelectApp={this.showAppPage}
              fetchState={this.state.fetchState}
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
          <TabPaneComponent
              id="about">
            <AboutPageComponent />
          </TabPaneComponent>
        </TogglableTabsComponent>
      );
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
            <ul className="nav navbar-nav navbar-right">
              <li>
                <a href="https://mesosphere.github.io/marathon/docs/" target="_blank">
                  Docs ⇗
                </a>
              </li>
            </ul>
            <NavTabsComponent
              activeTabId={this.state.activeTabId}
              className="navbar-nav nav-tabs-unbordered"
              onTabClick={this.onTabClick}
              tabs={tabs} />
          </div>
        </nav>
        {page}
      </div>
    );
  }
});

module.exports = Marathon;
