/** @jsx React.DOM */

var Mousetrap = require("mousetrap");
var React = require("react/addons");
var _ = require("underscore");
var States = require("../constants/States");
var AppVersionListComponent = require("../components/AppVersionListComponent");
var ModalComponent = require("../components/ModalComponent");
var StackedViewComponent = require("../components/StackedViewComponent");
var TabPaneComponent = require("../components/TabPaneComponent");
var TaskDetailComponent = require("../components/TaskDetailComponent");
var TaskViewComponent = require("../components/TaskViewComponent");
var TogglableTabsComponent = require("../components/TogglableTabsComponent");
var pollResourceMixin = require("../mixins/pollResourceMixin");

var tabsTemplate = [
  {id: "apps:appid", text: "Tasks"},
  {id: "apps:appid/configuration", text: "Configuration"}
];

var AppModalComponent = React.createClass({
  displayName: "AppModalComponent",

  mixins: [pollResourceMixin],

  propTypes: {
    appVersionsFetchState: React.PropTypes.number.isRequired,
    model: React.PropTypes.object.isRequired,
    destroyApp: React.PropTypes.func.isRequired,
    fetchAppVersions: React.PropTypes.func.isRequired,
    onDestroy: React.PropTypes.func.isRequired,
    onTasksKilled: React.PropTypes.func.isRequired,
    rollBackApp: React.PropTypes.func.isRequired,
    router: React.PropTypes.object.isRequired
  },

  getInitialState: function() {
    var appid = this.props.model.get("id");
    var activeTabId;

    this.tabs = _.reduce(tabsTemplate, function(current, tab) {
      var id = tab.id.replace(":appid", appid);
      if (activeTabId == null) {
         activeTabId = id;
      }
      current.push({
        id: id,
        text: tab.text
      });
      return current;
    },[]);

    return {
      activeViewIndex: 0,
      activeTabId: activeTabId,
      selectedTasks: {},
      activeTask: null,
      tasksFetchState: States.STATE_LOADING
    };
  },

  componentDidMount: function() {
    this.props.router.on("route:apps", function (appid, view) {
      if(appid && this.isMounted()) {
        this.setState({
          activeTabId: "apps/" + appid + (view ? "/" + view : "")
        });
      }
    }.bind(this));

    this.setState({
      activeTabId: this.props.router.currentHash()
    });

    this.setPollResource(this.fetchTasks);

    Mousetrap.bind("#", function() {
      this.destroyApp();
    }.bind(this));
  },

  componentWillUnmount: function() {
    this.stopPolling();
  },

  fetchTasks: function() {
    var app = this.props.model;

    app.tasks.fetch({
      error: function() {
        this.setState({tasksFetchState: States.STATE_ERROR});
      }.bind(this),
      success: function(collection, response) {
        // update changed attributes in app
        app.update(response.app);
        this.setState({tasksFetchState: States.STATE_SUCCESS});
      }.bind(this)
    });
  },

  toggleAllTasks: function () {
    var newSelectedTasks = {};
    var modelTasks = this.props.model.tasks;

    // Note: not an **exact** check for all tasks being selected but a good
    // enough proxy.
    var allTasksSelected = Object.keys(this.state.selectedTasks).length ===
      modelTasks.length;

    if (!allTasksSelected) {
      modelTasks.forEach(function (task) {
        newSelectedTasks[task.id] = true;
      });
    }

    this.setState({selectedTasks: newSelectedTasks});
  },

  toggleTask: function (task, value) {
    var selectedTasks = this.state.selectedTasks;

    // If `toggleTask` is used as a callback for an event handler, the second
    // parameter will be an event object. Use it to set the value only if it
    // is a Boolean.
    var localValue = (typeof value === Boolean) ?
      value :
      !selectedTasks[task.id];

    if (localValue === true) {
      selectedTasks[task.id] = true;
    } else {
      delete selectedTasks[task.id];
    }

    this.setState({selectedTasks: selectedTasks});
  },

  showTaskDetails: function(task) {
    this.setState({
      activeTask: task,
      activeViewIndex: 1
    });
  },

  showTaskList: function() {
    this.setState({
      activeTask: null,
      activeViewIndex: 0
    });
  },

  scaleApp: function() {
    var model = this.props.model;

    var instancesString = prompt("Scale to how many instances?",
    model.get("instances"));

    // Clicking "Cancel" in a prompt returns either null or an empty String.
    // perform the action only if a value is submitted.
    if (instancesString != null && instancesString !== "") {
      model.save(
        {instances: parseInt(instancesString, 10)},
        {
          error: function (data, response) {
            var msg = response.responseJSON.message || response.statusText;
            alert("Not scaling: " + msg);
          },
          success: function () {
            // refresh app versions
            this.props.fetchAppVersions();
          }.bind(this)
      });

      if (model.validationError != null) {
        // If the model is not valid, revert the changes to prevent the UI
        // from showing an invalid state.
        model.update(model.previousAttributes());
        alert("Not scaling: " + model.validationError[0].message);
      }
    }
  },

  suspendApp: function () {
    var model = this.props.model;

    if (confirm("Suspend app by scaling to 0 instances?")) {
      model.suspend({
        error: function (data, response) {
          var msg = response.responseJSON.message || response.statusText;
          alert("Could not suspend: " + msg);
        },
        success: function () {
          // refresh app versions
          this.props.fetchAppVersions();
        }.bind(this)
      });
    }
  },

  destroyApp: function() {
    var model = this.props.model;

    if (confirm("Destroy app '" + model.id + "'?\nThis is irreversible.")) {
      model.destroy({
        error: function (data, response) {
          var msg = response.responseJSON.message || response.statusText;
          alert("Error destroying app '" + model.id + "': " + msg);
        },
        success: function () {
          this.props.destroyApp();
          this.refs.modalComponent.destroy();
        }.bind(this),
        wait: true
      });
    }
  },

  render: function() {
    var model = this.props.model;

    var isDeploying = model.isDeploying();

    var statusClassSet = React.addons.classSet({
      "text-warning": isDeploying
    });

    var hasHealth = model.get("healthChecks") != null &&
      model.get("healthChecks").length > 0;

    /* jshint trailing:false, quotmark:false, newcap:false */
    return (
      <ModalComponent ref="modalComponent" onDestroy={this.props.onDestroy}
        size="lg">
        <div className="modal-header">
           <button type="button" className="close"
              aria-hidden="true" onClick={this.props.onDestroy}>&times;</button>
          <span className="h3 modal-title">{model.get("id")}</span>
          <ul className="list-inline list-inline-subtext">
            <li>
                <span className={statusClassSet}>
                  {isDeploying ? "Deploying" : "Running" }
                </span>
            </li>
          </ul>
          <div className="header-btn">
            <button className="btn btn-sm btn-default"
                onClick={this.suspendApp}
                disabled={model.get("instances") < 1}>
              Suspend
            </button>
            <button className="btn btn-sm btn-default" onClick={this.scaleApp}>
              Scale
            </button>
            <button className="btn btn-sm btn-danger pull-right" onClick={this.destroyApp}>
              Destroy App
            </button>
          </div>
        </div>
        <TogglableTabsComponent className="modal-body modal-body-no-top"
            activeTabId={this.state.activeTabId}
            tabs={this.tabs} >
          <TabPaneComponent id={"apps" + model.get("id")}>
            <StackedViewComponent
              activeViewIndex={this.state.activeViewIndex}>
              <TaskViewComponent
                tasks={model.tasks}
                fetchTasks={this.fetchTasks}
                tasksFetchState={this.state.tasksFetchState}
                currentAppVersion={model.get('version')}
                formatTaskHealthMessage={model.formatTaskHealthMessage}
                hasHealth={hasHealth}
                onTasksKilled={this.props.onTasksKilled}
                onTaskDetailSelect={this.showTaskDetails} />
              <TaskDetailComponent
                tasksFetchState={this.state.tasksFetchState}
                taskHealthMessage={model.formatTaskHealthMessage(this.state.activeTask)}
                hasHealth={hasHealth}
                onShowTaskList={this.showTaskList}
                task={this.state.activeTask} />
            </StackedViewComponent>
          </TabPaneComponent>
          <TabPaneComponent
            id={"apps" + model.get("id") + "/configuration"}
            onActivate={this.props.fetchAppVersions} >
            <AppVersionListComponent
              app={model}
              fetchAppVersions={this.props.fetchAppVersions}
              fetchState={this.props.appVersionsFetchState}
              onRollback={this.props.rollBackApp} />
          </TabPaneComponent>
        </TogglableTabsComponent>
      </ModalComponent>
    );
  }
});

module.exports = AppModalComponent;
