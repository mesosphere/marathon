define([
  "Backbone",
  "models/Task"
], function(Backbone, Task) {
  function reverseSortBy(attribute, modelA, modelB) {
    var a = modelA.get(attribute);
    var b = modelB.get(attribute);

    return (a < b) ? 1 : (a > b) ? -1 : 0;
  }

  var TaskCollection = Backbone.Collection.extend({
    initialize: function(models, options) {
      this.options = options;
      this.setComparator("-updatedAt");
    },
    model: Task,
    parse: function(response) {
      return response.tasks;
    },
    setComparator: function(comparator) {
      this.comparatorString = comparator;

      if (comparator.substr(0, 1) === "-") {
        this.comparatorAttribute = comparator.substr(1);
        this.comparator = TaskCollection.comparators[comparator] ||
          reverseSortBy.bind(null, this.comparatorAttribute);
      } else {
        this.comparatorAttribute = comparator;
        this.comparator = comparator;
      }
    },
    sortReverse: function() {
      var comparatorString = (this.comparatorString.substr(0, 1) === "-") ?
        this.comparatorString.substr(1) :
        "-" + this.comparatorString;

      this.setComparator(comparatorString);
      this.sort();
    },
    url: function() {
      return "/v2/apps/" + this.options.appId + "/tasks";
    }
  }, {
    comparators: {
      "-updatedAt": function(task) {
        return -task.get("updatedAt").getTime();
      }
    }
  });

  return TaskCollection;
});
