/** @jsx React.DOM */

var React = require("react/addons");
var AppBreadcrumbsComponent = require("../components/AppBreadcrumbsComponent");
var AppVersionListComponent = require("../components/AppVersionListComponent");
var TabPaneComponent = require("../components/TabPaneComponent");
var TaskDetailComponent = require("../components/TaskDetailComponent");
var TaskViewComponent = require("../components/TaskViewComponent");
var TogglableTabsComponent = require("../components/TogglableTabsComponent");

var tabs = [
  {id: "tasks", text: "Tasks"},
  {id: "configuration", text: "Configuration"}
];

var AppPageComponent = React.createClass({
  displayName: "AppPageComponent",

  propTypes: {
    activeTask: React.PropTypes.object,
    appVersionsFetchState: React.PropTypes.number.isRequired,
    model: React.PropTypes.object.isRequired,
    destroyApp: React.PropTypes.func.isRequired,
    fetchTasks: React.PropTypes.func.isRequired,
    fetchAppVersions: React.PropTypes.func.isRequired,
    onDestroy: React.PropTypes.func.isRequired,
    onShowTaskDetails: React.PropTypes.func.isRequired,
    onShowTaskList: React.PropTypes.func.isRequired,
    onTasksKilled: React.PropTypes.func.isRequired,
    restartApp: React.PropTypes.func.isRequired,
    rollBackApp: React.PropTypes.func.isRequired,
    scaleApp: React.PropTypes.func.isRequired,
    suspendApp: React.PropTypes.func.isRequired,
    tasksFetchState: React.PropTypes.number.isRequired
  },

  getInitialState: function () {
    return {
      activeViewIndex: 0,
      activeTabId: tabs[0].id
    };
  },

  handleDestroyApp: function () {
    this.props.destroyApp();
    this.onDestroy();
  },

  handleRestartApp: function () {
    this.props.restartApp();
  },

  onTabClick: function (id) {
    this.setState({
      activeTabId: id
    });
  },

  showTaskDetails: function (task) {
    this.props.onShowTaskDetails(task, function () {
      this.setState({
        activeViewIndex: 1
      });
    }.bind(this));
  },

  showTaskList: function () {
    this.props.onShowTaskList();
    this.setState({
      activeViewIndex: 0
    });
  },

  scaleApp: function () {
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

  getControls: function () {
    if (this.state.activeViewIndex !== 0) {
      return null;
    }

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div className="header-btn">
        <button className="btn btn-sm btn-default"
            onClick={this.props.suspendApp}
            disabled={this.props.model.get("instances") < 1}>
          Suspend
        </button>
        <button className="btn btn-sm btn-default" onClick={this.scaleApp}>
          Scale
        </button>
        <button className="btn btn-sm btn-danger pull-right"
          onClick={this.handleDestroyApp}>
          Destroy App
        </button>
        <button className="btn btn-sm btn-default pull-right"
          onClick={this.handleRestartApp}>
          Restart App
        </button>
      </div>
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  },

  getTaskDetailComponent: function () {
    var model = this.props.model;

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <TaskDetailComponent
        fetchState={this.props.tasksFetchState}
        taskHealthMessage={model.formatTaskHealthMessage(this.props.activeTask)}
        hasHealth={model.hasHealth()}
        onShowTaskList={this.showTaskList}
        task={this.props.activeTask} />
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  },

  getAppDetails: function () {
    var model = this.props.model;

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <TogglableTabsComponent className="page-body page-body-no-top"
          activeTabId={this.state.activeTabId}
          onTabClick={this.onTabClick}
          tabs={tabs} >
        <TabPaneComponent id="tasks">
          <TaskViewComponent
            collection={model.tasks}
            fetchState={this.props.tasksFetchState}
            fetchTasks={this.props.fetchTasks}
            formatTaskHealthMessage={model.formatTaskHealthMessage}
            hasHealth={model.hasHealth()}
            onTasksKilled={this.props.onTasksKilled}
            onTaskDetailSelect={this.showTaskDetails} />
        </TabPaneComponent>
        <TabPaneComponent
          id="configuration"
          onActivate={this.props.fetchAppVersions} >
          <AppVersionListComponent
            app={model}
            fetchAppVersions={this.props.fetchAppVersions}
            fetchState={this.props.appVersionsFetchState}
            onRollback={this.props.rollBackApp} />
        </TabPaneComponent>
      </TogglableTabsComponent>
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  },

  render: function () {
    var content;
    var model = this.props.model;
    var statusClassSet = React.addons.classSet({
      "text-warning": model.isDeploying()
    });

    if (this.state.activeViewIndex === 0) {
      content = this.getAppDetails();
    } else if (this.state.activeViewIndex === 1)  {
      content = this.getTaskDetailComponent();
    }

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div>
        <AppBreadcrumbsComponent
          activeTask={this.props.activeTask}
          activeViewIndex={this.state.activeViewIndex}
          model={model}
          onDestroy={this.props.onDestroy}
          showTaskList={this.showTaskList} />
        <div className="page-header">
          <span className="h3 modal-title">{model.get("id")}</span>
          <ul className="list-inline list-inline-subtext">
            <li>
              <span className={statusClassSet}>
                {model.getStatus()}
              </span>
            </li>
          </ul>
          {this.getControls()}
        </div>
        {content}
      </div>
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  }
});

module.exports = AppPageComponent;
