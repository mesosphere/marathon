/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "models/AppVersion",
  "jsx!components/AppVersionComponent"
], function(React, App, AppVersion, AppVersionComponent) {
  return React.createClass({
    displayName: "AppVersionListItemComponent",
    propTypes: {
      appVersion: React.PropTypes.instanceOf(AppVersion).isRequired,
      onRollback: React.PropTypes.func
    },

    getInitialState: function() {
      var open = !!this.props.currentVersion;
      if (open) {
        this.props.appVersion.fetch();
      }
      return {
        open: open
      };
    },

    handleDetailsClick: function(event) {
      if (event.target.type === "radio") {
        // handled by other functions
        return;
      }
      event.preventDefault();
      if (!this.state.open) {
        this.props.appVersion.fetch();
      }
      this.setState({open: !this.state.open});
    },

    handleFirstRadioClick: function(event) {
      event.preventDefault();
      // TODO: handle this
    },

    handleSecondRadioClick: function(event) {
      event.preventDefault();
      // TODO: handle this
    },

    render: function() {
      var caretClassSet = React.addons.classSet({
          "caret-right": this.state.open,
          "caret": !this.state.open
        });
      var versionDate = new Date(this.props.appVersion.get("version"));
      return (
        <div className="panel panel-inverse">
          <div className="panel-heading row clickable" onClick={this.handleDetailsClick}>
            <span className="col-md-7">
              <time dateTime={versionDate.toISOString()} title={versionDate.toISOString()}>
                {versionDate.toLocaleString()}</time>
            </span>
            <span className="text-center col-md-2">
              <input type="radio" onClick={this.handleFirstRadioClick} />
            </span>
            <span className="text-center col-md-2">
              <input type="radio" onClick={this.handleSecondRadioClick} />
            </span>
            <span className="clickable text-center col-md-1">
              <span className={caretClassSet}></span>
            </span>
          </div>
          {
            this.state.open ?
              <div className="panel-body">
                <AppVersionComponent
                  onRollback={this.props.onRollback}
                  appVersion={this.props.appVersion}
                  currentVersion={this.props.currentVersion} />
              </div> :
              null
          }
        </div>
      );
    }
  });
});
