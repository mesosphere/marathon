/** @jsx React.DOM */

define([
  "React",
  "constants/States",
  "jsx!components/DeploymentComponent",
  "mixins/BackboneMixin"
], function(React, States, DeploymentComponent, BackboneMixin) {
  "use strict";

  return React.createClass({
    displayName: "DeploymentListComponent",

    mixins: [BackboneMixin],

    propTypes: {
      deployments: React.PropTypes.object.isRequired,
      destroyDeployment: React.PropTypes.func.isRequired,
      fetchState: React.PropTypes.number.isRequired
    },

    getResource: function() {
      return this.props.deployments;
    },

    sortCollectionBy: function(comparator) {
      var deployments = this.props.deployments;
      comparator =
        deployments.sortKey === comparator && !deployments.sortReverse ?
        "-" + comparator :
        comparator;
      deployments.setComparator(comparator);
      deployments.sort();
    },

    render: function() {
      var sortKey = this.props.deployments.sortKey;

      var deploymentNodes;
      var tableClassName = "table table-fixed";

      var headerClassSet = React.addons.classSet({
        "clickable": true,
        "dropup": this.props.deployments.sortReverse
      });

      if (this.props.fetchState === States.STATE_LOADING) {
        deploymentNodes =
          <tr>
            <td className="text-center text-muted" colSpan="5">
              Loading apps...
            </td>
          </tr>;
      } else if (this.props.fetchState === States.STATE_ERROR) {
        deploymentNodes =
          <tr>
            <td className="text-center text-danger" colSpan="5">
              Error fetching apps. Refresh to try again.
            </td>
          </tr>;
      } else if (this.props.deployments.length === 0) {
        deploymentNodes =
          <tr>
            <td className="text-center" colSpan="5">No running apps.</td>
          </tr>;
      } else {

        /* jshint trailing:false, quotmark:false, newcap:false */
        deploymentNodes = this.props.deployments.map(function(model) {
          return (
            <DeploymentComponent
              key={model.id}
              destroyDeployment={this.props.destroyDeployment}
              model={model} />
          );
        }, this);
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <table className={tableClassName}>
          <colgroup>
            <col style={{width: "28%"}} />
            <col style={{width: "18%"}} />
            <col style={{width: "18%"}} />
            <col style={{width: "18%"}} />
            <col style={{width: "18%"}} />
          </colgroup>
          <thead>
            <tr>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "id")} className={headerClassSet}>
                  ID {sortKey === "id" ? <span className="caret"></span> : null}
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "affectedAppsString")} className={headerClassSet}>
                  Affected Apps {sortKey === "affectedAppsString" ? <span className="caret"></span> : null}
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "currentActionsString")} className={headerClassSet}>
                  {sortKey === "currentActionsString" ? <span className="caret"></span> : null} Action
                </span>
              </th>
              <th className="text-right">
                <span onClick={this.sortCollectionBy.bind(null, "currentStep")} className={headerClassSet}>
                  {sortKey === "currentStep" ? <span className="caret"></span> : null} Progress
                </span>
              </th>
              <th>
              </th>
            </tr>
          </thead>
          <tbody>
            {deploymentNodes}
          </tbody>
        </table>
      );
    }
  });
});
