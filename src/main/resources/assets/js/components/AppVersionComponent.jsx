/** @jsx React.DOM */

define([
  "Underscore",
  "React",
  "models/App",
  "models/AppVersion",
  "jsx!components/EditableValueLabelComponent"
], function(_, React, App, AppVersion, EditableValueLabelComponent) {
  "use strict";

  return React.createClass({
    displayName: "AppVersionComponent",

    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersion: React.PropTypes.oneOfType([
        React.PropTypes.instanceOf(App).isRequired,
        React.PropTypes.instanceOf(AppVersion).isRequired
      ]),
      onRollback: React.PropTypes.func
    },

    getInitialState: function() {
      return {
        errors: []
      };
    },

    handleSubmit: function(event) {
      if (_.isFunction(this.props.onRollback)) {
        event.preventDefault();
        this.props.onRollback(this.props.appVersion, event);
      }
    },

    componentWillMount: function() {
      this.props.app.on("invalid", this.handleInvalid);
    },

    componentWillUnmount: function() {
      this.props.app.off("invalid", this.handleInvalid);
    },

    handleInvalid: function() {
      this.setState({
        errors: this.props.app.validationError
      });
    },

    render: function() {
      var fields = [
        {
          attribute: "cmd",
          label: "Command",
          type: "textarea"
        },
        {
          attribute: "constraints",
          label: "Constraints",
          type: "text",

          // turns out, this whole converter idea was no good at all. there
          // are a few annoying effects because of this implementation. instead
          // of preparing the data of the model on every change / render through
          // this method, it should have been implemented as in the
          // NewAppModalComponent or maybe even in the model itself but this is
          // clearly not working
          converter: {
            forModel: function(value) {
              return value;
            },
            forRender: function(value) {
              return value;
            }
          }
        },
        {
          attribute: "container",
          label: "Container",
          type: "text"
        },
        {
          attribute: "env",
          label: "Environment",
          type: "textarea",
          converter: {
            forModel: function(value) {
              return value.split(",").reduce(function(envVariables, envValue) {
                var split = envValue.split("=");

                envVariables[split[0]] = split[1] || "";
                return envVariables;
              }, {});
            },
            forRender: function(envVariables) {
              if (typeof envVariables === "string") return envVariables;

              return Object.keys(envVariables).map(function(envVariable) {
                return envVariable + "=" + envVariables[envVariable];
              }).join(",");
            }
          }
        },
        {
          attribute: "executor",
          label: "Executor",
          type: "text"
        },
        {
          attribute: "instances",
          label: "Instances",
          type: "number"
        },
        {
          attribute: "disk",
          label: "Disk Space (MB)",
          type: "number"
        },
        {
          attribute: "memory",
          label: "Memory (MB)",
          type: "number"
        },
        {
          attribute: "ports",
          label: "Ports",
          type: "text",
          converter: {
            forModel: function(value) {
              return value.split(",").map(function (port) {
                var number = parseInt(port, 10);
                if (isNaN(number)) return "";
                return number;
              });
            },
            forRender: function(value) {
              return value.join(", ");
            }
          }
        },
        {
          attribute: "taskRateLimit",
          label: "Task Rate Limit",
          type: "number"
        },
        {
          attribute: "uris",
          label: "URIs",
          type: "text",
          converter: {
            forModel: function(value) {
              return value;
            },
            forRender: function(value) {
              return value;
            }
          }
        }
      ];

      var controls = fields.map(function(field) {
        var input;
        switch (field.type) {
          case "text":     input = <input type="text" />; break;
          case "textarea": input = <textarea />; break;
          case "number":   input = <input type="number" />; break;
        }

        return (
          <EditableValueLabelComponent onChange={this.props.onChange} model={this.props.app} attribute={field.attribute} label={field.label} key={field.attribute} converter={field.converter} error={this.state.errors.filter(function (error) { return error.attribute === field.attribute; }).pop()}>
            {input}
          </EditableValueLabelComponent>
        );
      }.bind(this));

      return (
        <div>
          <dl className={"dl-horizontal " + this.props.className}>

            {controls}

            <dt>Version</dt>
            <dd>{this.props.appVersion.id}</dd>
          </dl>
          {
            this.props.currentVersion ?
              null :
              <div className="text-right">
                <form action={this.props.app.url()} method="post" onSubmit={this.handleSubmit}>
                    <input type="hidden" name="_method" value="put" />
                    <input type="hidden" name="version" value={this.props.appVersion.get("version")} />
                    <button type="submit" className="btn btn-sm btn-default">
                      Apply these settings
                    </button>
                </form>
              </div>
          }
        </div>
      );
    }
  });
});
