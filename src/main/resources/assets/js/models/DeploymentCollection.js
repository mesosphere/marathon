define([
  "models/Deployment",
  "models/SortableCollection"
], function(Deployment, SortableCollection) {
  "use strict";

  return SortableCollection.extend({
    model: Deployment,

    initialize: function(models, options) {
      this.options = options;
      this.setComparator("-id");
      this.sort();
    },

    url: function() {
      return "/v2/deployments";
    }
  });
});
