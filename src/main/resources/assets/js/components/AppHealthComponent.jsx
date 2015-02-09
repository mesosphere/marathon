/** @jsx React.DOM */

var React = require("react/addons");

function roundWorkaround(x) {
  return Math.floor(x * 1000) / 1000;
}

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

  getHealthBar: function () {
    var healthData = this.getHealthData();

    // normalize quantities to add up to 100%. Cut off digits at
    // third decimal to work around rounding error leading to more than 100%.
    var dataSum = healthData.reduce(function (a, x) {
      return a + x.quantity;
    }, 0);

    var allZeroWidthBefore = true;
    return healthData.map(function (d, i) {
      var width = roundWorkaround(d.quantity * 100 / dataSum);
      var classSet = {
        // set health-bar-inner class for bars in the stack which have a
        // non-zero-width left neightbar
        "health-bar-inner": width !== 0 && !allZeroWidthBefore,
        "progress-bar": true
      };
      // add health bar name
      classSet["health-bar-" + d.name] = true;

      if (width !== 0) {
        allZeroWidthBefore = false;
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      return (
        <div
          className={React.addons.classSet(classSet)}
          style={{width: width + "%"}}
          key={i} />
      );
    });
  },

  render: function () {
    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div className="progress health-bar">
        {this.getHealthBar()}
      </div>
    );
  }
});

module.exports = AppHealthComponent;
