/** @jsx React.DOM */

var React = require("react/addons");
var PagedNavComponent = require("../components/PagedNavComponent");
var TaskListComponent = require("../components/TaskListComponent");

var TaskViewComponent = React.createClass({
  displayName: "TaskViewComponent",

  propTypes: {
    collection: React.PropTypes.object.isRequired,
    fetchState: React.PropTypes.number.isRequired,
    fetchTasks: React.PropTypes.func.isRequired,
    formatTaskHealthMessage: React.PropTypes.func.isRequired,
    hasHealth: React.PropTypes.bool,
    onTasksKilled: React.PropTypes.func.isRequired
  },

  getInitialState: function () {
    return {
      selectedTasks: {},
      currentPage: 0,
      itemsPerPage: 8
    };
  },

  handlePageChange: function (pageNum) {
    this.setState({currentPage: pageNum});
  },

  killSelectedTasks: function (options) {
    var _options = options || {};

    var selectedTaskIds = Object.keys(this.state.selectedTasks);
    var tasksToKill = this.props.collection.filter(function (task) {
      return selectedTaskIds.indexOf(task.id) >= 0;
    });

    tasksToKill.forEach(function (task) {
      task.destroy({
        scale: _options.scale,
        success: function () {
          this.props.onTasksKilled(_options);
          delete this.state.selectedTasks[task.id];
        }.bind(this),
        wait: true
      });
    }, this);
  },

  killSelectedTasksAndScale: function () {
    this.killSelectedTasks({scale: true});
  },

  toggleAllTasks: function () {
    var newSelectedTasks = {};
    var modelTasks = this.props.collection;

    // Note: not an **exact** check for all tasks being selected but a good
    // enough proxy.
    var allTasksSelected = Object.keys(this.state.selectedTasks).length ===
      modelTasks.length;

    if (!allTasksSelected) {
      modelTasks.map(function (task) { newSelectedTasks[task.id] = true; });
    }

    this.setState({selectedTasks: newSelectedTasks});
  },

  onTaskToggle: function (task, value) {
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

  getButtons: function () {
    var selectedTasksLength = Object.keys(this.state.selectedTasks).length;

    var refreshButtonClassSet = React.addons.classSet({
      "btn btn-sm btn-info": true,
      "hidden": selectedTasksLength !== 0
    });

    var killButtonClassSet = React.addons.classSet({
      "btn btn-sm btn-info": true,
      "hidden": selectedTasksLength === 0
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div className="btn-group">
        <button
            className={refreshButtonClassSet}
            onClick={this.props.fetchTasks}>
          ↻ Refresh
        </button>
        <button
            className={killButtonClassSet}
            onClick={this.killSelectedTasks}>
          Kill
        </button>
        <button
            className={killButtonClassSet}
            disabled={selectedTasksLength > 1}
            onClick={this.killSelectedTasksAndScale}>
          Kill &amp; Scale
        </button>
      </div>
    );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  },

  getPagedNav: function () {
    var tasksLength = this.props.collection.length;
    var itemsPerPage = this.state.itemsPerPage;

    // at least two pages
    if (tasksLength > itemsPerPage) {
      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      return (
        <PagedNavComponent
          className="text-right"
          currentPage={this.state.currentPage}
          onPageChange={this.handlePageChange}
          itemsPerPage={itemsPerPage}
          noItems={tasksLength}
          useArrows={true} />
      );
      /* jshint trailing:true, quotmark:true, newcap:true */
      /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    }

    return null;
  },

  render: function () {
    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div>
        <div className="row">
          <div className="col-sm-6">
            {this.getButtons()}
          </div>
          <div className="col-sm-6">
            {this.getPagedNav()}
          </div>
        </div>
        <TaskListComponent
          currentPage={this.state.currentPage}
          fetchState={this.props.fetchState}
          formatTaskHealthMessage={this.props.formatTaskHealthMessage}
          hasHealth={this.props.hasHealth}
          onTaskToggle={this.onTaskToggle}
          itemsPerPage={this.state.itemsPerPage}
          selectedTasks={this.state.selectedTasks}
          tasks={this.props.collection}
          toggleAllTasks={this.toggleAllTasks} />
      </div>
    );
  }
});

module.exports = TaskViewComponent;
