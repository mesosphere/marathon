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

    getInitialState: function() {
      return {
        loading: false
      };
    },

    handleDestroyDeployment: function () {
      this.setState({loading: true});
      this.props.destroyDeployment(this.props.model, function () {
        this.setState({loading: false});
      }.bind(this));


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
                return <li key={action.apps}>{action.action}</li>;
              })}
            </ul>
          </td>
          <td className="text-right">
            <span className={isDeployingClassSet}>
              {model.get("currentStep") - 1}
            </span> / {model.get("totalSteps")}
          </td>
          <td className="text-right">
            {
              this.state.loading ?
                <div className="progress progress-striped active pull-right" style={{"width": "140px"}}>
                  <span className="progress-bar progress-bar-info" role="progressbar" aria-valuenow="100" aria-valuemin="0" aria-valuemax="100" style={{"width": "100%"}}>
                    <span className="sr-only">Reverting deployment</span>
                  </span>
                </div> :
                <button
                    onClick={this.handleDestroyDeployment.bind(null, this)}
                    className="btn btn-sm btn-default">
                  Revert deployment
                </button>
            }
          </td>
        </tr>
      );
    }
  });
});
