// adapted from https://github.com/dainbrump/ng-epoch
var ngEpoch = angular.module(PKG.name+'.commons');

var baseDirective = {
  restrict: 'E',
  replace: true,
  template: '<div class="epoch"></div>',
  scope: {
    history: '=',
    stream: '='
  },
  controller: 'epochController'
};

ngEpoch.controller('epochController', function ($scope, $compile, myResizeManager) {

  $scope.initEpoch = function (elem, type, attr, forcedOpts) {
    if($scope.me) {
      return;
    }

    var options = {};

    angular.forEach(attr, function (v, k) {
      if ( v && k.indexOf('chart')===0 ) {
        var key = k.substring(5);
        this[key.charAt(0).toLowerCase() + key.slice(1)] = $scope.$eval(v);
      }
    }, options);

    angular.extend(options, forcedOpts || {}, {
      data: angular.copy($scope.history),
      el: elem[0]
    });

    if(!options.data) {
      options.data = [{values:[{
        time: ((new Date()).getTime() / 1000)|0,
        y: 0
      }]}]
    }

    $scope.type = type;
    $scope.options = options;

    if(attr.history) {
      $scope.$watch('history', function (newVal) {
        if(newVal) {
          $scope.options.data = newVal;
          render();
        }
      });
    }
    else {
      render();
    }

    if(type.indexOf('time.')===0) {
      $scope.$watch('stream', function (newVal) {
        if(!$scope.me) {
          return;
        }
        if (type === 'time.gauge') {
          $scope.me.update(newVal);
        }
        else if (newVal && newVal.length) {
          $scope.me.push(newVal);
        }
      });
    }
  };

  function render () {
    var o = $scope.options,
        el = angular.element(o.el).empty();
    console.log('[epoch]', $scope.type, o);
    $scope.me = new Epoch._typeMap[$scope.type](o);
    $scope.me.draw();
    $compile(el)($scope);
  };

  $scope.$on(myResizeManager.eventName, render);

});


ngEpoch.directive('epochPie', function () {
  return angular.extend({
    link: function (scope, elem, attr) {
      scope.initEpoch(elem, 'pie', attr);
    }
  }, baseDirective);
});



ngEpoch.directive('epochBar', function () {
  return angular.extend({
    link: function (scope, elem, attr) {
      scope.initEpoch(elem, 'bar', attr);
    }
  }, baseDirective);
});

ngEpoch.directive('epochLiveBar', function () {
  return angular.extend({
    link: function (scope, elem, attr) {
      scope.initEpoch(elem, 'time.bar', attr);
    }
  }, baseDirective);
});



ngEpoch.directive('epochLine', function () {
  return angular.extend({
    link: function (scope, elem, attr) {
      scope.initEpoch(elem, 'line', attr);
    }
  }, baseDirective);
});

ngEpoch.directive('epochLiveLine', function () {
  return angular.extend({
    link: function (scope, elem, attr) {
      scope.initEpoch(elem, 'time.line', attr, {
        axes: ['left', 'bottom'],
        ticks: { left: 5, time: 15 }
      });
    }
  }, baseDirective);
});



ngEpoch.directive('epochArea', function () {
  return angular.extend({
    link: function (scope, elem, attr) {
      scope.initEpoch(elem, 'area', attr);
    }
  }, baseDirective);
});

ngEpoch.directive('epochLiveArea', function () {
  return angular.extend({
    link: function (scope, elem, attr) {
      scope.initEpoch(elem, 'time.area', attr);
    }
  }, baseDirective);
});




ngEpoch.directive('epochGauge', function () {
  return angular.extend({
    link: function (scope, elem, attr) {
      scope.initEpoch(elem, 'time.gauge', attr, {
        domain: [0, 1000],
        format: function(v) { return v.toFixed(2); }
      });
    }
  }, baseDirective);
});


