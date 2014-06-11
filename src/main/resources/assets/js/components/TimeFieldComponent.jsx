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
      var time = this.props.time;
      return (
        <div>
          <dt>{this.props.label}</dt>
          {
            time != null ?
              <dd>
                <time dateTime={time}>
                  {new Date(time).toLocaleString()}
                </time>
              </dd> :
              <dd className="text-muted">None</dd>
          }
        </div>
      );
    }
  });
});

