define([
  "React"
], function(React) {
  return React.createClass({
    displayName: "TimeFieldComponent",
    propTypes: {
      label: React.PropTypes.string.isRequired,
      time: React.PropTypes.oneOfType([
        React.PropTypes.string,
        React.PropTypes.object
      ])
    },
    render: function() {
      var timeValue = (
        this.props.time ?
        <dd>
          <time dateTime={this.props.time}>
            {new Date(this.props.time).toLocaleString()}
          </time>
        </dd> :
        <dd className="text-muted">None</dd>
      );
      return (
        <div>
          <dt>{this.props.label}</dt>
          {timeValue}
        </div>
      );
    }
  });
});

