/** @jsx React.DOM */

define([
  "React"
], function(React) {
  "use strict";

  return React.createClass({
    name: "DeploymentComponent",

    propTypes: {
      destroyDeployment: React.PropTypes.func.isRequired
    },

    handleDestroyDeployment: function (id, event) {
      event.preventDefault();
      this.props.destroyDeployment(id);
    },

    render: function() {
      var model = this.props.model;

      var isDeployingClassSet = React.addons.classSet({
        "text-warning": model.get("currentStep") < model.get("totalSteps")
      });

      var formatAffectedApps = model.formatAffectedApps().split("\n").join("<br>");
      var formatCurrentActions = model.formatCurrentActions().split("\n").join("<br>");

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        // Set `title` on cells that potentially overflow so hovering on the
        // cells will reveal their full contents.
        <tr>
          <td className="overflow-ellipsis" title={model.get("id")}>
            {model.get("id")}
          </td>
          <td dangerouslySetInnerHTML={{__html: formatAffectedApps}}></td>
          <td dangerouslySetInnerHTML={{__html: formatCurrentActions}}></td>
          <td className="text-right">
            <span className={isDeployingClassSet}>
              {model.get("currentStep")}
            </span> / {model.get("totalSteps")}
          </td>
          <td className="text-right">
            <button
                onClick={this.handleDestroyDeployment.bind(this, model)}
                className="btn btn-sm btn-danger pull-right">
              Destroy Deployment
            </button>
          </td>
        </tr>
      );
    }
  });
});
