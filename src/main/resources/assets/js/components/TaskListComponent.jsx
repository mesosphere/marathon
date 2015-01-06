/** @jsx React.DOM */

define([
  "React",
  "constants/States",
  "mixins/BackboneMixin",
  "jsx!components/TaskListItemComponent",
  "jsx!components/PagedContentComponent"
], function(React, States, BackboneMixin,
    TaskListItemComponent, PagedContentComponent) {
  "use strict";

  return React.createClass({
    displayName: "TaskListComponent",

    mixins: [BackboneMixin],

    propTypes: {
      currentPage: React.PropTypes.number.isRequired,
      fetchState: React.PropTypes.number.isRequired,
      itemsPerPage: React.PropTypes.number.isRequired,
      hasHealth: React.PropTypes.bool,
      selectedTasks: React.PropTypes.object.isRequired,
      tasks: React.PropTypes.object.isRequired,
      appVersion: React.PropTypes.object.isRequired
    },

    getResource: function() {
      return this.props.tasks;
    },

    getInitialState: function() {
      return {
        fetchState: States.STATE_LOADING
      };
    },

    handleThToggleClick: function(event) {
      // If the click happens on the checkbox, let the checkbox's onchange event
      // handler handle it and skip handling the event here.
      if (event.target.nodeName !== "INPUT") {
        this.props.toggleAllTasks();
      }
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

    render: function() {
      var taskNodes;
      var tasksLength = this.props.tasks.length;
      var hasHealth = !!this.props.hasHealth;

      // If there are no tasks, they can't all be selected. Otherwise, assume
      // they are all selected and let the iteration below decide if that is
      // true.
      var allTasksSelected = tasksLength > 0;

      if (this.props.fetchState === States.STATE_LOADING) {
        taskNodes =
          <tbody>
            <tr>
              <td className="text-center text-muted" colSpan="7">
                Loading tasks...
              </td>
            </tr>
          </tbody>;
      } else if (this.props.fetchState === States.STATE_ERROR) {
        taskNodes =
          <tbody>
            <tr>
              <td className="text-center text-danger" colSpan="7">
                Error fetching tasks. Refresh the list to try again.
              </td>
            </tr>
          </tbody>;
      } else if (tasksLength === 0) {
        taskNodes =
          <tbody>
            <tr>
              <td className="text-center" colSpan="7">
                No tasks running.
              </td>
            </tr>
          </tbody>;
      } else {

        /* jshint trailing:false, quotmark:false, newcap:false */
        taskNodes = (
          <PagedContentComponent
              currentPage={this.props.currentPage}
              itemsPerPage={this.props.itemsPerPage}
              element="tbody" >
            {
              this.props.tasks.map(function(task) {
                // Expicitly check for Boolean since the key might not exist in the
                // object.
                var isActive = this.props.selectedTasks[task.id] === true;
                if (!isActive) { allTasksSelected = false; }

                return (
                    <TaskListItemComponent
                      isActive={isActive}
                      key={task.id}
                      taskHealthMessage={this.props.formatTaskHealthMessage(task)}
                      onToggle={this.props.onTaskToggle}
                      onTaskDetailSelect={this.props.onTaskDetailSelect}
                      hasHealth={hasHealth}
                      task={task}
                      appVersion={this.props.appVersion} />
                );
              }, this)
            }
          </PagedContentComponent>
        );
      }

      var sortKey = this.props.tasks.sortKey;

      var headerClassSet = React.addons.classSet({
          "clickable": true,
          "dropup": this.props.tasks.sortReverse
        });

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <table className="table table-unstyled">
          <thead>
            <tr>
              <th className={headerClassSet} width="1" onClick={this.handleThToggleClick}>
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
                <span className={headerClassSet} onClick={this.sortCollectionBy.bind(null, "version")}>
                  {(sortKey === "version") ? <span className="caret"></span> : null} Version
                </span>
              </th>
              <th className="text-right">
                <span onClick={this.sortCollectionBy.bind(null, "updatedAt")}
                      className={headerClassSet}>
                  {(sortKey === "updatedAt") ? <span className="caret"></span> : null} Updated
                </span>
              </th>
              {
                hasHealth ?
                  <th className="text-center">
                    <span onClick={this.sortCollectionBy.bind(null, "getHealth")}
                          className={headerClassSet}>
                      {(sortKey === "getHealth") ? <span className="caret"></span> : null} Health
                    </span>
                  </th> :
                  null
              }
            </tr>
          </thead>
          {taskNodes}
        </table>
      );
    }
  });
});
