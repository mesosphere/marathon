define([
  "Backbone",
  "Underscore"
], function(Backbone, _) {
  "use strict";

  var DEFAULT_ATTR = "id";
  var SortableCollection = Backbone.Collection.extend({
    setComparator: function(attribute) {
      attribute = attribute || DEFAULT_ATTR;
      this.sortReverse = attribute.substr(0, 1) === "-";
      this.sortKey = this.sortReverse ? attribute.substr(1) : attribute;
      this.comparator = function(a, b) {
        // Assuming that the sortKey values
        // can be compared with '>' and '<'
        var aVal = _.isFunction(a[this.sortKey]) ?
          a[this.sortKey]() :
          a.get(this.sortKey);
        var bVal = _.isFunction(b[this.sortKey]) ?
          b[this.sortKey]() :
          b.get(this.sortKey);
        return this.sortReverse
                ? bVal < aVal ? 1 : bVal > aVal ? -1 : 0 // reversed
                : aVal < bVal ? 1 : aVal > bVal ? -1 : 0; // regular
      };
    }
  });

  return SortableCollection;
});
