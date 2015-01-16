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
      {quantity: model.get("tasksHealthy"), className: "health-bar-healthy"},
      {quantity: model.get("tasksUnhealthy"), className: "health-bar-unhealthy"}, /* jscs:disable maximumLineLength */
      {quantity: tasksWithUnknownHealth, className: "health-bar-running"},
      {quantity: model.get("tasksStaged"), className: "health-bar-staged"}
    ];

    // cut off after `instances` many tasks...
    var tasksSum = 0;
    for (var i = 0; i < healthData.length; i++) {
      var capacityLeft = Math.max(0, model.get("instances") - tasksSum);
      tasksSum += healthData[i].quantity;
      healthData[i].quantity = Math.min(capacityLeft, healthData[i].quantity);
    }

    // ... show everything above that in blue
    healthData.push({
      quantity: Math.max(0, tasksSum - model.get("instances")),
      className: "health-bar-over-capacity"
    });

    // add unscheduled task, or show black if completely suspended
    var unscheduledTasks = Math.max(0, model.get("instances") - tasksSum);

    var isSuspended = model.get("instances") === 0 && tasksSum === 0;

    healthData.push({
      quantity: isSuspended ? 1 : unscheduledTasks,
      className: "health-bar-unscheduled"
    });

    return healthData;
  },

  render: function () {
    var healthData = this.getHealthData();

    // normalize quantities to add up to 100%
    var dataSum = healthData.reduce(function (a, x) {
      return a + x.quantity;
    }, 0);
    var normalizedHealthData = healthData.map(function (d) {
      return {
        width: (d.quantity * 100 / dataSum) + "%",
        className: ("progress-bar " + (d.className || "")).trim()
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
