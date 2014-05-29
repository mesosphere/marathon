/** @jsx React.DOM */

define([
  "Underscore",
  "React",
  "models/App",
  "models/AppVersion"
], function(_, React, App, AppVersion) {
  return React.createClass({
    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersion: React.PropTypes.instanceOf(AppVersion).isRequired,
      onRollback: React.PropTypes.func
    },

    handleDetailsClick: function(event) {
      event.preventDefault();
      this.props.onShowDetails(this.props.app, event);
    },

    handleSubmit: function(event) {
      if (_.isFunction(this.props.onRollback)) {
        event.preventDefault();
        this.props.onRollback(this.props.appVersion, event);
      }
    },

    render: function() {
      var versionDate = new Date(this.props.appVersion.get("version"));

      return (
        <tr>
          <td>
            <time dateTime={versionDate.toISOString()} title={versionDate.toISOString()}>
              {versionDate.toLocaleString()}</time>
              <a href="#" onClick={this.handleDetailsClick}>Details</a>
          </td>
          <td className="text-right">
            <form action={this.props.app.url()} method="post" onSubmit={this.handleSubmit}>
              <input type="hidden" name="_method" value="put" />
              <input type="hidden" name="version" value={this.props.appVersion.get("version")} />
              <button type="submit" className="btn btn-xs btn-default">
                Set as current
              </button>
            </form>
          </td>
        </tr>
      );
    }
  });
});
