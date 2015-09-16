/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

angular.module(PKG.name + '.feature.adapters')
  .factory('AdapterDetail', function(myWorkFlowApi, myMapreduceApi, myWorkersApi, GLOBALS) {

    var publicObj = {};

    function initialize(app, $state, $scope) {
      publicObj.programType = app.artifact.name === GLOBALS.etlBatch ? 'WORKFLOWS' : 'WORKERS';
      publicObj.params = {
        namespace: $state.params.namespace,
        appId: app.name,
        scope: $scope
      };

      publicObj.logsParams = {
        namespace: $state.params.namespace,
        appId: app.name,
        max: 50,
        scope: $scope
      };

      if (publicObj.programType === 'WORKFLOWS') {
        publicObj.api = myWorkFlowApi;
        publicObj.logsApi = myMapreduceApi;

        angular.forEach(app.programs, function (program) {
          if (program.type === 'Workflow') {
            publicObj.params.workflowId = program.id;
          } else if (program.type === 'Mapreduce') {
            publicObj.logsParams.mapreduceId = program.id;
          }
        });

      } else {
        publicObj.api = myWorkersApi;
        publicObj.logsApi = myWorkersApi;
      }
    }


    return {
      initialize: initialize,
      properties: publicObj
    };

  });
