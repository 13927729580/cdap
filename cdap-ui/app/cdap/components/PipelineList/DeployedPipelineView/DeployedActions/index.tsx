/*
 * Copyright © 2018 Cask Data, Inc.
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

import * as React from 'react';
import IconSVG from 'components/IconSVG';
import Popover from 'components/Popover';
import { deletePipeline } from 'components/PipelineList/DeployedPipelineView/store/ActionCreator';
import { IPipeline } from 'components/PipelineList/DeployedPipelineView/types';
import T from 'i18n-react';

interface IDeployedActionsProps {
  pipeline: IPipeline;
}

const DeployedActions: React.SFC<IDeployedActionsProps> = ({ pipeline }) => {
  return (
    <div className="table-column action text-xs-center">
      <Popover
        target={(props) => <IconSVG name="icon-cog-empty" {...props} />}
        className="pipeline-list-popover"
        placement="bottom"
        bubbleEvent={false}
        enableInteractionInPopover={true}
      >
        <ul>
          <li className="disabled">{T.translate('commons.duplicate')}</li>
          <li className="disabled">{T.translate('commons.export')}</li>

          <hr />

          <li className="delete" onClick={deletePipeline.bind(null, pipeline)}>
            {T.translate('commons.delete')}
          </li>
        </ul>
      </Popover>
    </div>
  );
};

export default DeployedActions;
