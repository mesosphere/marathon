/** @jsx React.DOM */

var React = require("react/addons");

var AppHealthComponent = React.createClass({
  displayName: "AppHealthComponent",

  getHealthData: function () {
    var model = this.props.model;

    var tasksWithUnknownHealth = Math.max(
      model.get("tasksRunning") -
      model.get("tasksHealthy") -
      model.get("tasksUnhealthy"),
      0
    );

    var healthData = [
      {quantity: model.get("tasksHealthy"), name: "healthy"},
      {quantity: model.get("tasksUnhealthy"), name: "unhealthy"},
      {quantity: tasksWithUnknownHealth, name: "running"},
      {quantity: model.get("tasksStaged"), name: "staged"}
    ];

    // cut off after `instances` many tasks...
    var tasksSum = 0;
    for (var i = 0; i < healthData.length; i++) {
      var capacityLeft = Math.max(0, model.get("instances") - tasksSum);
      tasksSum += healthData[i].quantity;
      healthData[i].quantity = Math.min(capacityLeft, healthData[i].quantity);
    }

    // ... show everything above that in blue
    var overCapacity = Math.max(0, tasksSum - model.get("instances"));

    healthData.push({quantity: overCapacity, name: "over-capacity"});

    // add unscheduled task, or show black if completely suspended
    var isSuspended = model.get("instances") === 0 && tasksSum === 0;
    var unscheduled = Math.max(0, (model.get("instances") - tasksSum));
    var unscheduledOrSuspended = isSuspended ? 1 : unscheduled;

    healthData.push({quantity: unscheduledOrSuspended, name: "unscheduled"});

    return healthData;
  },

  render: function () {
    var healthData = this.getHealthData();

    // normalize quantities to add up to 100%. Cut off digits at
    // third decimal to work around rounding error leading to more than 100%.
    var dataSum = healthData.reduce(function (a, x) {
      return a + x.quantity;
    }, 0);

    var roundWorkaround = function (x) { return Math.floor(x * 1000) / 1000; };

    var normalizedHealthData = healthData.map(function (d) {
      return {
        width: roundWorkaround(d.quantity * 100 / dataSum) + "%",
        className: "progress-bar health-bar-" + d.name
      };
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div className="progress health-bar">
        {normalizedHealthData.map(function (d, i) {
          return <div className={d.className} style={{width: d.width}} key={i} />;
        })}
      </div>
    );
  }
});

module.exports = AppHealthComponent;
