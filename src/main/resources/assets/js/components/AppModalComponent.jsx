/** @jsx React.DOM */

define([
  "Underscore",
  "React",
  "mixins/BackboneMixin",
  "jsx!components/AppVersionListComponent",
  "jsx!components/ModalComponent",
  "jsx!components/StackedViewComponent",
  "jsx!components/TabPaneComponent",
  "jsx!components/TaskDetailComponent",
  "jsx!components/TaskViewComponent",
  "jsx!components/TogglableTabsComponent"
], function(_, React, BackboneMixin, AppVersionListComponent,
    ModalComponent, StackedViewComponent, TabPaneComponent,
    TaskDetailComponent, TaskViewComponent, TogglableTabsComponent) {
  "use strict";

  return React.createClass({
    displayName: "AppModalComponent",

    mixins: [BackboneMixin],

    propTypes: {
      appVersionsFetchState: React.PropTypes.number.isRequired,
      model: React.PropTypes.object.isRequired,
      destroyApp: React.PropTypes.func.isRequired,
      fetchTasks: React.PropTypes.func.isRequired,
      fetchAppVersions: React.PropTypes.func.isRequired,
      onDestroy: React.PropTypes.func.isRequired,
      onShowTaskDetails: React.PropTypes.func.isRequired,
      onShowTaskList: React.PropTypes.func.isRequired,
      onTasksKilled: React.PropTypes.func.isRequired,
      rollBackApp: React.PropTypes.func.isRequired,
      scaleApp: React.PropTypes.func.isRequired,
      STATES: React.PropTypes.object.isRequired,
      suspendApp: React.PropTypes.func.isRequired,
      tasksFetchState: React.PropTypes.number.isRequired
    },

    getResource: function() {
      return this.props.model;
    },

    getInitialState: function() {
      return {
        activeViewIndex: 0,
        selectedTasks: {}
      };
    },

    destroy: function() {
      this.refs.modalComponent.destroy();
    },

    handleDestroyApp: function() {
      this.props.destroyApp();
      this.destroy();
    },

    toggleAllTasks: function() {
      var newSelectedTasks = {};
      var modelTasks = this.props.model.tasks;

      // Note: not an **exact** check for all tasks being selected but a good
      // enough proxy.
      var allTasksSelected = Object.keys(this.state.selectedTasks).length ===
        modelTasks.length;

      if (!allTasksSelected) {
        modelTasks.forEach(function(task) {
          newSelectedTasks[task.id] = true;
        });
      }

      this.setState({selectedTasks: newSelectedTasks});
    },

    toggleTask: function(task, value) {
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
        activeViewIndex: 1
      });
      this.props.onShowTaskDetails(task);
    },

    showTaskList: function() {
      this.setState({
        activeViewIndex: 0
      });
      this.props.onShowTaskList();
    },

    showScaleAlert: function() {
      var model = this.props.model;
      var instancesString = prompt("Scale to how many instances?",
        model.get("instances"));

      // Clicking "Cancel" in a prompt returns either null or an empty String.
      // perform the action only if a value is submitted.
      if (instancesString != null && instancesString !== "") {
        var instances = parseInt(instancesString, 10);
        this.props.scaleApp(instances);
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
                  onClick={this.props.suspendApp}
                  disabled={model.get("instances") < 1}>
                Suspend
              </button>
              <button className="btn btn-sm btn-default" onClick={this.showScaleAlert}>
                Scale
              </button>
              <button className="btn btn-sm btn-danger pull-right" onClick={this.handleDestroyApp}>
                Destroy App
              </button>
            </div>
          </div>
          <TogglableTabsComponent className="modal-body modal-body-no-top"
              tabs={[
                {id: "tasks", text: "Tasks"},
                {id: "configuration", text: "Configuration"}
              ]}>
            <TabPaneComponent id="tasks">
              <StackedViewComponent
                activeViewIndex={this.state.activeViewIndex}>
                <TaskViewComponent
                  collection={model.tasks}
                  fetchState={this.props.tasksFetchState}
                  fetchTasks={this.props.fetchTasks}
                  formatTaskHealthMessage={model.formatTaskHealthMessage}
                  hasHealth={hasHealth}
                  onTasksKilled={this.props.onTasksKilled}
                  onTaskDetailSelect={this.props.onShowTaskDetails}
                  STATES={this.props.STATES} />
                <TaskDetailComponent
                  fetchState={this.props.tasksFetchState}
                  taskHealthMessage={model.formatTaskHealthMessage(this.state.activeTask)}
                  hasHealth={hasHealth}
                  STATES={this.props.STATES}
                  onShowTaskList={this.props.showTaskList}
                  task={this.props.activeTask} />
              </StackedViewComponent>
            </TabPaneComponent>
            <TabPaneComponent
              id="configuration"
              onActivate={this.props.fetchAppVersions} >
              <AppVersionListComponent
                app={model}
                fetchAppVersions={this.props.fetchAppVersions}
                fetchState={this.props.appVersionsFetchState}
                onRollback={this.props.rollBackApp}
                STATES={this.props.STATES} />
            </TabPaneComponent>
          </TogglableTabsComponent>
        </ModalComponent>
      );
    }
  });
});
