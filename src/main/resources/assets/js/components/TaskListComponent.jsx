/** @jsx React.DOM */

var React = require("react/addons");
var States = require("../constants/States");
var BackboneMixin = require("../mixins/BackboneMixin");
var TaskListItemComponent = require("../components/TaskListItemComponent");
var PagedContentComponent = require("../components/PagedContentComponent");

var TaskListComponent = React.createClass({
  displayName: "TaskListComponent",

  mixins: [BackboneMixin],

  propTypes: {
    currentPage: React.PropTypes.number.isRequired,
    fetchState: React.PropTypes.number.isRequired,
    itemsPerPage: React.PropTypes.number.isRequired,
    hasHealth: React.PropTypes.bool,
    selectedTasks: React.PropTypes.object.isRequired,
    tasks: React.PropTypes.object.isRequired,
  },

  getResource: function () {
    return this.props.tasks;
  },

  getInitialState: function () {
    return {
      fetchState: States.STATE_LOADING
    };
  },

  handleThToggleClick: function (event) {
    // If the click happens on the checkbox, let the checkbox's onchange event
    // handler handle it and skip handling the event here.
    if (event.target.nodeName !== "INPUT") {
      this.props.toggleAllTasks();
    }
  },

  sortCollectionBy: function (comparator) {
    var collection = this.props.tasks;
    comparator =
      collection.sortKey === comparator && !collection.sortReverse ?
      "-" + comparator :
      comparator;
    collection.setComparator(comparator);
    collection.sort();
  },

  getTasks: function () {
    var appId = this.props.tasks.options.appId;
    var hasHealth = !!this.props.hasHealth;

    return (
      this.props.tasks.map(function (task) {
        var isActive = this.props.selectedTasks[task.id] === true;

        /* jshint trailing:false, quotmark:false, newcap:false */
        /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
        return (
          <TaskListItemComponent
            appId={appId}
            hasHealth={hasHealth}
            isActive={isActive}
            key={task.id}
            onToggle={this.props.onTaskToggle}
            task={task}
            taskHealthMessage={this.props.formatTaskHealthMessage(task)}/>
        );
        /* jshint trailing:true, quotmark:true, newcap:true */
        /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      }, this)
    );
  },

  allTasksSelected: function (tasksLength) {
    // If there are no tasks, they can't all be selected. Otherwise, assume
    // they are all selected and let the iteration below decide if that is
    // true.
    var allTasksSelected = tasksLength > 0;

    this.props.tasks.forEach(function (task) {
      // Expicitly check for Boolean since the key might not exist in the
      // object.
      var isActive = this.props.selectedTasks[task.id] === true;
      if (!isActive) { allTasksSelected = false; }
    }, this);

    return allTasksSelected;
  },

  render: function () {
    var tasksLength = this.props.tasks.length;
    var allTasksSelected = this.allTasksSelected(tasksLength);
    var hasHealth = !!this.props.hasHealth;
    var hasError = this.props.fetchState === States.STATE_ERROR;

    var sortKey = this.props.tasks.sortKey;

    var headerClassSet = React.addons.classSet({
      "clickable": true,
      "dropup": this.props.tasks.sortReverse
    });

    var loadingClassSet = React.addons.classSet({
      "hidden": this.props.fetchState !== States.STATE_LOADING
    });

    var noTasksClassSet = React.addons.classSet({
      "hidden": tasksLength !== 0 || hasError
    });

    var errorClassSet = React.addons.classSet({
      "fluid-container": true,
      "hidden": !hasError
    });

    var hasHealthClassSet = React.addons.classSet({
      "text-center": true,
      "hidden": !hasHealth
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div>
        <div className={errorClassSet}>
          <p className="text-center text-danger">
            Error fetching tasks. Refresh the list to try again.
          </p>
        </div>
        <table className="table table-unstyled">
          <thead>
            <tr>
              <th
                className={headerClassSet}
                width="1"
                onClick={this.handleThToggleClick}>
                <input type="checkbox"
                  checked={allTasksSelected}
                  disabled={tasksLength === 0}
                  onChange={this.props.toggleAllTasks} />
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "id")}
                      className={headerClassSet}>
                  ID {(sortKey === "id") ? <span className="caret"></span> : null}
                </span>
              </th>
              <th className="text-center">
                <span onClick={this.sortCollectionBy.bind(null, "status")}
                      className={headerClassSet}>
                  Status {(sortKey === "status") ? <span className="caret"></span> : null}
                </span>
              </th>
              <th className="text-right">
                <span
                  className={headerClassSet}
                  onClick={this.sortCollectionBy.bind(null, "version")}>
                  {(sortKey === "version") ? <span className="caret"></span> : null} Version
                </span>
              </th>
              <th className="text-right">
                <span onClick={this.sortCollectionBy.bind(null, "updatedAt")}
                      className={headerClassSet}>
                  {(sortKey === "updatedAt") ? <span className="caret"></span> : null} Updated
                </span>
              </th>
                <th className={hasHealthClassSet}>
                  <span onClick={this.sortCollectionBy.bind(null, "getHealth")}
                        className={headerClassSet}>
                    {(sortKey === "getHealth") ? <span className="caret"></span> : null} Health
                  </span>
                </th>
            </tr>
          </thead>
          <PagedContentComponent
              currentPage={this.props.currentPage}
              itemsPerPage={this.props.itemsPerPage}
              tag="tbody" >
            <tr className={noTasksClassSet}>
              <td className="text-center" colSpan="7">
                No tasks running.
              </td>
            </tr>
            <tr className={loadingClassSet}>
              <td className="text-center text-muted" colSpan="7">
                Loading tasks...
              </td>
            </tr>
            {this.getTasks()}
          </PagedContentComponent>
        </table>
      </div>
    );
  }
});

module.exports = TaskListComponent;
