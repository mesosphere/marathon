/** @jsx React.DOM */

var React = require("react/addons");
var States = require("../constants/States");
var DeploymentComponent = require("../components/DeploymentComponent");
var BackboneMixin = require("../mixins/BackboneMixin");

var DeploymentListComponent = React.createClass({
  displayName: "DeploymentListComponent",

  mixins: [BackboneMixin],

  propTypes: {
    deployments: React.PropTypes.object.isRequired,
    destroyDeployment: React.PropTypes.func.isRequired,
    fetchState: React.PropTypes.number.isRequired
  },

  getResource: function () {
    return this.props.deployments;
  },

  sortCollectionBy: function (comparator) {
    var deployments = this.props.deployments;
    comparator =
      deployments.sortKey === comparator && !deployments.sortReverse ?
      "-" + comparator :
      comparator;
    deployments.setComparator(comparator);
    deployments.sort();
  },

  getDeploymentNodes: function () {
    return this.props.deployments.map(function (model) {
      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      return (
        <DeploymentComponent
          key={model.id}
          destroyDeployment={this.props.destroyDeployment}
          model={model} />
      );
      /* jshint trailing:true, quotmark:true, newcap:true */
      /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    }, this);
  },

  render: function () {
    var sortKey = this.props.deployments.sortKey;

    var headerClassSet = React.addons.classSet({
      "clickable": true,
      "dropup": this.props.deployments.sortReverse
    });

    var loadingClassSet = React.addons.classSet({
      "hidden": this.props.fetchState !== States.STATE_LOADING
    });

    var errorClassSet = React.addons.classSet({
      "hidden": this.props.fetchState !== States.STATE_ERROR
    });

    var noDeploymentsClassSet = React.addons.classSet({
      "hidden": this.props.deployments.length !== 0
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <table className="table table-fixed">
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
                Deployment ID {sortKey === "id" ? <span className="caret"></span> : null}
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
          <tr className={loadingClassSet}>
            <td className="text-center text-muted" colSpan="5">
              Loading deployments...
            </td>
          </tr>
          <tr className={errorClassSet}>
            <td className="text-center text-danger" colSpan="5">
              Error fetching deployments. Refresh to try again.
            </td>
          </tr>
          <tr className={noDeploymentsClassSet}>
            <td className="text-center" colSpan="5">
              No deployments in progress.
            </td>
          </tr>
          {this.getDeploymentNodes()}
        </tbody>
      </table>
    );
  }
});

module.exports = DeploymentListComponent;
