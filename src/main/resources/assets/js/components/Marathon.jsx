/** @jsx React.DOM */

define([
  "mousetrap",
  "React",
  "Underscore",
  "constants/States",
  "models/AppCollection",
  "models/DeploymentCollection",
  "jsx!components/AppListComponent",
  "jsx!components/AppModalComponent",
  "jsx!components/NewAppModalComponent"
], function(Mousetrap, React, _, States, AppCollection, DeploymentCollection,
    AppListComponent, AppModalComponent, NewAppModalComponent) {
  "use strict";

  var UPDATE_INTERVAL = 5000;

  return React.createClass({
    displayName: "Marathon",

    getInitialState: function() {
      return {
        activeApp: null,
        activeTask: null,
        appVersionsFetchState: States.STATE_LOADING,
        collection: new AppCollection(),
        deployments: new DeploymentCollection(),
        fetchState: States.STATE_LOADING,
        modalClass: null,
        tasksFetchState: States.STATE_LOADING
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
        } else if (this.state.modalClass === AppModalComponent) {
          this.setPollResource(this.fetchTasks);
        } else {
          this.stopPolling();
        }
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
        reset: true,
        success: function() {
          this.setState({fetchState: States.STATE_SUCCESS});
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

    fetchTasks: function() {
      if (this.state.activeApp != null) {
        this.state.activeApp.tasks.fetch({
          error: function() {
            this.setState({tasksFetchState: States.STATE_ERROR});
          }.bind(this),
          success: function(collection, response) {
            // update changed attributes in app
            this.state.activeApp.update(response.app);
            this.setState({tasksFetchState: States.STATE_SUCCESS});
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
        modalClass: null,
        tasksFetchState: States.STATE_LOADING,
        appVersionsFetchState: States.STATE_LOADING
      });
    },

    handleShowTaskDetails: function(task, callback) {
      this.setState({activeTask: task}, function() {
        callback();
      }.bind(this));
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
        this.setState({appVersionsFetchState: States.STATE_LOADING});
        // refresh app versions
        this.fetchAppVersions();
      }
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
      if (this.state.activeApp != null) {
        var app = this.state.activeApp;
        app.setVersion(version);
        app.save(
          null,
          {
            error: function(data, response) {
              var msg = response.responseJSON.message || response.statusText;
              alert("Could not update to chosen version: " + msg);
            },
            success: function() {
              // refresh app versions
              this.fetchAppVersions();
            }.bind(this)
          });
      }
    },

    scaleApp: function(instances) {
      if (this.state.activeApp != null) {
        var app = this.state.activeApp;
        app.save(
          {instances: instances},
          {
            error: function(data, response) {
              var msg = response.responseJSON.message || response.statusText;
              alert("Not scaling: " + msg);
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
          error: function(data, response) {
            var msg = response.responseJSON.message || response.statusText;
            alert("Could not suspend: " + msg);
          },
          success: function() {
            // refresh app versions
            this.fetchAppVersions();
          }.bind(this)
        });
      }
    },

    poll: function() {
      this._pollResource();
    },

    setPollResource: function(func) {
      // Kill any poll that is in flight to ensure it doesn't fire after having changed
      // the `_pollResource` function.
      this.stopPolling();
      this._pollResource = func;
      this.startPolling();
    },

    startPolling: function() {
      if (this._interval == null) {
        this.poll();
        this._interval = setInterval(this.poll, UPDATE_INTERVAL);
      }
    },

    stopPolling: function() {
      if (this._interval != null) {
        clearInterval(this._interval);
        this._interval = null;
      }
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
              activeTask={this.state.activeTask}
              appVersionsFetchState={this.state.appVersionsFetchState}
              destroyApp={this.destroyApp}
              fetchTasks={this.fetchTasks}
              fetchAppVersions={this.fetchAppVersions}
              model={this.state.activeApp}
              onDestroy={this.handleModalDestroy}
              onShowTaskDetails={this.handleShowTaskDetails}
              onShowTaskList={this.handleShowTaskList}
              onTasksKilled={this.handleTasksKilled}
              rollBackApp={this.rollbackToAppVersion}
              scaleApp={this.scaleApp}
              suspendApp={this.suspendApp}
              tasksFetchState={this.state.tasksFetchState}
              ref="modal" />
          );
        } else if (this.state.modalClass === NewAppModalComponent) {
          modal = (
            <NewAppModalComponent
              model={this.state.activeApp}
              onDestroy={this.handleModalDestroy}
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
              ref="appList" />
          </div>
          {modal}
        </div>
      );
    }
  });
});
