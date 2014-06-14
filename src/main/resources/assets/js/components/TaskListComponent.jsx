/** @jsx React.DOM */

define([
  "React",
  "mixins/BackboneMixin",
  "jsx!components/TaskListItemComponent"
], function(React, BackboneMixin, TaskListItemComponent) {

  return React.createClass({
    displayName: "TaskListComponent",
    mixins:[BackboneMixin],
    propTypes: {
      fetchState: React.PropTypes.number.isRequired,
      hasHealth: React.PropTypes.bool,
      selectedTasks: React.PropTypes.object.isRequired,
      STATES: React.PropTypes.object.isRequired,
      tasks: React.PropTypes.object.isRequired
    },
    getResource: function() {
      return this.props.tasks;
    },
    getInitialState: function() {
      return {
        fetchState: this.props.STATES.STATE_LOADING,
        showTimestamps: false
      };
    },
    handleThToggleClick: function(event) {
      // If the click happens on the checkbox, let the checkbox's onchange event
      // handler handle it and skip handling the event here.
      if (event.target.nodeName !== "INPUT") {
        this.props.toggleAllTasks();
      }
    },
    render: function() {
      var taskNodes;
      var tasksLength = this.props.tasks.length;
      var hasHealth = !!this.props.hasHealth;

      // If there are no tasks, they can't all be selected. Otherwise, assume
      // they are all selected and let the iteration below decide if that is
      // true.
      var allTasksSelected = tasksLength > 0;

      if (this.props.fetchState === this.props.STATES.STATE_LOADING) {
        taskNodes =
          <tr>
            <td className="text-center text-muted" colSpan="5">
              Loading tasks...
            </td>
          </tr>;
      } else if (this.props.fetchState === this.props.STATES.STATE_ERROR) {
        taskNodes =
          <tr>
            <td className="text-center text-danger" colSpan="5">
              Error fetching tasks. Refresh the list to try again.
            </td>
          </tr>;
      } else if (tasksLength === 0) {
        taskNodes =
          <tr>
            <td className="text-center" colSpan="5">
              No tasks running.
            </td>
          </tr>;
      } else {
        taskNodes = this.props.tasks.map(function(task) {
          // Expicitly check for Boolean since the key might not exist in the
          // object.
          var isActive = this.props.selectedTasks[task.id] === true;
          if (!isActive) { allTasksSelected = false; }

          return (
              <TaskListItemComponent
                isActive={isActive}
                key={task.id}
                formatTaskHealthMessage={this.props.formatTaskHealthMessage}
                onToggle={this.props.onTaskToggle}
                onTaskDetailSelect={this.props.onTaskDetailSelect}
                hasHealth={hasHealth}
                task={task} />
          );
        }, this);
      }

      var sortKey = this.props.tasks.sortKey;
      var sortOrder =
        this.props.tasks.sortReverse ?
        "▲" :
        "▼";
      return (
        <table className="table">
          <thead>
            <tr>
              <th className="clickable" width="1" onClick={this.handleThToggleClick}>
                <input type="checkbox"
                  checked={allTasksSelected}
                  disabled={tasksLength === 0}
                  onChange={this.props.toggleAllTasks} />
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "id")}
                      className="clickable">
                  ID {(sortKey === "id") ? sortOrder : null}
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "status")}
                      className="clickable">
                  Status {(sortKey === "status") ? sortOrder : null}
                </span>
              </th>
              <th className="text-right">
                <span onClick={this.sortCollectionBy.bind(null, "updatedAt")}
                      className="clickable">
                  {(sortKey === "updatedAt") ? sortOrder : null} Updated
                </span>
              </th>
              {
                hasHealth ?
                  <th className="text-center">
                    <span onClick={this.sortCollectionBy.bind(null, "health")}
                          className="clickable">
                      {(sortKey === "health") ? sortOrder : null} Health
                    </span>
                  </th> :
                  null
              }
            </tr>
          </thead>
          <tbody>
            {taskNodes}
          </tbody>
        </table>
      );
    },
    sortCollectionBy: function(comparator) {
      var collection = this.props.tasks;
      comparator =
        collection.sortKey === comparator && !collection.sortReverse ?
        "-" + comparator :
        comparator;
      collection.setComparator(comparator);
      collection.sort();
    },
    toggleShowTimestamps: function() {
      this.setState({showTimestamps: !this.state.showTimestamps});
    }
  });
});
