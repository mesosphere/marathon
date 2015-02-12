/** @jsx React.DOM */

var React = require("react/addons");
var AppHealthComponent = require("../components/AppHealthComponent");

var AppComponent = React.createClass({
  propTypes: {
    model: React.PropTypes.object.isRequired,
    router: React.PropTypes.object.isRequired
  },

  onClick: function () {
    this.props.router.navigate(
      "apps/" + encodeURIComponent(this.props.model.get("id")),
      {trigger: true}
    );
  },

  render: function () {
    var model = this.props.model;

    var runningTasksClassSet = React.addons.classSet({
      "text-warning": !model.allInstancesBooted()
    });

    var statusClassSet = React.addons.classSet({
      "text-warning": model.isDeploying()
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      // Set `title` on cells that potentially overflow so hovering on the
      // cells will reveal their full contents.
      <tr onClick={this.onClick}>
        <td className="overflow-ellipsis" title={model.get("id")}>
          {model.get("id")}
        </td>
        <td className="text-right">{model.get("mem")}</td>
        <td className="text-right">{model.get("cpus")}</td>
        <td className="text-right">
          <span className={runningTasksClassSet}>
            {model.formatTasksRunning()}
          </span> / {model.get("instances")}
        </td>
        <td className="text-right health-bar-column">
          <AppHealthComponent model={model} />
        </td>
        <td className="text-right">
          <span className={statusClassSet}>{model.getStatus()}</span>
        </td>
      </tr>
    );
  }
});

module.exports = AppComponent;
