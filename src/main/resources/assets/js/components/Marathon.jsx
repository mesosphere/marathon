/** @jsx React.DOM */

define([
  "mousetrap",
  "React",
  "Underscore",
  "models/AppCollection",
  "models/DeploymentCollection",
  "jsx!components/AppListComponent",
  "jsx!components/AppModalComponent",
  "jsx!components/NewAppModalComponent"
], function(Mousetrap, React, _, AppCollection, DeploymentCollection,
    AppListComponent, AppModalComponent, NewAppModalComponent) {
  "use strict";

  var STATES = {
    STATE_LOADING: 0,
    STATE_ERROR: 1,
    STATE_SUCCESS: 2
  };

  var UPDATE_INTERVAL_APPS = 5000;
  var UPDATE_INTERVAL_TASKS = 2000;

  return React.createClass({
    displayName: "Marathon",

    getInitialState: function() {
      return {
        activeApp: null,
        activeTask: null,
        appVersionsFetchState: STATES.STATE_LOADING,
        collection: new AppCollection(),
        deployments: new DeploymentCollection(),
        fetchState: STATES.STATE_LOADING,
        modalClass: null,
        tasksFetchState: STATES.STATE_LOADING
      };
    },

    componentDidMount: function() {
      // Override Mousetrap's `stopCallback` to allow "esc" to trigger even within
      // input elements so the new app modal can be closed via "esc".
      var mousetrapOriginalStopCallback = Mousetrap.stopCallback;
      Mousetrap.stopCallback = function(e, element, combo) {
        if (combo === "esc" || combo === "escape") { return false; }
        return mousetrapOriginalStopCallback.apply(null, arguments);
      };

      Mousetrap.bind("esc", function() {
        if (this.refs.modal != null) {
          this.refs.modal.destroy();
        }
      }.bind(this));

      Mousetrap.bind("c", function() {
        this.showNewAppModal(); }.bind(this), "keyup");

      Mousetrap.bind("#", function() {
        if (this.state.modalClass === AppModalComponent) {
          this.destroyApp();
        }
      }.bind(this));

      this.startPollingApps();
    },

    componentDidUpdate: function(prevProps, prevState) {
      if (prevState.modalClass !== this.state.modalClass) {
        // No `modalClass` means the modal went from open to closed. Start
        // polling in that case, otherwise stop polling since the modal went
        // from closed to open.
        this.state.modalClass === null ?
          this.refs.appList.startPolling() :
          this.refs.appList.stopPolling();

      }
    },

    componentWillUnmount: function() {
      this.stopPollingApps();
    },

    fetchResource: function() {
      var _this = this;

      this.state.collection.fetch({
        error: function() {
          _this.setState({fetchState: STATES.STATE_ERROR});
        },
        reset: true,
        success: function() {
          _this.setState({fetchState: STATES.STATE_SUCCESS});
        }
      });
    },

    fetchAppVersions: function() {
      if (this.state.activeApp != null) {
        this.state.activeApp.versions.fetch({
          error: function() {
            this.setState({appVersionsFetchState: STATES.STATE_ERROR});
            // ctx.forceUpdate();
          }.bind(this),
          success: function() {
            this.setState({appVersionsFetchState: STATES.STATE_SUCCESS});
            // ctx.forceUpdate();
          }.bind(this)
        });
      }
    },

    fetchTasks: function(ctx) {
      if (this.state.activeApp != null) {
        this.state.activeApp.tasks.fetch({
          error: function() {
            this.setState({tasksFetchState: STATES.STATE_ERROR});
            ctx && _.isFunction(ctx.forceUpdate) && ctx.forceUpdate();
          }.bind(this),
          success: function(collection, response) {
            // update changed attributes in app
            this.state.activeApp.update(response.app);
            this.setState({tasksFetchState: STATES.STATE_SUCCESS});
            ctx && _.isFunction(ctx.forceUpdate) && ctx.forceUpdate();
          }.bind(this)
        });
      }
    },

    handleAppCreate: function(appModel, options) {
      this.state.collection.create(appModel, options);
    },

    handleModalDestroy: function() {
      this.setState({
        activeApp: null,
        modalClass: null
      });
    },

    handleShowTaskDetails: function(task) {
      this.setState({activeTask: task});
    },

    handleShowTaskList: function() {
      this.setState({activeTask: null});
    },

    handleTasksKilled: function(options) {
      var instances;
      var app = this.state.activeApp;
      var _options = options || {};
      if (_options.scale) {
        instances = app.get("instances");
        app.set("instances", instances - 1);
        this.setState({appVersionsFetchState: STATES.STATE_LOADING});
        // refresh app versions
        this.fetchAppVersions();
      }

      // Force an update since React doesn't know a key was removed from
      // `selectedTasks`.
      this.forceUpdate();
    },

    destroyApp: function() {
      var app = this.state.activeApp;
      if (confirm("Destroy app '" + app.get("id") + "'?\nThis is irreversible.")) {
        // Send force option to ensure the UI is always able to kill apps
        // regardless of deployment state.
        app.destroy({
          url: _.result(app, "url") + "?force=true"
        });
      }
    },

    rollbackToAppVersion: function(version) {
      var app = this.state.activeApp;
      app.setVersion(version);
      app.save(
        null,
        {
          error: function() {
            this.setState({appVersionsFetchState: STATES.STATE_ERROR});
          }.bind(this),
          success: function() {
            // refresh app versions
            this.fetchAppVersions();
          }.bind(this)
        });
    },

    rollbackToPreviousAttributes: function() {
      var app = this.state.activeApp;
      app.update(app.previousAttributes());
    },

    scaleApp: function(instances) {
      if (this.state.activeApp != null) {
        var app = this.state.activeApp;
        app.save(
          {instances: instances},
          {
            error: function() {
              this.setState({appVersionsFetchState: STATES.STATE_ERROR});
            },
            success: function() {
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

    suspendApp: function() {
      if (confirm("Suspend app by scaling to 0 instances?")) {
        this.state.activeApp.suspend({
          error: function() {
            this.setState({appVersionsFetchState: STATES.STATE_ERROR});
          }.bind(this),
          success: function() {
            // refresh app versions
            this.fetchAppVersions();
          }.bind(this)
        });
      }
    },

    startPollingApps: function() {
      if (this._intervalApps == null) {
        this.fetchResource();
        this._intervalApps = setInterval(this.fetchResource, UPDATE_INTERVAL_APPS);
      }
    },

    stopPollingApps: function() {
      if (this._intervalApps != null) {
        clearInterval(this._intervalApps);
        this._intervalApps = null;
      }
    },

    startPollingTasks: function() {
      if (this._intervalTasks == null) {
        this._intervalTasks = setInterval(this.fetchTasks, UPDATE_INTERVAL_TASKS);
      }
    },

    stopPollingTasks: function() {
      clearInterval(this._intervalTasks);
      this._intervalTasks = null;
    },

    showAppModal: function(app) {
      if (this.state.modalClass !== null) {
        return;
      }

      this.setState({
        activeApp: app,
        modalClass: AppModalComponent
      });
    },

    showNewAppModal: function(event) {
      if (this.state.modalClass !== null) {
        return;
      }

      this.setState({
        modalClass: NewAppModalComponent
      });
    },

    render: function() {
      var modal;
      if (this.state.modalClass !== null) {
        /* jshint trailing:false, quotmark:false, newcap:false */
        if (this.state.modalClass === AppModalComponent) {
          modal = (
            <AppModalComponent
              model={this.state.activeApp}
              onDestroy={this.handleModalDestroy}
              ref="modal" />
          );
        } else if (this.state.modalClass === NewAppModalComponent) {
          modal = (
            <NewAppModalComponent
              appVersionsFetchState={this.state.appVersionsFetchState}
              model={this.state.activeApp}
              destroyApp={this.destroyApp}
              fetchTasks={this.fetchTasks}
              fetchAppVersions={this.fetchAppVersions}
              onDestroy={this.handleModalDestroy}
              onShowTaskDetails={this.handleShowTaskDetails}
              onShowTaskList={this.handleShowTaskList}
              onTasksKilled={this.handleTasksKilled}
              rollBackApp={this.rollbackToAppVersion}
              scaleApp={this.scaleApp}
              STATES={STATES}
              suspendApp={this.suspendApp}
              tasksFetchState={this.state.tasksFetchState}
              ref="modal" />
          );
        }
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <div>
          <nav className="navbar navbar-inverse" role="navigation">
           <div className="container-fluid">
              <a className="navbar-brand" href="/">
                <img width="160" height="27" alt="Marathon" src="/img/marathon-logo.png" />
              </a>
              <button type="button" className="btn btn-success navbar-btn pull-right"
                  onClick={this.showNewAppModal}>
                + New App
              </button>
            </div>
          </nav>
          <div className="container-fluid">
            <AppListComponent
              collection={this.state.collection}
              deployments={this.state.deployments}
              onSelectApp={this.showAppModal}
              fetchState={this.state.fetchState}
              STATES={STATES}
              ref="appList" />
          </div>
          {modal}
        </div>
      );
    }
  });
});
