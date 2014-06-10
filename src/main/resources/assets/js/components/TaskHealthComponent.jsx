define([
  "React",
  "jsx!components/TimeFieldComponent"
], function(React, TimeFieldComponent) {

  return React.createClass({
    displayName: "TaskHealthComponent",
    propTypes: {
      task: React.PropTypes.object.isRequired
    },
    render: function() {
      var task = this.props.task;
      var healthCheckResults = task.get("healthCheckResults");

      if (healthCheckResults != null) {
        var healthNodeList = healthCheckResults.map(function (cResult, index) {
          if (cResult != null) {
            var timeNodes = [
              {
                label: "First success",
                time: cResult.firstSuccess
              }, {
                label: "Last success",
                time: cResult.lastSuccess
              }, {
                label: "Last failure",
                time: cResult.lastFailure
              }
            ];
            var timeFields = timeNodes.map(function(timeNode, index) {
              return (
                <TimeFieldComponent
                  key={index}
                  label={timeNode.label}
                  time={timeNode.time} />
              );
            });
            return (
              <div key={index}>
                <h5 className="text-right text-muted">Health Check Result {index+1}</h5>
                <dl className="dl-horizontal">
                  {timeFields}
                  <dt>Consectutive failures</dt>
                  {cResult.consecutiveFailures == null ?
                    <dd className="text-muted">None</dd> :
                    <dd>{cResult.consecutiveFailures}</dd>}
                  <dt>Alive</dt>
                  {cResult.alive == null ?
                    <dd>No</dd> :
                    <dd>Yes</dd>}
                </dl>
                <hr key={"hr-" + index} />
              </div>
            );
          }
        });
        if (healthNodeList && healthNodeList.length) {
          healthNodeList.unshift(<hr key={"hr-" + healthNodeList.length-1} />);
        }
      }
      return (
        <div className={this.props.className}>
          {healthNodeList}
        </div>
      );
    }
  });
});

