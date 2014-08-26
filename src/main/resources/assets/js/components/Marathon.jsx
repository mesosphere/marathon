/** @jsx React.DOM */

define([
  "mousetrap",
  "React",
  "Underscore",
  "models/AppCollection",
  "jsx!components/AppListComponent",
  "jsx!components/AppModalComponent",
  "jsx!components/NewAppModalComponent"
], function(Mousetrap, React, _, AppCollection, AppListComponent,
    AppModalComponent, NewAppModalComponent) {
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
        collection: new AppCollection(),
        fetchState: STATES.STATE_LOADING,
        tasksFetchState: STATES.STATE_LOADING,
        appVersionsFetchState: STATES.STATE_LOADING,
        activeApp: null,
        activeTask: null
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

      Mousetrap.bind("c", function() {
        this.showNewAppModal(); }.bind(this), "keyup");
      Mousetrap.bind("#", function() {
        if (this.state.modal != null && this.state.activeApp != null) {
          this.destroyApp();
        }
      }.bind(this));

      this.startPollingApps();
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
            this.forceUpdate();
          }.bind(this),
          success: function() {
            this.setState({appVersionsFetchState: STATES.STATE_SUCCESS});
            this.forceUpdate();
          }.bind(this)
        });
      }
    },

    fetchTasks: function() {
      if (this.state.activeApp != null) {
        this.state.activeApp.tasks.fetch({
          error: function() {
            this.setState({tasksFetchState: STATES.STATE_ERROR});
            this.forceUpdate();
          }.bind(this),
          success: function(collection, response) {
            // update changed attributes in app
            this.state.activeApp.update(response.app);
            this.setState({tasksFetchState: STATES.STATE_SUCCESS});
            this.forceUpdate();
          }.bind(this)
        });
      }
    },

    handleAppCreate: function(appModel, options) {
      this.state.collection.create(appModel, options);
    },

    handleModalDestroy: function() {
      this.setState({modal: null}, function () {
        this.startPollingApps();
        this.stopPollingTasks();
        this.setState({activeApp: null});
        this.setState({tasksFetchState: STATES.STATE_LOADING});
        this.setState({appVersionsFetchState: STATES.STATE_LOADING});
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
      if (this.state != null && this.state.modal != null &&
          this.state.modal.isMounted()) {
        return;
      }
      this.setState({activeApp: app}, function () {
        /* jshint trailing:false, quotmark:false, newcap:false */
        this.setState({
          modal: React.renderComponent(
            <AppModalComponent
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
              tasksFetchState={this.state.tasksFetchState} />,
            document.getElementById("lightbox"),
            function () {
              this.stopPollingApps();
              this.startPollingTasks();
            }.bind(this)
          )
        });
      });
    },

    showNewAppModal: function(event) {
      // Don't recreate the modal on successive calls of `showModal` if the
      // modal is already open. For example, pressing "c" to open the modal and
      // then pressing "c" again should not create new App and Modal instances
      // or data will be lost if the form is partially filled.
      if (this.state != null && this.state.modal != null &&
          this.state.modal.isMounted()) {
        return;
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
      this.setState({
        modal: React.renderComponent(
          <NewAppModalComponent
            onCreate={this.handleAppCreate}
            onDestroy={this.handleModalDestroy} />,
          document.getElementById("lightbox"),
          this.stopPollingApps
        )
      });
    },

    render: function() {
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
              onSelectApp={this.showAppModal}
              fetchState={this.state.fetchState}
              STATES={STATES}
              ref="appList" />
          </div>
        </div>
      );
    }
  });
});
