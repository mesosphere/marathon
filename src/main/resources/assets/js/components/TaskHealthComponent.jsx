define([
  "React"
], function(React) {
  function createTimeFields(timeNodes) {
    return timeNodes.map(function(timeField) {
      var node = [<dt>{timeField.label}</dt>];
      node.push(
        timeField.time == null ?
          <dd className="text-muted">None</dd> :
          <dd>
            <time dateTime={timeField.time}>
              {new Date(timeField.time).toLocaleString()}
            </time>
          </dd>
      );
      return node;
    });
  }

  return React.createClass({
    displayName: "TaskHealthComponent",
    propTypes: {
      task: React.PropTypes.object.isRequired
    },
    render: function() {
      var task = this.props.task;
      var healthCheckResults = task.get("healthCheckResults");
      var timeFields;
      var timeNodes;
      if (healthCheckResults != null) {
        healthNodeList = healthCheckResults.map(function (cResult, index) {
          if (cResult != null) {
            timeNodes = [
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
            timeFields = createTimeFields(timeNodes);
            var aliveNode = (cResult.alive == null ?
              <dd>No</dd> :
              <dd>Yes</dd>);
            return (
              <div key={index}>
                <h5 className="text-right text-muted">Health Check Result {index+1}</h5>
                <dl className="dl-horizontal">
                  {timeFields}
                  <dt>Alive</dt>
                  {aliveNode}
                </dl>
                <hr />
              </div>
            );
          }
        });
        if (healthNodeList.length > 0) {
          healthNodeList.unshift(<hr key="hr" />);
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
        
