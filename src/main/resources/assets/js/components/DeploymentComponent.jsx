/** @jsx React.DOM */

define([
  "React"
], function(React) {
  "use strict";

  return React.createClass({
    name: "DeploymentComponent",

    propTypes: {
      model: React.PropTypes.object.isRequired,
      destroyDeployment: React.PropTypes.func.isRequired
    },

    handleDestroyDeployment: function () {
      this.props.destroyDeployment(this.props.model);
    },

    render: function() {
      var model = this.props.model;

      var isDeployingClassSet = React.addons.classSet({
        "text-warning": model.get("currentStep") < model.get("totalSteps")
      });

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        // Set `title` on cells that potentially overflow so hovering on the
        // cells will reveal their full contents.
        <tr>
          <td className="overflow-ellipsis" title={model.get("id")}>
            {model.get("id")}
          </td>
          <td>
            <ul className="list-unstyled">
              {model.get("currentActions").map(function(action) {
                return <li key={action.apps}>{action.apps}</li>;
              })}
            </ul>
          </td>
          <td>
            <ul className="list-unstyled">
              {model.get("currentActions").map(function(action) {
                return <li key={action.action}>{action.action}</li>;
              })}
            </ul>
          </td>
          <td className="text-right">
            <span className={isDeployingClassSet}>
              {model.get("currentStep") - 1}
            </span> / {model.get("totalSteps")}
          </td>
          <td className="text-right">
            <button
                onClick={this.handleDestroyDeployment.bind(this)}
                className="btn btn-sm btn-danger">
              Destroy Deployment
            </button>
          </td>
        </tr>
      );
    }
  });
});
